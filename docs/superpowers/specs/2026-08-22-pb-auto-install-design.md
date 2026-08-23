# pb auto-install from remote releases — design

Date: 2026-08-22
Status: approved (design discussion 2026-08-22)

## Problem

Consumers of `dev.mintychochip.pebblehost.deploy` must have the `pb` CLI on
PATH (or pass an explicit `pbBinary`). That is friction for the simple case
and forces CI to build `pebblehost-cli` from source (see `deploy.yml`,
currently pinned to branch `feat/file-push-restored` + `cargo build`).
Upstream publishes ready-made release binaries; the plugin should install
`pb` automatically when no usable binary is available.

## Decisions (locked)

- **Scope**: plugin-level resolution only. `deploy.yml` is unchanged in this
  feature (its PATH-installed `pb` hits resolution tier 2 below).
- **Version policy**: `pbVersion` defaults to `"latest"`; consumers may pin an
  exact version (`"2026.8.21.16"` or `"v2026.8.21.16"`).
- **Integrity**: downloads are verified against the sha256 digest GitHub
  publishes on each release asset (`assets[].digest`, observed on release
  `v2026.8.21.16`).

## Upstream contract (verified 2026-08-22)

- Repo: `mintychochip/pebblehost-cli`. Latest tag shape: `v<YYYY.M.D>.<run>`
  (e.g. `v2026.8.21.16`); asset names embed the version **without** the `v`.
- Assets: `pebblehost-cli-<ver>-<rust-target>.tar.gz`, each containing exactly
  one member: `pb` (`pb.exe` on windows target).
- Targets: `x86_64-unknown-linux-gnu`, `aarch64-unknown-linux-gnu`,
  `armv7-unknown-linux-gnueabihf`, `x86_64-apple-darwin`,
  `aarch64-apple-darwin`, `x86_64-pc-windows-msvc`.
- Metadata endpoints: `GET /repos/mintychochip/pebblehost-cli/releases/latest`
  and `/releases/tags/<v>` return `tag_name` plus assets with
  `name`, `digest` (`sha256:…`), `browser_download_url`.

## Architecture

New class `PbInstaller` in `dev.mintychochip.pebblehost.deploy`.
`DeployPebbleHostTask.deploy()` calls it before constructing
`PebbleHostClient`; `validateBinary()` (`pb --version`) remains the final
sanity check regardless of where the binary came from.

Resolution order:

1. **Explicit path**: `pbBinary` set to anything other than the default
   `"pb"` → must exist as a file, else hard error (the user asked for that
   exact binary; never silently substitute).
2. **PATH**: default `"pb"` found on `PATH` (scan `PATH` entries for `pb`,
   `pb.exe` on Windows) → used as-is.
3. **Auto-install**: resolve version, check cache, otherwise download,
   verify, extract, chmod.

### Version resolution

- `pbVersion = "latest"` → `releases/latest`; pinned value → normalized to
  `v…` tag form and fetched via `releases/tags/<tag>` so digests are always
  available. `latest` resolves the current tag with one API call per deploy
  run; downloads only happen on cache miss. Pinned versions are fully
  offline once cached.
- If env `GITHUB_TOKEN` set, sent as `Authorization: Bearer` to avoid
  shared-CI-runner rate limits (unauthenticated limit is 60/h/IP).

### Cache

- Location: `$GRADLE_USER_HOME/caches/pebblehost-deploy/pb/<tag>/pb[.exe]`
  (plus `<tag>/release.json` metadata kept alongside).
- Cache hit ⇒ zero network. Downloads land in a temp file inside the same
  directory and are atomically moved into place so concurrent builds cannot
  observe a partial binary.

### Download & extract

- `java.net.http.HttpClient` with redirects enabled streams the asset;
  SHA-256 computed while streaming and compared to the API digest before
  anything is installed. Mismatch ⇒ hard failure, nothing cached.
- Extraction shells out to system `tar -xzf <asset> -C <dir>` (bsdtar ships
  with Windows 10+, GNU tar on linux/mac) — consistent with the plugin's
  subprocess style; no new dependencies. Resulting `pb`/`pb.exe` located,
  marked executable on non-Windows.

### Platform mapping

| os.name contains | os.arch       | rust target                   |
|------------------|---------------|-------------------------------|
| linux            | amd64/x86_64  | x86_64-unknown-linux-gnu      |
| linux            | aarch64/arm64 | aarch64-unknown-linux-gnu     |
| linux            | arm (32-bit)  | armv7-unknown-linux-gnueabihf |
| mac/darwin       | x86_64/amd64  | x86_64-apple-darwin           |
| mac/darwin       | aarch64/arm64 | aarch64-apple-darwin          |
| windows          | any           | x86_64-pc-windows-msvc        |

Anything else ⇒ hard error listing supported platforms.

## Errors

All failures raise `GradleException` with actionable text:
unsupported platform (with supported list); HTTP failure incl. 403 rate-limit
hint suggesting `GITHUB_TOKEN`; asset missing for platform/tag; digest
mismatch; tar extraction failure. No silent fallbacks between tiers beyond
the defined order above.

## DSL surface

```kotlin
pebblehost {
    // existing
    pbBinary = "pb"          // unchanged; explicit non-default paths must exist
    // new
    pbVersion = "latest"     // convention; or e.g. "2026.8.21.16"
}
```

Task property `pbVersion` wired like its siblings; no new CLI options.

## Testing

- `PbInstallerTest` (offline unit): platform-mapping table; tier precedence
  (explicit-existing wins, explicit-missing errors, PATH hit short-circuits);
  cache-hit performs no network; full install flow against a local
  `com.sun.net.httpserver` stub serving canned metadata JSON + a real
  `.tar.gz` produced by system `tar` in `@TempDir`; digest mismatch rejected;
  unsupported platform error.
- Existing functional tests keep passing explicit fake-pb paths — unaffected.
- README: auto-install behavior, `pbVersion`, cache location, token/rate-limit
  notes.

## Erratum (2026-08-22, post-merge)

- ~~Changing `deploy.yml` is a non-goal~~ — superseded by the approved
  "In the plugin" option whose stated outcome was that `deploy.yml` drops its
  cargo-build steps. The workflow now installs `pb` from the latest release
  asset (linux x86_64) via `gh api` + `curl | tar`, keeping the
  `pb file --help` verification, and runs on JVM 25 to match plugin bytecode.

## Non-goals

- Changing `deploy.yml` (explicit scope decision).
- Publishing checksum sidecar files upstream (API digests suffice).
- Auto-updating an explicit `pbBinary` path or managing PATH installs.
