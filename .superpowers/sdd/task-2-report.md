# Task 2 Report: Network Install Path Test Coverage

## Summary

Added three focused integration tests to `PbInstallerTest` covering the offline-stub network install path: digest-verified download from release metadata, cache reuse on pinned resolve, and clear failure when no platform asset is published.

## What Was Added

### Helper (private)

- `realTarballContainingPb()` — builds a real gzip tarball via system `tar` containing `currentBinName()` (not hardcoded `pb`). Archive member is **not** chmod'd before packing so post-install `Files.isExecutable` proves installer chmod.

### Tests (4 `@Test` methods)

| Test | Behavior verified |
|------|-------------------|
| `installsFromReleasesVerifyingDigestThenServesFromCache` | Stub serves metadata with `pebblehost-cli-{version}-{currentTarget()}.tar.gz` and matching `sha256:` digest; `resolve("pb","latest")` downloads, verifies digest, extracts to `cache/v2026.9.1.42/{currentBinName()}`, returns absolute path; file exists and is executable. After stopping the stub, `resolve("pb","2026.9.1.42")` returns the same path from cache with no network. |
| `pinnedVersionIsOfflineOnCacheHit` | Pre-creates `cache/v2026.9.1.42/{currentBinName()}`; `apiBase` points to `http://127.0.0.1:1/releases/` (unreachable); `resolve("pb","2026.9.1.42")` returns cached absolute path without network. |
| `digestMismatchRejectsDownloadedAsset` | Real tarball via helper; stub advertises 64-zero digest; `resolve("pb","latest")` throws `GradleException` with `sha256 verification`; `countBinaryFiles(cache) == 0`. |
| `missingPlatformAssetFailsWithClearMessage` | Inline `HttpServer` serves `{"tag_name":"v2026.9.1.42","assets":[]}`; `resolve("pb","latest")` throws `GradleException` with message containing `publishes no asset`. |

### Adaptations from brief (Task 1 hardening)

1. Reused existing helpers: `currentTarget()`, `currentBinName()`, `startStub(...)`, `countBinaryFiles()` — no redefinition of stub/platform helpers.
2. Asset names built as `"pebblehost-cli-" + version + "-" + currentTarget() + ".tar.gz"` via `startStub`; installed paths use `cache.resolve(tag).resolve(currentBinName())`.
3. Did not add `digestMismatchInstallsNothing` from the brief initially — added `digestMismatchRejectsDownloadedAsset` in review fix (see below).
4. `pinnedVersionIsOfflineOnCacheHit` uses unreachable port 1 instead of relying on stub stop — proves offline cache hit for pinned version spec.

## Files Changed

| File | Change |
|------|--------|
| `plugin/src/test/java/dev/mintychochip/pebblehost/deploy/PbInstallerTest.java` | helper + 4 network-install tests (+ review fix) |

No production (`main`) sources modified.

## Test Evidence

### Command

```bash
./gradlew :plugin:test --tests dev.mintychochip.pebblehost.deploy.PbInstallerTest
```

### Result: GREEN

```
BUILD SUCCESSFUL in 1s
```

JUnit XML (`plugin/build/test-results/test/TEST-dev.mintychochip.pebblehost.deploy.PbInstallerTest.xml`):

- **17 tests**, 0 failures, 0 errors, 0 skipped
- Network-install tests passing:
  - `installsFromReleasesVerifyingDigestThenServesFromCache()`
  - `digestMismatchRejectsDownloadedAsset()`
  - `pinnedVersionIsOfflineOnCacheHit()`
  - `missingPlatformAssetFailsWithClearMessage()`

### RED phase

Not recorded in this session — Task 1 implementation already satisfied these paths; tests were added against green implementation per brief Step 2 expectation. Prior Task 1 tests (`digestMissingFailsHard`, `corruptedArchiveLeavesNoCachedBinary`) remain green alongside the new coverage.

## Commit

```
ad00b5f test(plugin): cover pb download, digest verification, and caching
b2e5185 test(plugin): exercise digest mismatch and installer chmod
```

## Review fix (post ad00b5f)

Reviewer flagged two gaps in ad00b5f:

1. **Digest mismatch not exercised** — happy-path stub always advertised the correct digest for tarball bytes. Added `digestMismatchRejectsDownloadedAsset`: real tarball, stub digest = 64 zeros, expect `sha256 verification` and zero cached binaries.
2. **Chmod assertion vacuous** — `realTarballContainingPb()` pre-set executable before `tar`, so `Files.isExecutable(installed)` did not prove installer chmod. Removed `setExecutable(true)` before archiving; happy-path test still green (installer sets `rwxr-xr-x` on non-Windows).

Re-run: `./gradlew :plugin:test --tests dev.mintychochip.pebblehost.deploy.PbInstallerTest` — GREEN (17 tests).

## Concerns

None. All four network-install tests pass on Linux x86_64; platform-agnostic helpers ensure correct asset naming and binary resolution on macOS/Windows CI as well.
