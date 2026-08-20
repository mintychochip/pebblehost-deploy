# pebblehost-cli `file push` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `pb file push <local> --server <id> --directory <dir>` subcommand to `pebblehost-cli` that uploads a local file to a PebbleHost server via the Pterodactyl two-step upload (GET signed upload URL, then multipart POST), so the Gradle deploy plugin can push jars.

**Architecture:** Extend the existing `Api` client with a method that (1) `GET /api/client/servers/{server}/files/upload?directory=…` and parses `attributes.url` from the JSON, then (2) `POST` a multipart `files[]` body to that returned URL using a fresh reqwest client (the upload hop is unauthenticated and external). Wire it as a new `FilePush` subcommand under the existing `file` command.

**Tech Stack:** Rust (edition 2021), clap 4.5 derive, reqwest 0.12 (add `multipart` feature), tokio, serde_json, wiremock 0.6 for tests. Follows the repo's existing single-file `src/main.rs` + wiremock test style.

## Global Constraints

- Repo: `github.com/mintychochip/pebblehost-cli` (cloned reference at `/home/jlo/dev/pebblehost-cli-ref`, latest `v2026.8.18.5`, commit `4f9ec43`).
- Work on a branch off `master`; open a PR against `master` (the repo's CI runs lint/test on PRs to master).
- Keep the existing `Api`/`Response`/`execute` structure; add a `FilePush` variant to the `Command` enum and a `FilePushArgs` struct.
- `reqwest` needs the `multipart` feature enabled in `Cargo.toml`.
- The upload hop must NOT reuse the bearer-token client (the signed URL is external and unauthenticated); use a separate bare reqwest client.
- Auth/base-url/env handling stays as-is (`PEBBLEHOST_API_TOKEN`, `--base-url`).
- Tests follow the repo's existing `wiremock` style (see `#[cfg(test)] mod tests` in `src/main.rs`).
- Run `cargo fmt --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test --all-features` before committing (repo's pre-flight).

---
## File Structure

- Modify: `Cargo.toml` — add `multipart` feature to reqwest.
- Modify: `src/main.rs` — add `FilePush` command, `FilePushArgs`, `Api::push_file`, `execute` arm, and wiremock tests.

---

### Task 1: Add `file push` command + upload implementation

**Files:**
- Modify: `Cargo.toml`
- Modify: `src/main.rs`
- Test: `src/main.rs` (`#[cfg(test)]` module)

**Interfaces:**
- Consumes: existing `Api` struct (`client`, `base_url`, `token`), `Response` enum, `Command` enum, `execute`/`run` functions.
- Produces: `Command::File(FileCommand)`, `FileCommand { subcommand: FileSubcommand }`, `FileSubcommand::{Contents(FileArgs), Push(FilePushArgs)}`, `Api::push_file(server_id, local, directory) -> Result<Response, CliError>`.
- **Breaking change (intentional):** `pb file <server> <path>` becomes `pb file contents <server> <path>`; `pb file push <local> --server <id> --directory <dir>` is the new upload command. The existing `Command::File(FileArgs)` leaf is replaced by the nested group.

- [ ] **Step 1: Write the failing test**

Add to the `#[cfg(test)] mod tests` block in `src/main.rs`:

```rust
#[tokio::test]
async fn file_push_fetches_upload_url_then_posts_multipart() {
    let server = MockServer::start().await;
    // Step 1: the API returns a signed upload URL pointing at the mock server.
    let upload_url = format!("{}/upload-target", server.uri());
    Mock::given(method("GET"))
        .and(path("/api/client/servers/srv-1/files/upload"))
        .and(query_param("directory", "plugins"))
        .and(header("Authorization", "Bearer secret"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "attributes": { "url": upload_url }
        })))
        .mount(&server)
        .await;
    // Step 2: the multipart POST to the signed URL.
    Mock::given(method("POST"))
        .and(path("/upload-target"))
        .respond_with(ResponseTemplate::new(204))
        .mount(&server)
        .await;

    let api = test_api(&server, "secret");
    // Write a temp file to upload.
    let dir = std::env::temp_dir();
    let local = dir.join("pb-test-upload.jar");
    std::fs::write(&local, b"fake jar bytes").unwrap();
    let resp = api
        .push_file("srv-1", local.to_str().unwrap().to_string(), "plugins".to_string())
        .await
        .unwrap();
    assert_eq!(resp, Response::Json(Value::Null));
    std::fs::remove_file(&local).ok();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cargo test file_push -- --nocapture`
Expected: FAIL — `push_file` does not exist (compile error).

- [ ] **Step 3: Add the `multipart` feature**

In `Cargo.toml`, change the reqwest dependency:

```toml
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls", "multipart"] }
```

- [ ] **Step 4: Write the implementation**

Add to `src/main.rs`:

1. Replace the `File(FileArgs)` variant in `enum Command` with a nested group:
```rust
    File(FileCommand),
```

2. Add the nested command structs (after `FileArgs`):
```rust
#[derive(Subcommand, Debug)]
enum FileSubcommand {
    /// Print the contents of a remote file.
    Contents(FileArgs),
    /// Upload a local file to the server.
    Push(FilePushArgs),
}

#[derive(Args, Debug)]
struct FileCommand {
    #[command(subcommand)]
    subcommand: FileSubcommand,
}

#[derive(Args, Debug)]
struct FilePushArgs {
    server_id: String,
    /// Local path of the file to upload.
    local: String,
    /// Remote directory to upload into (e.g. "plugins" or "/").
    #[arg(long, default_value = "/")]
    directory: String,
}
```

3. New method on `Api` (after `request`):
```rust
    async fn push_file(&self, server_id: &str, local: &str, directory: &str) -> Result<Response, CliError> {
        // Step 1: fetch the signed upload URL.
        let url_resp = self
            .request(
                Method::GET,
                &path_server(server_id, "/files/upload"),
                &[("directory", directory.to_owned())],
                None,
            )
            .await?;
        let upload_url = match url_resp {
            Response::Json(value) => value
                .get("attributes")
                .and_then(|a| a.get("url"))
                .and_then(|u| u.as_str())
                .ok_or_else(|| CliError::Input("upload response missing attributes.url".to_string()))?
                .to_owned(),
            _ => return Err(CliError::Input("upload response was not JSON".to_string())),
        };

        // Step 2: multipart POST the file to the signed URL (unauthenticated hop).
        let bytes = tokio::fs::read(local).await?;
        let part = reqwest::multipart::Part::bytes(bytes)
            .file_name(std::path::Path::new(local)
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("upload")
                .to_owned());
        let form = reqwest::multipart::Form::new().part("files[]", part);
        let upload_client = Client::builder()
            .timeout(Duration::from_secs(120))
            .build()
            .expect("upload client should build");
        let response = upload_client.post(&upload_url).multipart(form).send().await?;
        let status = response.status();
        if !status.is_success() {
            return Err(CliError::Api {
                status,
                message: format!("upload to {} failed", upload_url),
            });
        }
        Ok(Response::Json(Value::Null))
    }
```

4. Replace the `Command::File(a)` execute arm with the nested dispatch:
```rust
        Command::File(cmd) => match cmd.subcommand {
            FileSubcommand::Contents(a) => {
                api.request(
                    Method::GET,
                    &path_server(&a.server_id, "/files/contents"),
                    &[("file", a.path)],
                    None,
                )
                .await
            }
            FileSubcommand::Push(a) => {
                api.push_file(&a.server_id, &a.local, &a.directory).await
            }
        },
```

5. Update the existing `raw_text_success_body_is_preserved` test to use the new `contents` form:
```rust
    // Command::File(FileCommand { subcommand: FileSubcommand::Contents(FileArgs { server_id: "srv-1".into(), path: "server.properties".into() }) })
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cargo test file_push -- --nocapture`
Expected: PASS — the test verifies both the upload-URL fetch (bearer auth, `directory` query) and the multipart POST to the signed URL.

- [ ] **Step 6: Run the full pre-flight**

Run: `cargo fmt --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test --all-features`
Expected: all pass. Note: the existing `raw_text_success_body_is_preserved` test must be updated for the nested `contents` form (step 4.5).

- [ ] **Step 7: Commit on a branch**

```bash
cd /home/jlo/dev/pebblehost-cli-ref
git checkout -b feat/file-push
git add Cargo.toml src/main.rs
git commit -m "feat: add pb file push and nest file under a group"
```

---

### Task 2: Update README + release via repo workflow

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add `file push` to the README usage**

Add after the `api-call` example:

```markdown
Upload a local file to a server:

```bash
pb file push ./build/libs/myplugin.jar --server SERVER_ID --directory plugins
```
```

- [ ] **Step 2: Commit**

```bash
cd /home/jlo/dev/pebblehost-cli-ref
git add README.md
git commit -m "docs: document pb file push"
```

- [ ] **Step 3: Push branch and open PR**

```bash
git push -u origin feat/file-push
```

Open a PR against `master` (the repo's `lint.yml` runs on PRs to master). After
merge, trigger the `release.yml` workflow (workflow_dispatch) to cut a release
so `pb update` picks up `file push`. Verify the released binary has the command:
`pb file push --help` shows the new subcommand.
