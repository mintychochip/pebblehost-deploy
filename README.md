# pebblehost-deploy

[![Build](https://github.com/mintychochip/pebblehost-deploy/actions/workflows/build.yml/badge.svg)](https://github.com/mintychochip/pebblehost-deploy/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/github/license/mintychochip/pebblehost-deploy)](LICENSE)

A Gradle plugin that deploys a built Minecraft plugin/mod jar to PebbleHost
servers via the [pebblehost-cli](https://github.com/mintychochip/pebblehost-cli)
`pb` binary. Supports manual (`deployPebbleHost`) and CI (reusable
`deploy.yml` workflow) deployment, with flat or grouped canary→prod rollout.

## Repository layout

```
pebblehost-deploy/
├── plugin/          # included build — Gradle deploy plugin (`dev.pebblehost.deploy`)
├── test-plugin/     # `:test-plugin` — sample Paper plugin consumer
├── gradlew          # root wrapper — run all builds from here
└── settings.gradle.kts
```

The deploy plugin lives in `plugin/` as an included build. `test-plugin`
resolves `dev.pebblehost.deploy` from that live source via
`pluginManagement { includeBuild("plugin") }`, so a single `./gradlew build`
always exercises the current plugin code.

Build the plugin and run its tests:

```bash
./gradlew :plugin:build
```

Build everything:

```bash
./gradlew build
```

## Prerequisites

- Java 17+ and the Gradle wrapper (included).
- The `pb` binary (see pebblehost-cli install instructions). Set `pbBinary` if
  it is not on `PATH`.
- A PebbleHost API token: `PEBBLEHOST_API_TOKEN` env var, `pebblehost.token`,
  or `pebblehostToken` in `gradle.properties`.
- The `pb` version must include `file push` (CLI extension).

## Usage

```kotlin
plugins {
    id("dev.pebblehost.deploy")
}

pebblehost {
    jar = file("build/libs/myplugin.jar")
    targetDir = "plugins"            // or "mods"
    strategy = "groups"              // "flat" | "groups"
    canaryGate = true
    continueAfterCanary = false
    restart = true                   // manual default: restart + verify
    verifyState = "running"
    verifyTimeoutMs = 180_000
    rollback = "abort"               // "abort" | "restore"
    pbBinary = "pb"                  // optional: path to the pb binary
    target("abc123")                 // group defaults to "default"
    target("def456")                 // add more servers as needed
}
```

Manual: `./gradlew :test-plugin:deployPebbleHost`

CI: run the reusable `deploy.yml` workflow (workflow_dispatch). It builds the
jar, installs `pb`, and runs the same task with `PEBBLEHOST_API_TOKEN` from
secrets.

## Rollout

- `flat`: all targets deploy in parallel.
- `groups`: targets grouped by `group` deploy in parallel within a group;
  groups run in order. With `canaryGate=true` (default), only the first group
  deploys until you re-run with `--continue-after-canary`.

To assign a target to a group, configure the `Target` directly:

```kotlin
pebblehost {
    targets.add(objects.newInstance(dev.pebblehost.deploy.Target::class.java).apply {
        serverId.set("abc123")
        group.set("canary")
    })
}
```

## Restart & verification

Replacing a plugin jar does not hot-reload it — the new code only runs after a
server restart. Manual deploy restarts and verifies by default; CI controls it
via the `restart` input.

Verification is mechanical: the server reaching `verifyState` (default
`running`) does **not** prove the plugin loaded. Confirm plugin load via the
server console/log after deploy.

## Rollback

Default `rollback=abort`: on failure the rollout stops, the new jar is left in
place, and the build reports per-server status. Set `rollback=restore` to have
the plugin restore the versioned backup, restart, and verify automatically.
