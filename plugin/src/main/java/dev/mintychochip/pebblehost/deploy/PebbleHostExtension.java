package dev.mintychochip.pebblehost.deploy;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class PebbleHostExtension {
    private final ObjectFactory objects;
    private final Property<String> token;
    private final Property<String> baseUrl;
    private final RegularFileProperty jar;
    private final Property<String> targetDir;
    private final Property<String> strategy;
    private final Property<Boolean> canaryGate;
    private final Property<Boolean> continueAfterCanary;
    private final Property<Boolean> restart;
    private final Property<String> verifyState;
    private final Property<Long> verifyTimeoutMs;
    private final Property<String> rollback;
    private final Property<String> pbBinary;
    private final Property<String> pbVersion;
    private final ListProperty<Target> targets;

    @Inject
    public PebbleHostExtension(ObjectFactory objects) {
        this.objects = objects;
        this.token = objects.property(String.class);
        this.baseUrl = objects.property(String.class).convention("https://panel.pebblehost.com");
        this.jar = objects.fileProperty();
        this.targetDir = objects.property(String.class).convention("plugins");
        this.strategy = objects.property(String.class).convention("groups");
        this.canaryGate = objects.property(Boolean.class).convention(true);
        this.continueAfterCanary = objects.property(Boolean.class).convention(false);
        this.restart = objects.property(Boolean.class).convention(true);
        this.verifyState = objects.property(String.class).convention("running");
        this.verifyTimeoutMs = objects.property(Long.class).convention(180_000L);
        this.rollback = objects.property(String.class).convention("abort");
        this.pbBinary = objects.property(String.class).convention("pb");
        this.pbVersion = objects.property(String.class).convention("latest");
        this.targets = objects.listProperty(Target.class);
    }

    public Property<String> getToken() { return token; }
    public Property<String> getBaseUrl() { return baseUrl; }
    public RegularFileProperty getJar() { return jar; }
    public Property<String> getTargetDir() { return targetDir; }
    public Property<String> getStrategy() { return strategy; }
    public Property<Boolean> getCanaryGate() { return canaryGate; }
    public Property<Boolean> getContinueAfterCanary() { return continueAfterCanary; }
    public Property<Boolean> getRestart() { return restart; }
    public Property<String> getVerifyState() { return verifyState; }
    public Property<Long> getVerifyTimeoutMs() { return verifyTimeoutMs; }
    public Property<String> getRollback() { return rollback; }
    public Property<String> getPbBinary() { return pbBinary; }
    public Property<String> getPbVersion() { return pbVersion; }
    public ListProperty<Target> getTargets() { return targets; }

    /** Convenience: add a target server to the rollout. */
    public void target(String serverId) {
        Target t = objects.newInstance(Target.class);
        t.getServerId().set(serverId);
        targets.add(t);
    }
}
