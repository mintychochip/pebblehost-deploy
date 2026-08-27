# Task 3 Report: DSL + task wiring for pbVersion and pb auto-resolution

## Status

**Complete**

## Commit

- **SHA:** `67d1027fdbc079ee2b41e3826020ca5983fc924d`
- **Subject:** `feat(plugin): wire pbVersion DSL and auto-resolve pb in deploy task`

## Edits per file

### `PebbleHostExtension.java`

- Added `private final Property<String> pbVersion` field alongside `pbBinary`.
- Initialized in constructor with convention `"latest"`.
- Exposed `public Property<String> getPbVersion()`.

### `PebbleHostPlugin.java`

- Wired extension → task: `task.getPbVersion().set(ext.getPbVersion())` after `pbBinary` registration.

### `DeployPebbleHostTask.java`

- Added `@Input public abstract Property<String> getPbVersion()`.
- Replaced direct `PebbleHostClient` construction with `PbInstaller` resolution:
  - Cache root: `<gradleUserHome>/caches/pebblehost-deploy/pb`
  - `String pb = installer.resolve(getPbBinary().get(), getPbVersion().get())`
  - `PebbleHostClient` receives resolved absolute path.

## Consistency check

Grepped all `pbBinary` / `pbVersion` / `PbInstaller` / `PebbleHostClient` call sites:

| Location | Role | Status |
|---|---|---|
| `PebbleHostExtension` | DSL property + `"latest"` convention | Added |
| `PebbleHostPlugin` | Extension → task wiring | Added |
| `DeployPebbleHostTask` | Task input + `PbInstaller.resolve()` before client | Added |
| `DeployFunctionalTest` | Sets explicit `pbBinary` fake path in Gradle DSL | Unchanged — flows through extension → task → installer tier 1 (explicit path) |
| `PbInstallerTest` | Unit tests for installer | Unchanged — no DSL wiring needed |

No duplicate copies or missed call sites. `PebbleHostClient` is only constructed in `DeployPebbleHostTask.deploy()`.

## Scope check

- **3 files changed**, 11 insertions, 1 deletion — matches brief exactly.
- No new files, no test changes, no unrelated refactors.
- Behavior outside deploy resolution unchanged; functional tests still pass explicit fake-pb paths (tier 1, no network).

## Verification

```bash
./gradlew clean build; echo "exit=$?"
```

- **Result:** `BUILD SUCCESSFUL`
- **Exit code:** `0`

### Plugin test breakdown (8 suites, 50 tests, 0 failures, 0 errors)

| Suite | Tests |
|---|---|
| DeployConfigTest | 3 |
| DeployFunctionalTest | 2 |
| PbInstallerTest | 17 |
| PebbleHostClientTest | 7 |
| PebbleHostPluginTest | 2 |
| RolloutOrchestratorTest | 7 |
| RolloutPlannerTest | 6 |
| OperationsTest | 6 |

All existing suites plus `PbInstallerTest` green.

## Self-review

1. **DSL convention:** `pbVersion` defaults to `"latest"` per spec; users can pin via `pebblehost { pbVersion = "1.2.3" }`.
2. **Wiring chain:** extension property → plugin registration → task `@Input` — consistent with every other extension field.
3. **Resolution path:** `deploy()` resolves pb through `PbInstaller` before client construction; cache dir matches design (`gradleUserHome/caches/pebblehost-deploy/pb`).
4. **Backward compat:** explicit `pbBinary` paths (functional tests) short-circuit at installer tier 1 — no network, no behavior change for existing consumers.
5. **No concerns** — minimal diff, all checks pass.
