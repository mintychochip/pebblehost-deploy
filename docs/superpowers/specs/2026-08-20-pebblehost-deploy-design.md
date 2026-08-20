# PebbleHost Deploy — Gradle Plugin Design

Date: 2026-08-20
Status: Design (pending implementation plan)
Related: [mintychochip/pebblehost-cli](https://github.com/mintychochip/pebblehost-cli)

## Problem

Developers building Minecraft plugin/mod jars against PebbleHost servers have no
first-party deploy path. Releasing means uploading a jar, handling restart, and
— for multi-server setups — coordinating rollout across a fleet. Manual deploys
and CI deploys are both needed, but today there is no shared mechanism.

## Goal

A Gradle plugin that deploys a locally-built (or otherwise supplied) jar to one
or more PebbleHost servers via the PebbleHost client API, driven either by a
manual Gradle task or a reusable CI workflow, with optional grouped/progressive
rollout across a server fleet.

The plugin must use `pebblehost-cli` (`pb`) as its interface to the PebbleHost
API.

## Decisions (locked)

- **Restart default**: manual `deployPebbleHost` task restarts the server after
  upload and verifies it returns online. CI workflow exposes a `deploy-restart`
  input (default `true`, overridable).
- **Rollback default**: `abort` — stop on first verification failure, leave the
  new jar in place, report per-server. This honors the "audit/fix after the
  fact, possibly via a central controller" intent. Configurable to
  `rollback = "restore"` for plugin-owned auto-restore.
- **Upload path**: extend `pebblehost-cli` with `pb file push`; the plugin shells
  out to `pb` for every remote operation.

Rationale for `abort` default over `restore`: the user expressed that a failed
rollout may be better fixed post-hoc (and could later roll back from a central
controller), rather than the plugin auto-overwriting remote state. `restore` is
deliberately opt-in.

## Non-goals (v1)

- Publishing the Gradle plugin to a plugin portal / maven repo. Project-local
  plugin (applied from `buildSrc` or a locally-included build).
- A web/coordinator control plane. Rollout coordination lives in the
  plugin/CLI layer.
- Modpack/plugin *search* and *installation* from providers (Modrinth etc.).
  We only deploy jars we already have.
- Generic "arbitrary file set" deploys beyond the jar + convenience files.
- Database backup/restore.

## Context: PebbleHost & the CLI

- PebbleHost is a [Pterodactyl](https://pterodactyl.io)-based panel. Client API
  is documented at `https://api.pebblehost.com/api.yaml`.
- `pebblehost-cli` ships the `pb` binary. Relevant existing capabilities:
  - `pb servers`, `pb server <id>`, `pb power <id> --action`, `pb resources <id>`,
    `pb files <id>`, `pb filesearch`, `pb file <id> <path>`, `pb backups <id>`,
    `pb api-call <METHOD> <PATH> --query ... --body ...`.
  - Auth: `PEBBLEHOST_API_TOKEN` (or `--token`); base URL via `--base-url`
    (default `https://panel.pebblehost.com`).

### Verified gap: the CLI cannot upload

Checked the latest source (`v2026.8.18.5`, commit `4f9ec43`):

- `Files`/`File` subcommands only **GET** (list / search / contents).
- `operations.json` documents `getFileUploadUrl`
  (`GET /api/client/servers/{server}/files/upload`) but `main.rs` never calls it.
- `pb api-call` always serializes the body as JSON (`req.json(&body)`), so it
  cannot send a raw multipart upload.

Pterodactyl's upload is **two-step**:

1. `GET /api/client/servers/{server}/files/upload?directory=…` → returns a
   signed upload URL (JSON `attributes.url`).
2. `POST` multipart `files[]=<jar>` to that returned URL (the hop itself is an
   external, unauthenticated URL).

**Therefore the CLI must be extended** with a real `pb file push` subcommand
before the deploy plugin can work. `reqwest` is already a dependency; it needs
the `multipart` feature enabled (and the code to do the 2-step upload).

## Architecture

Two artifacts, one flow:

1. **`pebblehost-cli` extension** — add `pb file push` (the missing primitive).
2. **Gradle deploy plugin** (this repo, `pebblehost-deploy`) — shells out to
   `pb` for every remote operation: upload, backup/copy, power, verify, rollback.

### Why shell out to `pb` instead of embedding HTTP in the plugin

- Single, tested auth + endpoint layer (one place owns token/base-url).
- Matches the stated intent: "use `github.com/mintychochip/pebblehost-cli`."
- The plugin stays thin and testable with an injected fake `pb`.

Trade-off: the plugin depends on a `pb` binary on PATH. Documented as a
prerequisite; the deploy task validates it and fails with a clear message.

## Deployment flow (per server)

For each target server, in roll-out order:

1. **Backup** existing remote jar to a **unique, versioned** backup name, e.g.
   `jars/<name>-deploy-<yyyyMMddHHmmss>.jar.bak` (only if the jar exists). Never
   overwrite a shared `…jar.bak`: a persistent `.bak` can silently capture the
   *prior* deploy's artifact and become the wrong thing to restore.
2. **Upload** new jar to target dir (e.g. `/plugins`) via `pb file push`.
3. **Restart** (policy-dependent, see below).
4. **Verify** (see Verification semantics below).
5. **Disposition** on failure: default `abort` (+ report); optional
   `rollback = "restore"` for auto-restore.

### Restart policy

Replacing a plugin jar does **not** hot-reload it; the new code only runs after
a server restart. Inherent to Minecraft plugin deployment.

- **Manual `deploy` task**: restart default **on** (deploy implies running the
  new code), then verify online.
- **CI workflow**: restart is a workflow input (`deploy-restart`, default
  true), overridable. A workflow that only wants to stage jars on each server
  can set it false.
- Optional per-server override `restart=false` for hosts that must not bounce.

### Verification semantics

`pb resources <id>` returns server state (e.g. `current_state`). **Running does
NOT prove the new plugin loaded.** We therefore treat a deploy as *mechanically*
successful when the server reaches the desired state after restart, and report
that as such (not as "plugin loaded"). Options to strengthen later (v2): tail
the server log for a plugin-load line, or an in-game/probe check. v1 states the
limitation explicitly in output.

- Poll `pb resources <id>` until `current_state == verifyState` (default
  `running`) within `verifyTimeoutMs`, else verification failure.
- When `restart=false`, verification is skipped for that server (nothing was
  triggered); the build records it as staged-not-verified.

### Disposition / rollback policy

**Default: `rollback = "abort"`.** On a step failure (upload error or server
does not return to the desired state), stop the rollout at the failed group,
leave the new jar in place, and fail the build with a per-server report
(succeeded / failed / reason / backup name). No automatic remote mutation beyond
what already happened. This matches the "fix after the fact, possibly via a
central controller" intent.

**Opt-in: `rollback = "restore"`.** A real rollback, not "upload a backup":

1. Restore the **versioned** backup: rename/copy
   `…<timestamp>.jar.bak` back to `jars/<name>.jar` (overwriting the bad jar).
2. **Restart again** so the old plugin is the one actually loaded.
3. Verify the server returns to `verifyState`.
4. Report: restored backup name, server, reason.

Validation: the plugin refuses to run `restore` if it cannot find a backup for
the deployed server (fail, don't guess). Backups are not GC'd by the plugin in
v1 (documented).

## Targeting & rollout

Both flat lists and grouped progressive rollout are supported:

- **`targets`**: list of `{ serverId, jarDir?, restart?, group? }`.
- **Flat**: all targets in one implicit group → deploy in parallel.
- **Groups**: targets with the same `group` deploy in parallel within the group;
  rollout advances **in group order** with a **canary gate** default: stop after
  the first group unless `continueAfterCanary = true` (or a canary group is
  explicitly named). This gives: flat fleets (single group) and canary→prod
  staging (groups `canary`, `prod`…).

Failure within a group (any step, including verification) aborts before
advancing to the next group.

## Configuration surface (Gradle)

Plugin exposes a `pebblehost` extension:

```
pebblehost {
  token = providers.environmentVariable("PEBBLEHOST_API_TOKEN")  // fallback gradle.properties
  baseUrl = "https://panel.pebblehost.com"
  jar = file("build/libs/foo.jar")            // artifact to deploy
  targetDir = "plugins"                       // remote dir (also 'mods')
  strategy = "groups"                          // 'flat' | 'groups'
  canaryGate = true
  continueAfterCanary = false
  restart = true                               // default manual policy
  verifyState = "running"
  verifyTimeoutMs = 180_000
  rollback = "abort"                            // 'abort' | 'restore'
  targets = [
    { serverId = "abc123", group = "canary" },
    { serverId = "def456", group = "prod" },
  ]
}
```

Task: `deployPebbleHost`.

### Invocation

- **Manual**: `./gradlew deployPebbleHost`.
- **CI (workflow)**: reusable `deploy.yml` builds the jar, then runs
  `deployPebbleHost`, passing `--deploy-restart`, `--deploy-rollback`, etc. and a
  GitHub Actions secret `PEBBLEHOST_API_TOKEN`. Server targets live in
  `gradle.properties`/the plugin config so secrets stay out of the repo.

## Components (plugin)

- `PebbleHostPlugin` — registers extension + `deployPebbleHost` task.
- `PebbleHostClient` — thin wrapper invoking `pb` (validate binary, run
  subcommands, parse JSON output). Injectable fake for tests.
- `UploadOperation` / `BackupOperation` / `PowerOperation` / `VerifyOperation` /
  `RollbackOperation` — one focused unit each, driven by a rollout orchestrator.
- `RolloutPlanner` — resolves targets into ordered groups; applies canary gate;
  yields per-manifest.
- `PebbleHostConfig` — typed config model; validates targets/strategy/rollback.

## Error handling

- Missing/empty token → clear error suggesting `PEBBLEHOST_API_TOKEN`.
- `pb` not on PATH / non-zero exit / non-JSON output → descriptive failure naming
  the server and step.
- Timeout during verify → treated as verification failure → disposition path.
- A server that was never online and cannot be verified → surfaced in the report
  and blocks group advancement.
- `restore` rollback with no findable backup → fail with explicit message; never
  guess/overwrite.

## Testing

- **Unit** (JVM, TestKit or plain JUnit): RolloutPlanner group ordering + canary
  gate; config validation (bad strategy/rollback/target).
- **Client tests**: with a fake `pb` executable (script) asserting the
  subcommand invocation and JSON-parsing; and against a captured "pb transcript".
- **CLI extension tests**: extend existing wiremock suite in pebblehost-cli for
  `file push` (2-step upload: URL fetch then multipart POST) — follows the repo's
  existing test style.
- Manual smoke: point `--base-url` at a test/fake panel; exercise
  backup→upload→restart→verify.

## Deliverables checklist

- [ ] `pebblehost-cli`: add `file push` subcommand + multipart feature + tests +
      release.
- [ ] Gradle plugin: extension + task + rollout planner + operations.
- [ ] Unit + client tests passing.
- [ ] `deploy.yml` reusable workflow.
- [ ] README documenting manual + CI usage, prerequisites, restart/rollback,
      and the "running ≠ plugin loaded" verification limitation.