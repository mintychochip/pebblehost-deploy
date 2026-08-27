## Commits
7ef77e6 docs: describe pb binary resolution and pbVersion

## Diff
diff --git a/README.md b/README.md
index 8bab0b6..5538686 100644
--- a/README.md
+++ b/README.md
@@ -92,20 +92,39 @@ pebblehost {
     restart = true                   // manual default: restart + verify
     verifyState = "running"
     verifyTimeoutMs = 180_000
     rollback = "abort"               // "abort" | "restore"
     pbBinary = "pb"                  // optional: path to the pb binary
     target("abc123")                 // group defaults to "default"
     target("def456")                 // add more servers as needed
 }
 ```
 
+### pb binary resolution
+
+The deploy task needs the [`pb` CLI](https://github.com/mintychochip/pebblehost-cli).
+Resolution order: an explicit `pbBinary` path (must exist), `pb` on your
+`PATH`, otherwise a release binary is downloaded automatically, verified
+against the published sha256 digest, cached under
+`$GRADLE_USER_HOME/caches/pebblehost-deploy/pb/<version>/`, and reused from
+there.
+
+```kotlin
+pebblehost {
+    pbVersion = "latest"   // default; or pin, e.g. "2026.8.21.16"
+}
+```
+
+`latest` makes one GitHub API call per deploy run; set `GITHUB_TOKEN` in the
+environment to avoid unauthenticated rate limits. Pinned versions are fully
+offline once cached.
+
 Manual: `./gradlew :test-plugin:deployPebbleHost`
 
 CI: run the reusable `deploy.yml` workflow (workflow_dispatch). It builds the
 jar, installs `pb`, and runs the same task with `PEBBLEHOST_API_TOKEN` from
 secrets.
 
 ## Rollout
 
 - `flat`: all targets deploy in parallel.
 - `groups`: targets grouped by `group` deploy in parallel within a group;
