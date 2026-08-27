## Commits
67d1027 feat(plugin): wire pbVersion DSL and auto-resolve pb in deploy task

## Stat
 .../dev/mintychochip/pebblehost/deploy/DeployPebbleHostTask.java  | 8 +++++++-
 .../dev/mintychochip/pebblehost/deploy/PebbleHostExtension.java   | 3 +++
 .../java/dev/mintychochip/pebblehost/deploy/PebbleHostPlugin.java | 1 +
 3 files changed, 11 insertions(+), 1 deletion(-)

## Diff
diff --git a/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/DeployPebbleHostTask.java b/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/DeployPebbleHostTask.java
index b497bc9..cdfb066 100644
--- a/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/DeployPebbleHostTask.java
+++ b/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/DeployPebbleHostTask.java
@@ -24,38 +24,44 @@ public abstract class DeployPebbleHostTask extends DefaultTask {
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
+    @Input public abstract Property<String> getPbVersion();
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
-        PebbleHostClient client = new PebbleHostClient(getPbBinary().get(), token, config.baseUrl(), new ProcessCommandRunner());
+        PbInstaller installer = new PbInstaller(
+            getProject().getGradle().getGradleUserHomeDir().toPath()
+                .resolve("caches").resolve("pebblehost-deploy").resolve("pb"),
+            getLogger());
+        String pb = installer.resolve(getPbBinary().get(), getPbVersion().get());
+        PebbleHostClient client = new PebbleHostClient(pb, token, config.baseUrl(), new ProcessCommandRunner());
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
diff --git a/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostExtension.java b/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostExtension.java
index 0587154..f93fc65 100644
--- a/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostExtension.java
+++ b/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostExtension.java
@@ -14,51 +14,54 @@ public abstract class PebbleHostExtension {
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
+    private final Property<String> pbVersion;
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
+        this.pbVersion = objects.property(String.class).convention("latest");
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
+    public Property<String> getPbVersion() { return pbVersion; }
     public ListProperty<Target> getTargets() { return targets; }
 
     /** Convenience: add a target server to the rollout. */
     public void target(String serverId) {
         Target t = objects.newInstance(Target.class);
         t.getServerId().set(serverId);
         targets.add(t);
     }
 }
diff --git a/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostPlugin.java b/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostPlugin.java
index 52a221b..abfe1f2 100644
--- a/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostPlugin.java
+++ b/plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostPlugin.java
@@ -15,14 +15,15 @@ public class PebbleHostPlugin implements Plugin<Project> {
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
+            task.getPbVersion().set(ext.getPbVersion());
             task.getTargets().set(ext.getTargets());
         });
     }
 }
