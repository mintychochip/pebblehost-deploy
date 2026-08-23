package dev.mintychochip.pebblehost.deploy;

import dev.mintychochip.pebblehost.deploy.operations.*;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RolloutOrchestrator {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final PebbleHostClient client;
    private final RolloutPlanner.RolloutPlan plan;
    private final DeployConfig config;
    private final Logger logger;

    public RolloutOrchestrator(PebbleHostClient client, RolloutPlanner.RolloutPlan plan, DeployConfig config, Logger logger) {
        this.client = client;
        this.plan = plan;
        this.config = config;
        this.logger = logger;
    }

    public void execute() throws Exception {
        client.validateBinary();
        List<ServerResult> all = new ArrayList<>();
        List<String> rollbackOutcomes = new ArrayList<>();
        for (RolloutPlanner.RolloutGroup group : plan.groups()) {
            logger.lifecycle("Deploying group '{}' ({} server(s))", group.name(), group.targets().size());
            List<ServerResult> results = deployGroup(group);
            all.addAll(results);
            boolean failed = results.stream().anyMatch(r -> !r.success());
            if (failed) {
                rollbackOutcomes.addAll(handleFailure(results));
                throw new GradleException(buildReport(all, rollbackOutcomes));
            }
            if (plan.canaryGate() && !plan.continueAfterCanary() && !plan.isLast(group)) {
                logger.lifecycle("Canary group '{}' deployed and verified. Re-run with --continue-after-canary to deploy the remaining groups.", group.name());
                break;
            }
        }
        logger.lifecycle(buildReport(all, rollbackOutcomes));
    }

    private List<ServerResult> deployGroup(RolloutPlanner.RolloutGroup group) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(group.targets().size());
        List<Future<ServerResult>> futures = new ArrayList<>();
        for (Target t : group.targets()) {
            futures.add(pool.submit(() -> deployServer(t)));
        }
        List<ServerResult> results = new ArrayList<>();
        for (Future<ServerResult> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                results.add(ServerResult.failure("unknown", String.valueOf(e.getCause()), null));
            }
        }
        pool.shutdown();
        return results;
    }

    ServerResult deployServer(Target target) {
        String serverId = target.getServerId().get();
        boolean restart = target.getRestart().getOrElse(config.restart());
        String jarName = config.jar().getName();
        String timestamp = LocalDateTime.now().format(TS);
        String backupPath = null;
        try {
            backupPath = new BackupOperation(client).backup(serverId, config.targetDir(), jarName, timestamp);
            new UploadOperation(client).push(serverId, config.jar().getAbsolutePath(), config.targetDir());
            if (restart) {
                new PowerOperation(client).restart(serverId);
                boolean ok = new VerifyOperation(client).verify(serverId, config.verifyState(), config.verifyTimeoutMs());
                if (!ok) {
                    return ServerResult.failure(serverId,
                        "server did not reach state '" + config.verifyState() + "' within " + config.verifyTimeoutMs() + "ms",
                        backupPath);
                }
            }
            return ServerResult.success(serverId, backupPath, restart);
        } catch (Exception e) {
            return ServerResult.failure(serverId, e.getMessage(), backupPath);
        }
    }

    private List<String> handleFailure(List<ServerResult> results) {
        List<String> outcomes = new ArrayList<>();
        if (!config.rollback().equals("restore")) return outcomes;
        // Roll back ALL servers in the failed group that have a backup, so the
        // group is left consistent (not a mix of old and new versions).
        for (ServerResult r : results) {
            if (r.backupPath() != null) {
                try {
                    new RollbackOperation(client).restore(r.serverId(), config.targetDir(), config.jar().getName(),
                        r.backupPath(), true, config.verifyState(), config.verifyTimeoutMs());
                    logger.warn("Rolled back {} to {}", r.serverId(), r.backupPath());
                    outcomes.add(r.serverId() + ": rolled back to " + r.backupPath());
                } catch (Exception e) {
                    logger.error("Rollback failed for {}: {}", r.serverId(), e.getMessage());
                    outcomes.add(r.serverId() + ": ROLLBACK FAILED — " + e.getMessage()
                        + " (backup preserved at " + r.backupPath() + ")");
                }
            }
        }
        return outcomes;
    }

    private String buildReport(List<ServerResult> results, List<String> rollbackOutcomes) {
        StringBuilder sb = new StringBuilder("PebbleHost deploy report:\n");
        for (ServerResult r : results) {
            sb.append("  ").append(r.serverId()).append(": ")
              .append(r.success() ? "OK" : "FAILED").append(" — ").append(r.message());
            if (r.backupPath() != null) sb.append(" (backup: ").append(r.backupPath()).append(")");
            sb.append('\n');
        }
        if (!rollbackOutcomes.isEmpty()) {
            sb.append("  rollbacks:\n");
            for (String o : rollbackOutcomes) sb.append("    ").append(o).append('\n');
        }
        return sb.toString();
    }
}
