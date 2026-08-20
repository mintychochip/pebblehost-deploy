package dev.pebblehost.deploy;

import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RolloutOrchestratorTest {
    static class ScriptedRunner implements CommandRunner {
        final List<String> outputs = new ArrayList<>();
        int i = 0;
        @Override
        public String run(List<String> command, Map<String, String> env, Duration timeout) {
            return outputs.get(Math.min(i++, outputs.size() - 1));
        }
    }

    private Target target(String id, String group) {
        Target t = org.gradle.testfixtures.ProjectBuilder.builder().build().getObjects().newInstance(Target.class);
        t.getServerId().set(id);
        t.getGroup().set(group);
        return t;
    }

    private DeployConfig config(String rollback) throws IOException {
        Path dir = Files.createTempDirectory("deploy-test");
        dir.toFile().deleteOnExit();
        Path jar = dir.resolve("a.jar");
        Files.writeString(jar, "fake jar");
        jar.toFile().deleteOnExit();
        return DeployConfig.from("tok", null, jar.toFile(), "plugins", true, "running", 1, rollback);
    }

    @Test
    void successfulFlatDeployRunsFullSequence() throws Exception {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add(""); // --version
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}"); // list
        r.outputs.add(""); // rename
        r.outputs.add(""); // push
        r.outputs.add(""); // power
        r.outputs.add("{\"attributes\":{\"current_state\":\"running\"}}"); // verify
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(List.of(target("srv-1", "default")), "flat", true, false);
        new RolloutOrchestrator(c, plan, config("abort"), Logging.getLogger(RolloutOrchestratorTest.class)).execute();
        assertTrue(true); // no exception = success
    }

    @Test
    void abortPolicyThrowsOnVerifyFailure() {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add(""); // --version
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.outputs.add(""); // rename
        r.outputs.add(""); // push
        r.outputs.add(""); // power
        r.outputs.add("{\"attributes\":{\"current_state\":\"offline\"}}"); // verify keeps failing
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(List.of(target("srv-1", "default")), "flat", true, false);
        assertThrows(Exception.class,
            () -> new RolloutOrchestrator(c, plan, config("abort"), Logging.getLogger(RolloutOrchestratorTest.class)).execute());
    }

    @Test
    void canaryGateStopsAfterFirstGroup() throws Exception {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add(""); // --version
        // group "canary": list, rename, push, power, verify
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.outputs.add(""); r.outputs.add(""); r.outputs.add("");
        r.outputs.add("{\"attributes\":{\"current_state\":\"running\"}}");
        // group "prod" must NOT run — runner would throw IndexOutOfBounds if it did
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(
            List.of(target("canary-1", "canary"), target("prod-1", "prod")), "groups", true, false);
        new RolloutOrchestrator(c, plan, config("abort"), Logging.getLogger(RolloutOrchestratorTest.class)).execute();
        assertEquals(6, r.i); // only canary group consumed outputs
    }

    @Test
    void restorePolicyRollsBackOnFailure() throws Exception {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add(""); // --version
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.outputs.add(""); // rename -> backup
        r.outputs.add(""); // push
        r.outputs.add(""); // power
        r.outputs.add("{\"attributes\":{\"current_state\":\"offline\"}}"); // verify fails
        r.outputs.add(""); // delete bad jar (rollback)
        r.outputs.add(""); // rename backup back (rollback)
        r.outputs.add(""); // power (rollback restart)
        r.outputs.add("{\"attributes\":{\"current_state\":\"running\"}}"); // rollback verify
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(List.of(target("srv-1", "default")), "flat", true, false);
        assertThrows(Exception.class,
            () -> new RolloutOrchestrator(c, plan, config("restore"), Logging.getLogger(RolloutOrchestratorTest.class)).execute());
        // 6 deploy calls (version,list,rename,push,power,verify) + 5 rollback calls
        // (delete,rename,power,verify,verify-final) with 1ms verify timeout.
        assertEquals(11, r.i);
    }

    @Test
    void restoreRollsBackSuccessfulPeersInFailedGroup() throws Exception {
        // Thread-safe, command-aware fake: responds per server ID and records
        // every command so we can assert rollback happened for BOTH peers.
        ServerAwareRunner r = new ServerAwareRunner();
        r.on("srv-ok", "files", "{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.on("srv-ok", "resources", "{\"attributes\":{\"current_state\":\"running\"}}");
        r.on("srv-bad", "files", "{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.on("srv-bad", "resources", "{\"attributes\":{\"current_state\":\"offline\"}}");
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(
            List.of(target("srv-ok", "default"), target("srv-bad", "default")), "flat", true, false);
        assertThrows(Exception.class,
            () -> new RolloutOrchestrator(c, plan, config("restore"), Logging.getLogger(RolloutOrchestratorTest.class)).execute());
        // Both peers must have been rolled back: delete + rename-back + power + verify each.
        assertRolledBack(r, "srv-ok");
        assertRolledBack(r, "srv-bad");
    }

    @Test
    void uploadFailurePreservesBackupForRollback() throws Exception {
        // Single server, flat deploy -> one-thread pool, deterministic order.
        // The fake throws on the push command so the backup path is preserved.
        ServerAwareRunner r = new ServerAwareRunner();
        r.on("srv-1", "files", "{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.on("srv-1", "resources", "{\"attributes\":{\"current_state\":\"running\"}}");
        r.failOn("srv-1", "file"); // pb file push throws
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(List.of(target("srv-1", "default")), "flat", true, false);
        assertThrows(Exception.class,
            () -> new RolloutOrchestrator(c, plan, config("restore"), Logging.getLogger(RolloutOrchestratorTest.class)).execute());
        assertRolledBack(r, "srv-1");
    }

    private void assertRolledBack(ServerAwareRunner r, String serverId) {
        List<List<String>> cmds = r.commandsFor(serverId);
        // Must contain a delete of the bad jar and a rename of the backup back.
        assertTrue(cmds.stream().anyMatch(c -> c.stream().anyMatch(a -> a.contains("delete"))),
            "expected delete for " + serverId + " but got: " + cmds);
        assertTrue(cmds.stream().anyMatch(c -> c.stream().anyMatch(a -> a.contains("rename"))),
            "expected rename-back for " + serverId + " but got: " + cmds);
        assertTrue(cmds.stream().anyMatch(c -> c.stream().anyMatch(a -> a.contains("power"))),
            "expected restart for " + serverId + " but got: " + cmds);
    }

    /** Thread-safe fake pb keyed by server ID; records every command. */
    static class ServerAwareRunner implements CommandRunner {
        private final Map<String, Map<String, String>> responses = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, List<List<String>>> recorded = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, String> failOn = new java.util.concurrent.ConcurrentHashMap<>();

        void on(String serverId, String subcommand, String output) {
            responses.computeIfAbsent(serverId, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(subcommand, output);
        }
        void failOn(String serverId, String subcommand) {
            failOn.put(serverId, subcommand);
        }
        List<List<String>> commandsFor(String serverId) {
            return recorded.getOrDefault(serverId, List.of());
        }
        @Override
        public String run(List<String> command, Map<String, String> env, Duration timeout) {
            String serverId = command.stream().anyMatch(a -> a.contains("srv-1")) ? "srv-1"
                : command.stream().anyMatch(a -> a.contains("srv-ok")) ? "srv-ok"
                : command.stream().anyMatch(a -> a.contains("srv-bad")) ? "srv-bad"
                : "unknown";
            recorded.computeIfAbsent(serverId, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(command);
            String sub = command.stream().skip(1).filter(s -> !s.startsWith("-")).findFirst().orElse("");
            if (failOn.containsKey(serverId) && failOn.get(serverId).equals(sub)) {
                throw new RuntimeException("upload failed");
            }
            Map<String, String> bySub = responses.get(serverId);
            return bySub == null ? "" : bySub.getOrDefault(sub, "");
        }
    }
}
