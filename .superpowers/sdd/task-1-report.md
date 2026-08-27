# Task 1 Report: PbInstaller core — tiers, platform mapping, tag normalization

## What was implemented

Created `PbInstaller`, the pb CLI resolver for the Gradle plugin with three resolution tiers:

1. **Explicit `pbBinary`** — non-default path must exist as a regular file; returns absolute path or fails with actionable message.
2. **PATH lookup** — scans configured path directories for `pb` / `pb.exe`; short-circuits before any network/cache activity.
3. **Auto-install** (fallback) — downloads from GitHub releases API, sha256-verified, extracts tarball, caches per tag (full install path implemented for later tasks; not exercised by Task 1 tests).

Public API:
- `PbInstaller(Path cacheRoot, Logger logger)` — production constructor using live `PATH` and default GitHub API base.
- `String resolve(String pbBinary, String pbVersion)` — returns absolute path to usable binary.

Package-visible test seam:
- `PbInstaller(Path cacheRoot, List<String> pathDirs, Logger logger, String apiBase)`

Static helpers:
- `normalizeTag(String)` — `latest` unchanged; bare versions get `v` prefix.
- `platformTarget(String osName, String osArch)` — maps to Rust triple targets for linux/mac/windows; throws `GradleException` with `pbBinary` bypass hint on unknown platforms.

## TDD evidence

### RED — tests before implementation

**Command:**
```bash
./gradlew :plugin:compileTestJava --rerun-tasks
```

**Result:** `BUILD FAILED` — 16 compilation errors, all `cannot find symbol: class PbInstaller` / `variable PbInstaller` in `PbInstallerTest.java`.

Representative output:
```
PbInstallerTest.java:22: error: cannot find symbol
    assertEquals("x86_64-unknown-linux-gnu", PbInstaller.platformTarget("Linux", "amd64"));
                                              ^
  symbol:   variable PbInstaller
```

### GREEN — implementation added

**Command:**
```bash
./gradlew :plugin:test --tests dev.mintychochip.pebblehost.deploy.PbInstallerTest
```

**Result:** `BUILD SUCCESSFUL`

Test report (`plugin/build/test-results/test/TEST-dev.mintychochip.pebblehost.deploy.PbInstallerTest.xml`):
```
tests="10" skipped="0" failures="0" errors="0"
```

Note: brief expected 11 tests; JUnit counts 10 `@Test` methods (`mapsMacX64AndArm64` and `normalizesVersionSpecsToTags` each use multiple assertions within a single test method).

## Files changed

| File | Action |
|------|--------|
| `plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PbInstaller.java` | Created |
| `plugin/src/test/java/dev/mintychochip/pebblehost/deploy/PbInstallerTest.java` | Created |

## Commit

```
d2cdd33 feat(plugin): pb resolver with platform mapping and tier precedence
```

## Self-review

- **Tier precedence** matches brief: explicit → PATH → install; PATH hit does not create cache directory (verified by test).
- **Platform mapping** covers all brief cases; Windows ignores arch; unknown platform message includes `pbBinary` bypass text.
- **Tag normalization** handles `latest`, bare version, and `v`-prefixed version.
- **Auto-install path** is present (HTTP client, sha256, tar extract, posix permissions) but intentionally not covered by Task 1 tests — Task 2 will stub network.
- **Unused import** `StandardCopyOption` present as in brief verbatim code; harmless, left for fidelity to spec.
- **Package visibility** on test constructor is correct for same-package test access without exposing API publicly.

## Concerns

None blocking. Minor note: brief text says "11 tests" but the provided test class defines 10 `@Test` methods; all pass.

## Reviewer fix (post d2cdd33)

Applied 6 prescriptive hardening fixes to `PbInstaller`:

1. **Asset name** — lookup uses `pebblehost-cli-<ver>-<target>.tar.gz` suffix.
2. **Digest enforcement** — missing/malformed `digest` on matched asset throws before download; warn/skip path removed.
3. **Tar timeout** — `waitFor(60s)` before reading output; timeout throws `timed out extracting <asset>`.
4. **Staging extraction** — download/verify/extract in temp staging dir; atomic move into cache tag dir; staging cleaned in `finally`.
5. **Metadata parsing** — malformed JSON → `GradleException`; missing `tag_name`/`assets` guarded.
6. **Invalid explicit path** — `InvalidPathException` wrapped as actionable `GradleException`.

**Tests added:** `digestMissingFailsHard`, `invalidExplicitPbBinaryFailsGracefully`, `corruptedArchiveLeavesNoCachedBinary` (HttpServer stubs).

**Verification:**
```bash
./gradlew :plugin:test --tests dev.mintychochip.pebblehost.deploy.PbInstallerTest
# BUILD SUCCESSFUL — 13 tests, 0 failures
```

**Commit:** `fix(plugin): harden pb installer asset lookup, digest enforcement, extraction safety`

## Re-review fix (post 6a9de57)

Applied 4 residual hardening fixes:

1. **Malformed digest** — `requireSha256Digest()` rejects empty or non-64-hex payloads before any download; message includes full raw digest string.
2. **Asset shape guard** — asset-scan loop wrapped in try/catch; `GradleException` rethrown; other `RuntimeException` → actionable `GradleException` citing endpoint.
3. **Publication atomicity** — removed `REPLACE_EXISTING` fallback; `AtomicMoveNotSupportedException` deletes staging and fails with filesystem guidance.
4. **Platform-independent tests** — `currentTarget()` / `currentBinName()` helpers; stub asset names and binary counts derived from runtime OS/arch.

**Verification:**
```bash
./gradlew :plugin:test --tests dev.mintychochip.pebblehost.deploy.PbInstallerTest
# BUILD SUCCESSFUL — 13 tests, 0 failures
```

**Commit:** `fix(plugin): tighten pb metadata validation and publication atomicity`

## Third re-review fix (post d44d12e)

Applied 3 reviewer-scoped hardening fixes to `PbInstaller.java`:

1. **tag_name validation** — explicit `JsonElement` guard (`null` / non-primitive) before `getAsString()`; v-prefix check retained.
2. **Publication race** — `FileAlreadyExistsException` caught alongside `AtomicMoveNotSupportedException`; accepts concurrently installed binary when present; otherwise fails with filesystem guidance.
3. **URL guard** — `URI.create(url)` wrapped; `IllegalArgumentException` → actionable `GradleException`.

**Verification:**
```bash
./gradlew :plugin:test --tests dev.mintychochip.pebblehost.deploy.PbInstallerTest
# BUILD SUCCESSFUL — 13 tests, 0 failures
```

**Commit:** `fix(plugin): tag_name validation, publication race, URL guard`

## Fourth whole-branch fix (post 81b4284)

Applied 2 merge-blocking edge-case fixes:

1. **Request construction guard** — `send()` wraps `URI.create(url)` and `HttpRequest.newBuilder(...)` in one `IllegalArgumentException` handler; message cites invalid download URL.
2. **Platform-correct PATH test** — `pathHitShortCircuitsBeforeAutoInstall` creates candidate via `currentBinName()` (`pb` / `pb.exe`).

**Verification:**
```bash
./gradlew :plugin:test --tests dev.mintychochip.pebblehost.deploy.PbInstallerTest
# BUILD SUCCESSFUL — 17 tests, 0 failures
```

**Commit:** `fix(plugin): guard request construction and platform-correct PATH test`
