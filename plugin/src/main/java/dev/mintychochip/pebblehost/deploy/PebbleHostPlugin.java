package dev.mintychochip.pebblehost.deploy;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class PebbleHostPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        PebbleHostExtension ext = project.getExtensions().create("pebblehost", PebbleHostExtension.class);
        project.getTasks().register("deployPebbleHost", DeployPebbleHostTask.class, task -> {
            task.setGroup("deployment");
            task.setDescription("Deploy the built jar to PebbleHost servers via the pb CLI.");
            task.getToken().set(ext.getToken());
            task.getBaseUrl().set(ext.getBaseUrl());
            task.getJar().set(ext.getJar());
            task.getTargetDir().set(ext.getTargetDir());
            task.getStrategy().set(ext.getStrategy());
            task.getCanaryGate().set(ext.getCanaryGate());
            task.getContinueAfterCanary().set(ext.getContinueAfterCanary());
            task.getRestart().set(ext.getRestart());
            task.getVerifyState().set(ext.getVerifyState());
            task.getVerifyTimeoutMs().set(ext.getVerifyTimeoutMs());
            task.getRollback().set(ext.getRollback());
            task.getPbBinary().set(ext.getPbBinary());
            task.getCliVersion().set(ext.getCliVersion());
            task.getTargets().set(ext.getTargets());
        });
    }
}
