package dev.pebblehost.deploy;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.api.tasks.UntrackedTask;

import java.io.File;

@UntrackedTask(because = "Deploys to remote servers; always runs")
public abstract class DeployPebbleHostTask extends DefaultTask {
    @Input @Optional public abstract Property<String> getToken();
    @Input public abstract Property<String> getBaseUrl();
    @InputFile public abstract RegularFileProperty getJar();
    @Input public abstract Property<String> getTargetDir();
    @Input public abstract Property<String> getStrategy();
    @Input public abstract Property<Boolean> getCanaryGate();
    @Input public abstract Property<Boolean> getContinueAfterCanary();
    @Input public abstract Property<Boolean> getRestart();
    @Input public abstract Property<String> getVerifyState();
    @Input public abstract Property<Long> getVerifyTimeoutMs();
    @Input public abstract Property<String> getRollback();
    @Input public abstract Property<String> getPbBinary();
    @Input public abstract ListProperty<Target> getTargets();

    @Option(option = "deploy-restart", description = "Restart servers after upload (true/false)")
    public void setRestartOption(String value) { getRestart().set(Boolean.parseBoolean(value)); }

    @Option(option = "deploy-rollback", description = "Rollback policy on failure: abort or restore")
    public void setRollbackOption(String value) { getRollback().set(value); }

    @Option(option = "continue-after-canary", description = "Proceed past the canary group (true/false)")
    public void setContinueAfterCanaryOption(String value) { getContinueAfterCanary().set(Boolean.parseBoolean(value)); }

    @TaskAction
    public void deploy() {
        String token = resolveToken();
        File jar = getJar().get().getAsFile();
        DeployConfig config = DeployConfig.from(token, getBaseUrl().get(), jar, getTargetDir().get(),
            getRestart().get(), getVerifyState().get(), getVerifyTimeoutMs().get(), getRollback().get());
        PebbleHostClient client = new PebbleHostClient(getPbBinary().get(), token, config.baseUrl(), new ProcessCommandRunner());
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(getTargets().get(), getStrategy().get(),
            getCanaryGate().get(), getContinueAfterCanary().get());
        try {
            new RolloutOrchestrator(client, plan, config, getLogger()).execute();
        } catch (GradleException e) {
            throw e;
        } catch (Exception e) {
            throw new GradleException("PebbleHost deploy failed: " + e.getMessage(), e);
        }
    }

    private String resolveToken() {
        if (getToken().isPresent() && !getToken().get().isBlank()) return getToken().get();
        String env = System.getenv("PEBBLEHOST_API_TOKEN");
        if (env != null && !env.isBlank()) return env;
        Object prop = getProject().findProperty("pebblehostToken");
        if (prop != null && !prop.toString().isBlank()) return prop.toString();
        throw new GradleException("Missing PebbleHost API token: set pebblehost.token, PEBBLEHOST_API_TOKEN, or gradle.properties pebblehostToken");
    }
}
