# PebbleHost Deploy Gradle Plugin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A project-local Gradle plugin (`dev.pebblehost.deploy`) that deploys a built Minecraft jar to one or more PebbleHost servers by shelling out to the `pb` CLI, with manual (`deployPebbleHost`) and CI (reusable `deploy.yml` workflow) invocation, and flat or grouped canary→prod rollout.

**Architecture:** The plugin shells out to the `pb` binary for every remote operation (backup/rename, upload, power, verify). A thin `PebbleHostClient` wraps `pb` subcommands and parses JSON output; `RolloutPlanner` resolves targets into ordered groups with a canary gate; `RolloutOrchestrator` drives per-server deploy/verify/rollback. The CLI must first be extended with `pb file push` (separate plan: `2026-08-20-pebblehost-cli-file-push.md`).

**Tech Stack:** Java 17 (compiled with running JDK via `--release 17`), Gradle 9.7.1 wrapper, `java-gradle-plugin`, Gson 2.11 for JSON, JUnit 5 + Gradle TestKit for tests.

## Global Constraints

- Java: source/target 17 (`--release 17`), no toolchain auto-provisioning.
- Gradle: 9.7.1 wrapper (current as of 2026-08-20; verified reachable at `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip`). If the daemon fails to start on the installed JDK 25, set `org.gradle.java.home` to a JDK 21 in `gradle.properties` (fallback only).
- Plugin id: `dev.pebblehost.deploy`. Task name: `deployPebbleHost`. Extension name: `pebblehost`.
- Config defaults (from spec): `strategy=groups`, `canaryGate=true`, `continueAfterCanary=false`, `restart=true`, `verifyState=running`, `verifyTimeoutMs=180000`, `rollback=abort`, `targetDir=plugins`, `baseUrl=https://panel.pebblehost.com`.
- Rollback default is `abort` (leave new jar, report). `restore` is opt-in and must refuse to run when no backup exists.
- Secrets: token resolved from extension `token` → env `PEBBLEHOST_API_TOKEN` → gradle.properties `pebblehostToken`. Passed to child processes via env var, never as a CLI arg.
- No publishing, no Kotlin, no extra Gradle plugins beyond `java-gradle-plugin`.
- `pb` binary is a prerequisite; task validates it and fails with a clear message.
- Verification is mechanical only: server reaches `verifyState`; output must state "running ≠ plugin loaded".

---
## File Structure

- `settings.gradle.kts` — rootProject name.
- `build.gradle.kts` — java-gradle-plugin, Gson, JUnit, TestKit.
- `gradle.properties` — (optional) `org.gradle.java.home` fallback note only.
- `src/main/java/dev/pebblehost/deploy/PebbleHostPlugin.java` — registers extension + task.
- `src/main/java/dev/pebblehost/deploy/PebbleHostExtension.java` — typed config with defaults.
- `src/main/java/dev/pebblehost/deploy/Target.java` — one server target (serverId, group, restart).
- `src/main/java/dev/pebblehost/deploy/DeployConfig.java` — immutable resolved config record.
- `src/main/java/dev/pebblehost/deploy/CommandRunner.java` — interface for running a command.
- `src/main/java/dev/pebblehost/deploy/ProcessCommandRunner.java` — real ProcessBuilder impl.
- `src/main/java/dev/pebblehost/deploy/PebbleHostClient.java` — wraps `pb` subcommands, parses JSON.
- `src/main/java/dev/pebblehost/deploy/RolloutPlanner.java` — groups + canary gate.
- `src/main/java/dev/pebblehost/deploy/RolloutOrchestrator.java` — drives groups/servers, report, rollback.
- `src/main/java/dev/pebblehost/deploy/ServerResult.java` — per-server outcome record.
- `src/main/java/dev/pebblehost/deploy/operations/BackupOperation.java`
- `src/main/java/dev/pebblehost/deploy/operations/UploadOperation.java`
- `src/main/java/dev/pebblehost/deploy/operations/PowerOperation.java`
- `src/main/java/dev/pebblehost/deploy/operations/VerifyOperation.java`
- `src/main/java/dev/pebblehost/deploy/operations/RollbackOperation.java`
- `src/main/java/dev/pebblehost/deploy/DeployPebbleHostTask.java` — task with `@Option`s.
- `src/test/java/dev/pebblehost/deploy/...` — JUnit + TestKit tests (mirror main packages).
- `.github/workflows/deploy.yml` — reusable CI workflow.
- `README.md` — usage, prerequisites, restart/rollback, verification limitation.

---

### Task 1: Scaffold Gradle plugin project + wrapper

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `.gitignore`
- Create: `gradle/wrapper/*` + `gradlew` (via `gradle wrapper`)

- [ ] **Step 1: Bootstrap the Gradle wrapper**

```bash
cd /home/jlo/dev/pebblehost-deploy
curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-9.7.1-bin.zip
unzip -q /tmp/gradle.zip -d /tmp
/tmp/gradle-9.7.1/bin/gradle wrapper --gradle-version 9.7.1
```

Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` (distributionUrl pointing at `gradle-9.7.1-bin.zip`).

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "pebblehost-deploy"
```

- [ ] **Step 3: Write `build.gradle.kts`**

```kotlin
plugins {
    `java-gradle-plugin`
}

group = "dev.pebblehost"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("pebblehostDeploy") {
            id = "dev.pebblehost.deploy"
            implementationClass = "dev.pebblehost.deploy.PebbleHostPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Write `.gitignore`**

```
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 5: Verify the scaffold builds**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (no source yet; plugin class not present yet is fine — `java-gradle-plugin` only validates at apply time).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts .gitignore gradlew gradlew.bat gradle/wrapper
git commit -m "chore: scaffold Gradle plugin project with wrapper"
```

---

### Task 2: Config model — extension, Target, DeployConfig, validation

**Files:**
- Create: `src/main/java/dev/pebblehost/deploy/PebbleHostExtension.java`
- Create: `src/main/java/dev/pebblehost/deploy/Target.java`
- Create: `src/main/java/dev/pebblehost/deploy/DeployConfig.java`
- Test: `src/test/java/dev/pebblehost/deploy/DeployConfigTest.java`

**Interfaces:**
- Produces: `PebbleHostExtension` (getters: `token`, `baseUrl`, `jar` RegularFileProperty, `targetDir`, `strategy`, `canaryGate`, `continueAfterCanary`, `restart`, `verifyState`, `verifyTimeoutMs`, `rollback`, `targets` ListProperty<Target>). `Target` (getters `serverId`, `group` default `"default"`, `restart` default `true`). `DeployConfig` record `(String token, String baseUrl, File jar, String targetDir, boolean restart, String verifyState, long verifyTimeoutMs, String rollback)` with static `from(...)` that validates.

- [ ] **Step 1: Write the failing test**

```java
package dev.pebblehost.deploy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeployConfigTest {
    @Test
    void validatesStrategyRollbackAndTargets() {
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", new java.io.File("a.jar"), "plugins", true, "running", 1000L, "bogus"));
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", new java.io.File("a.jar"), "plugins", true, "running", 1000L, "abort"));
    }

    @Test
    void acceptsValidRollbackValues() {
        DeployConfig c = DeployConfig.from("t", "http://x", new java.io.File("a.jar"), "plugins", true, "running", 1000L, "restore");
        assertEquals("restore", c.rollback());
    }

    @Test
    void rejectsMissingJar() {
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", new java.io.File("/no/such.jar"), "plugins", true, "running", 1000L, "abort"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests dev.pebblehost.deploy.DeployConfigTest`
Expected: FAIL — `DeployConfig` does not exist (compile error).

- [ ] **Step 3: Write the implementation**

`Target.java`:
```java
package dev.pebblehost.deploy;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public class Target {
    private final Property<String> serverId;
    private final Property<String> group;
    private final Property<Boolean> restart;

    @Inject
    public Target(ObjectFactory objects) {
        this.serverId = objects.property(String.class);
        this.group = objects.property(String.class).convention("default");
        this.restart = objects.property(Boolean.class).convention(true);
    }

    public Property<String> getServerId() { return serverId; }
    public Property<String> getGroup() { return group; }
    public Property<Boolean> getRestart() { return restart; }
}
```

`PebbleHostExtension.java`:
```java
package dev.pebblehost.deploy;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public abstract class PebbleHostExtension {
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
    private final ListProperty<Target> targets;

    @Inject
    public PebbleHostExtension(ObjectFactory objects) {
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
    public ListProperty<Target> getTargets() { return targets; }
}
```

`DeployConfig.java`:
```java
package dev.pebblehost.deploy;

import java.io.File;

public record DeployConfig(
        String token, String baseUrl, File jar, String targetDir,
        boolean restart, String verifyState, long verifyTimeoutMs, String rollback) {

    public static DeployConfig from(String token, String baseUrl, File jar, String targetDir,
                                    boolean restart, String verifyState, long verifyTimeoutMs, String rollback) {
        if (!rollback.equals("abort") && !rollback.equals("restore")) {
            throw new IllegalArgumentException("rollback must be 'abort' or 'restore', got: " + rollback);
        }
        if (!jar.exists()) {
            throw new IllegalArgumentException("jar does not exist: " + jar);
        }
        return new DeployConfig(token, baseUrl, jar, targetDir, restart, verifyState, verifyTimeoutMs, rollback);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests dev.pebblehost.deploy.DeployConfigTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pebblehost/deploy src/test/java/dev/pebblehost/deploy
git commit -m "feat: add pebblehost config model with validation"
```

---

### Task 3: CommandRunner + PebbleHostClient

**Files:**
- Create: `src/main/java/dev/pebblehost/deploy/CommandRunner.java`
- Create: `src/main/java/dev/pebblehost/deploy/ProcessCommandRunner.java`
- Create: `src/main/java/dev/pebblehost/deploy/PebbleHostClient.java`
- Test: `src/test/java/dev/pebblehost/deploy/PebbleHostClientTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `CommandRunner.run(List<String> command, Map<String,String> env, Duration timeout) throws IOException, InterruptedException` → stdout string, throws on non-zero exit/timeout. `PebbleHostClient(String pbBinary, String token, String baseUrl, CommandRunner runner)` with methods:
  - `void validateBinary()`
  - `List<String> listFiles(String serverId, String directory)`
  - `String currentState(String serverId)`
  - `void rename(String serverId, String from, String to)`
  - `void delete(String serverId, String path)`
  - `void push(String serverId, String localPath, String directory)`
  - `void power(String serverId, String action)`

- [ ] **Step 1: Write the failing tests**

```java
package dev.pebblehost.deploy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PebbleHostClientTest {
    static class FakeRunner implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        String output = "{}";
        @Override
        public String run(List<String> command, Map<String, String> env, Duration timeout) {
            calls.add(new ArrayList<>(command));
            return output;
        }
    }

    @Test
    void validateBinaryRunsVersion() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", "https://panel.pebblehost.com", r);
        c.validateBinary();
        assertEquals(List.of("pb", "--version"), r.calls.get(0));
    }

    @Test
    void currentStateParsesAttributes() {
        FakeRunner r = new FakeRunner();
        r.output = "{\"attributes\":{\"current_state\":\"running\"}}";
        PebbleHostClient c = new PebbleHostClient("pb", "tok", "https://panel.pebblehost.com", r);
        assertEquals("running", c.currentState("srv-1"));
        assertEquals(List.of("pb", "--base-url", "https://panel.pebblehost.com", "resources", "srv-1"), r.calls.get(0));
    }

    @Test
    void listFilesParsesNames() {
        FakeRunner r = new FakeRunner();
        r.output = "{\"data\":[{\"attributes\":{\"name\":\"a.jar\",\"is_file\":true}},{\"attributes\":{\"name\":\"b.jar\",\"is_file\":true}}]}";
        PebbleHostClient c = new PebbleHostClient("pb", null, null, r);
        assertEquals(List.of("a.jar", "b.jar"), c.listFiles("srv-1", "plugins"));
        assertEquals(List.of("pb", "files", "srv-1", "--directory", "plugins"), r.calls.get(0));
    }

    @Test
    void renameSendsApiCallPut() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        c.rename("srv-1", "plugins/a.jar", "plugins/a-deploy-1.bak");
        assertEquals(List.of("pb", "api-call", "PUT", "/api/client/servers/srv-1/files/rename",
            "--body", "{\"root\":\"/\",\"from\":\"plugins/a.jar\",\"to\":\"plugins/a-deploy-1.bak\"}"), r.calls.get(0));
    }

    @Test
    void pushCallsFilePush() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        c.push("srv-1", "/build/libs/a.jar", "plugins");
        assertEquals(List.of("pb", "file", "push", "/build/libs/a.jar", "--server", "srv-1", "--directory", "plugins"), r.calls.get(0));
    }

    @Test
    void powerSendsAction() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        c.power("srv-1", "restart");
        assertEquals(List.of("pb", "power", "srv-1", "--action", "restart"), r.calls.get(0));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests dev.pebblehost.deploy.PebbleHostClientTest`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write the implementation**

`CommandRunner.java`:
```java
package dev.pebblehost.deploy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface CommandRunner {
    String run(List<String> command, Map<String, String> env, Duration timeout) throws IOException, InterruptedException;
}
```

`ProcessCommandRunner.java`:
```java
package dev.pebblehost.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProcessCommandRunner implements CommandRunner {
    @Override
    public String run(List<String> command, Map<String, String> env, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("command timed out after " + timeout + ": " + command);
        }
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IOException("command failed (exit " + p.exitValue() + "): " + command
                + "\nstderr: " + stderr + "\nstdout: " + stdout);
        }
        return stdout;
    }
}
```

`PebbleHostClient.java`:
```java
package dev.pebblehost.deploy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PebbleHostClient {
    private final String pbBinary;
    private final String token;
    private final String baseUrl;
    private final CommandRunner runner;

    public PebbleHostClient(String pbBinary, String token, String baseUrl, CommandRunner runner) {
        this.pbBinary = pbBinary;
        this.token = token;
        this.baseUrl = baseUrl;
        this.runner = runner;
    }

    private Map<String, String> env() {
        return token == null || token.isBlank()
            ? Map.of()
            : Map.of("PEBBLEHOST_API_TOKEN", token);
    }

    private List<String> base() {
        List<String> cmd = new ArrayList<>();
        cmd.add(pbBinary);
        if (baseUrl != null && !baseUrl.isBlank()) {
            cmd.add("--base-url");
            cmd.add(baseUrl);
        }
        return cmd;
    }

    private String run(List<String> command, Duration timeout) {
        try {
            return runner.run(command, env(), timeout);
        } catch (Exception e) {
            throw new RuntimeException("pb command failed: " + command + " — " + e.getMessage(), e);
        }
    }

    public void validateBinary() {
        List<String> cmd = base();
        cmd.add("--version");
        run(cmd, Duration.ofSeconds(30));
    }

    public List<String> listFiles(String serverId, String directory) {
        List<String> cmd = base();
        cmd.addAll(List.of("files", serverId, "--directory", directory));
        String out = run(cmd, Duration.ofSeconds(60));
        JsonObject root = JsonParser.parseString(out).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        List<String> names = new ArrayList<>();
        for (JsonElement e : data) {
            JsonObject attrs = e.getAsJsonObject().getAsJsonObject("attributes");
            if (attrs.has("name")) names.add(attrs.get("name").getAsString());
        }
        return names;
    }

    public String currentState(String serverId) {
        List<String> cmd = base();
        cmd.addAll(List.of("resources", serverId));
        String out = run(cmd, Duration.ofSeconds(60));
        JsonObject root = JsonParser.parseString(out).getAsJsonObject();
        JsonObject attrs = root.getAsJsonObject("attributes");
        return attrs != null && attrs.has("current_state") ? attrs.get("current_state").getAsString() : "";
    }

    public void rename(String serverId, String from, String to) {
        List<String> cmd = base();
        cmd.addAll(List.of("api-call", "PUT", "/api/client/servers/" + serverId + "/files/rename",
            "--body", "{\"root\":\"/\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"));
        run(cmd, Duration.ofSeconds(60));
    }

    public void delete(String serverId, String path) {
        List<String> cmd = base();
        cmd.addAll(List.of("api-call", "POST", "/api/client/servers/" + serverId + "/files/delete",
            "--body", "{\"root\":\"/\",\"files\":[\"" + path + "\"]}"));
        run(cmd, Duration.ofSeconds(60));
    }

    public void push(String serverId, String localPath, String directory) {
        List<String> cmd = base();
        cmd.addAll(List.of("file", "push", localPath, "--server", serverId, "--directory", directory));
        run(cmd, Duration.ofSeconds(120));
    }

    public void power(String serverId, String action) {
        List<String> cmd = base();
        cmd.addAll(List.of("power", serverId, "--action", action));
        run(cmd, Duration.ofSeconds(60));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests dev.pebblehost.deploy.PebbleHostClientTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pebblehost/deploy src/test/java/dev/pebblehost/deploy
git commit -m "feat: add PebbleHostClient wrapping pb CLI"
```

---

### Task 4: RolloutPlanner — grouping + canary gate

**Files:**
- Create: `src/main/java/dev/pebblehost/deploy/RolloutPlanner.java`
- Test: `src/test/java/dev/pebblehost/deploy/RolloutPlannerTest.java`

**Interfaces:**
- Consumes: `Target` (Task 2).
- Produces: `RolloutPlanner.RolloutPlan(List<RolloutGroup> groups, boolean canaryGate, boolean continueAfterCanary)` with `isLast(RolloutGroup)`. `RolloutPlanner.RolloutGroup(String name, List<Target> targets)`. Static `RolloutPlanner.plan(List<Target> targets, String strategy, boolean canaryGate, boolean continueAfterCanary)`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.pebblehost.deploy;

import org.gradle.api.model.ObjectFactory;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RolloutPlannerTest {
    private Target target(String id, String group) {
        ObjectFactory objects = ProjectBuilder.builder().build().getObjects();
        Target t = objects.newInstance(Target.class);
        t.getServerId().set(id);
        t.getGroup().set(group);
        return t;
    }

    @Test
    void flatStrategyYieldsSingleDefaultGroup() {
        List<Target> ts = List.of(target("a", "canary"), target("b", "prod"));
        RolloutPlanner.RolloutPlan p = RolloutPlanner.plan(ts, "flat", true, false);
        assertEquals(1, p.groups().size());
        assertEquals("default", p.groups().get(0).name());
        assertEquals(2, p.groups().get(0).targets().size());
        assertFalse(p.canaryGate());
    }

    @Test
    void groupsStrategyPreservesOrderAndGroups() {
        List<Target> ts = List.of(target("a", "prod"), target("b", "canary"), target("c", "prod"));
        RolloutPlanner.RolloutPlan p = RolloutPlanner.plan(ts, "groups", true, false);
        assertEquals(List.of("prod", "canary"), p.groups().stream().map(RolloutPlanner.RolloutGroup::name).toList());
        assertEquals(2, p.groups().get(0).targets().size());
        assertEquals(1, p.groups().get(1).targets().size());
    }

    @Test
    void canaryGateIsLastDetection() {
        List<Target> ts = List.of(target("a", "canary"), target("b", "prod"));
        RolloutPlanner.RolloutPlan p = RolloutPlanner.plan(ts, "groups", true, false);
        assertTrue(p.canaryGate());
        assertFalse(p.isLast(p.groups().get(0)));
        assertTrue(p.isLast(p.groups().get(1)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests dev.pebblehost.deploy.RolloutPlannerTest`
Expected: FAIL — `RolloutPlanner` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package dev.pebblehost.deploy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RolloutPlanner {
    public record RolloutGroup(String name, List<Target> targets) {}

    public record RolloutPlan(List<RolloutGroup> groups, boolean canaryGate, boolean continueAfterCanary) {
        public boolean isLast(RolloutGroup g) {
            return groups.indexOf(g) == groups.size() - 1;
        }
    }

    public static RolloutPlan plan(List<Target> targets, String strategy, boolean canaryGate, boolean continueAfterCanary) {
        if (strategy.equals("flat")) {
            return new RolloutPlan(List.of(new RolloutGroup("default", targets)), false, continueAfterCanary);
        }
        LinkedHashMap<String, List<Target>> byGroup = new LinkedHashMap<>();
        for (Target t : targets) {
            byGroup.computeIfAbsent(t.getGroup().get(), k -> new ArrayList<>()).add(t);
        }
        List<RolloutGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<Target>> e : byGroup.entrySet()) {
            groups.add(new RolloutGroup(e.getKey(), e.getValue()));
        }
        return new RolloutPlan(groups, canaryGate, continueAfterCanary);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests dev.pebblehost.deploy.RolloutPlannerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pebblehost/deploy/RolloutPlanner.java src/test/java/dev/pebblehost/deploy/RolloutPlannerTest.java
git commit -m "feat: add rollout planner with group ordering and canary gate"
```

---

### Task 5: Operations — backup, upload, power, verify, rollback

**Files:**
- Create: `src/main/java/dev/pebblehost/deploy/operations/BackupOperation.java`
- Create: `src/main/java/dev/pebblehost/deploy/operations/UploadOperation.java`
- Create: `src/main/java/dev/pebblehost/deploy/operations/PowerOperation.java`
- Create: `src/main/java/dev/pebblehost/deploy/operations/VerifyOperation.java`
- Create: `src/main/java/dev/pebblehost/deploy/operations/RollbackOperation.java`
- Test: `src/test/java/dev/pebblehost/deploy/operations/OperationsTest.java`

**Interfaces:**
- Consumes: `PebbleHostClient` (Task 3).
- Produces:
  - `BackupOperation.backup(String serverId, String targetDir, String jarName, String timestamp)` → `String` backup path or `null` if jar absent.
  - `UploadOperation.push(String serverId, String localPath, String targetDir)`.
  - `PowerOperation.restart(String serverId)`.
  - `VerifyOperation.verify(String serverId, String desiredState, long timeoutMs)` → `boolean`.
  - `RollbackOperation.restore(String serverId, String targetDir, String jarName, String backupPath, boolean restart, String verifyState, long verifyTimeoutMs)` — throws if `backupPath == null`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.pebblehost.deploy.operations;

import dev.pebblehost.deploy.CommandRunner;
import dev.pebblehost.deploy.PebbleHostClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OperationsTest {
    static class ScriptedRunner implements CommandRunner {
        final List<String> outputs = new ArrayList<>();
        int i = 0;
        @Override
        public String run(List<String> command, Map<String, String> env, Duration timeout) {
            return outputs.get(i++);
        }
    }

    private PebbleHostClient client(CommandRunner r) {
        return new PebbleHostClient("pb", "tok", null, r);
    }

    @Test
    void backupRenamesWhenJarPresent() {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        PebbleHostClient c = client(r);
        String backup = new BackupOperation(c).backup("srv-1", "plugins", "a.jar", "20260820000000");
        assertEquals("plugins/a.jar-deploy-20260820000000.bak", backup);
    }

    @Test
    void backupReturnsNullWhenJarAbsent() {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add("{\"data\":[]}");
        PebbleHostClient c = client(r);
        assertNull(new BackupOperation(c).backup("srv-1", "plugins", "a.jar", "20260820000000"));
    }

    @Test
    void verifyReturnsTrueWhenStateReached() {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add("{\"attributes\":{\"current_state\":\"starting\"}}");
        r.outputs.add("{\"attributes\":{\"current_state\":\"running\"}}");
        PebbleHostClient c = client(r);
        assertTrue(new VerifyOperation(c).verify("srv-1", "running", 10_000));
    }

    @Test
    void verifyReturnsFalseOnTimeout() {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add("{\"attributes\":{\"current_state\":\"offline\"}}");
        PebbleHostClient c = client(r);
        assertFalse(new VerifyOperation(c).verify("srv-1", "running", 1));
    }

    @Test
    void restoreRefusesWithoutBackup() {
        PebbleHostClient c = client(new ScriptedRunner());
        assertThrows(IllegalStateException.class,
            () -> new RollbackOperation(c).restore("srv-1", "plugins", "a.jar", null, true, "running", 1000));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests dev.pebblehost.deploy.operations.OperationsTest`
Expected: FAIL — operation classes do not exist.

- [ ] **Step 3: Write the implementation**

`BackupOperation.java`:
```java
package dev.pebblehost.deploy.operations;

import dev.pebblehost.deploy.PebbleHostClient;

import java.util.List;

public class BackupOperation {
    private final PebbleHostClient client;

    public BackupOperation(PebbleHostClient client) { this.client = client; }

    /** Returns the versioned backup path created, or null if the jar did not exist remotely. */
    public String backup(String serverId, String targetDir, String jarName, String timestamp) {
        List<String> files = client.listFiles(serverId, targetDir);
        if (!files.contains(jarName)) return null;
        String backupPath = targetDir + "/" + jarName + "-deploy-" + timestamp + ".bak";
        client.rename(serverId, targetDir + "/" + jarName, backupPath);
        return backupPath;
    }
}
```

`UploadOperation.java`:
```java
package dev.pebblehost.deploy.operations;

import dev.pebblehost.deploy.PebbleHostClient;

public class UploadOperation {
    private final PebbleHostClient client;

    public UploadOperation(PebbleHostClient client) { this.client = client; }

    public void push(String serverId, String localPath, String targetDir) {
        client.push(serverId, localPath, targetDir);
    }
}
```

`PowerOperation.java`:
```java
package dev.pebblehost.deploy.operations;

import dev.pebblehost.deploy.PebbleHostClient;

public class PowerOperation {
    private final PebbleHostClient client;

    public PowerOperation(PebbleHostClient client) { this.client = client; }

    public void restart(String serverId) {
        client.power(serverId, "restart");
    }
}
```

`VerifyOperation.java`:
```java
package dev.pebblehost.deploy.operations;

import dev.pebblehost.deploy.PebbleHostClient;

public class VerifyOperation {
    private static final long POLL_MS = 3000;
    private final PebbleHostClient client;

    public VerifyOperation(PebbleHostClient client) { this.client = client; }

    public boolean verify(String serverId, String desiredState, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (desiredState.equals(client.currentState(serverId))) return true;
            Thread.sleep(POLL_MS);
        }
        return desiredState.equals(client.currentState(serverId));
    }
}
```

`RollbackOperation.java`:
```java
package dev.pebblehost.deploy.operations;

import dev.pebblehost.deploy.PebbleHostClient;

public class RollbackOperation {
    private final PebbleHostClient client;

    public RollbackOperation(PebbleHostClient client) { this.client = client; }

    public void restore(String serverId, String targetDir, String jarName, String backupPath,
                        boolean restart, String verifyState, long verifyTimeoutMs) throws InterruptedException {
        if (backupPath == null) {
            throw new IllegalStateException("cannot restore: no backup exists for server " + serverId);
        }
        // Delete the bad jar first so the rename cannot collide/overwrite a live file.
        client.delete(serverId, targetDir + "/" + jarName);
        client.rename(serverId, backupPath, targetDir + "/" + jarName);
        if (restart) {
            client.power(serverId, "restart");
            new VerifyOperation(client).verify(serverId, verifyState, verifyTimeoutMs);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests dev.pebblehost.deploy.operations.OperationsTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pebblehost/deploy/operations src/test/java/dev/pebblehost/deploy/operations
git commit -m "feat: add deploy operations (backup, upload, power, verify, rollback)"
```

---

### Task 6: Orchestrator + ServerResult

**Files:**
- Create: `src/main/java/dev/pebblehost/deploy/ServerResult.java`
- Create: `src/main/java/dev/pebblehost/deploy/RolloutOrchestrator.java`
- Test: `src/test/java/dev/pebblehost/deploy/RolloutOrchestratorTest.java`

**Interfaces:**
- Consumes: `PebbleHostClient`, `RolloutPlanner.RolloutPlan`, `DeployConfig`, `Target`, operations (Tasks 2-5).
- Produces: `ServerResult` record `(String serverId, boolean success, String backupPath, String message)` with static `success(...)` and `failure(...)`. `RolloutOrchestrator(PebbleHostClient client, RolloutPlanner.RolloutPlan plan, DeployConfig config, org.gradle.api.logging.Logger logger)` with `void execute() throws Exception`. Package-visible `ServerResult deployServer(Target target)` for direct testing.

- [ ] **Step 1: Write the failing tests**

```java
package dev.pebblehost.deploy;

import dev.pebblehost.deploy.operations.*;
import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RolloutOrchestratorTest {
    static class ScriptedRunner implements CommandRunner {
        final List<String> outputs = new ArrayList<>();
        int i = 0;
        @Override
        public String run(List<String> command, Map<String, String> env, Duration timeout) {
            return outputs.get(Math.min(i++, outputs.size() - 1));
        }
    }

    private Target target(String id, String group) {
        Target t = org.gradle.testfixtures.ProjectBuilder.builder().build().getObjects().newInstance(Target.class);
        t.getServerId().set(id);
        t.getGroup().set(group);
        return t;
    }

    private DeployConfig config(String rollback) {
        File jar = new File("build/libs/a.jar");
        return DeployConfig.from("tok", null, jar, "plugins", true, "running", 10_000, rollback);
    }

    @Test
    void successfulFlatDeployRunsFullSequence() throws Exception {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add(""); // --version
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}"); // list
        r.outputs.add(""); // rename
        r.outputs.add(""); // push
        r.outputs.add(""); // power
        r.outputs.add("{\"attributes\":{\"current_state\":\"running\"}}"); // verify
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(List.of(target("srv-1", "default")), "flat", true, false);
        new RolloutOrchestrator(c, plan, config("abort"), Logging.getLogger(RolloutOrchestratorTest.class)).execute();
        assertTrue(true); // no exception = success
    }

    @Test
    void abortPolicyThrowsOnVerifyFailure() {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add(""); // --version
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.outputs.add(""); // rename
        r.outputs.add(""); // push
        r.outputs.add(""); // power
        r.outputs.add("{\"attributes\":{\"current_state\":\"offline\"}}"); // verify keeps failing
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(List.of(target("srv-1", "default")), "flat", true, false);
        assertThrows(Exception.class,
            () -> new RolloutOrchestrator(c, plan, config("abort"), Logging.getLogger(RolloutOrchestratorTest.class)).execute());
    }

    @Test
    void canaryGateStopsAfterFirstGroup() throws Exception {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add(""); // --version
        // group "canary": list, rename, push, power, verify
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.outputs.add(""); r.outputs.add(""); r.outputs.add("");
        r.outputs.add("{\"attributes\":{\"current_state\":\"running\"}}");
        // group "prod" must NOT run — runner would throw IndexOutOfBounds if it did
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(
            List.of(target("canary-1", "canary"), target("prod-1", "prod")), "groups", true, false);
        new RolloutOrchestrator(c, plan, config("abort"), Logging.getLogger(RolloutOrchestratorTest.class)).execute();
        assertEquals(6, r.i); // only canary group consumed outputs
    }

    @Test
    void restorePolicyRollsBackOnFailure() throws Exception {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add(""); // --version
        r.outputs.add("{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.outputs.add(""); // rename -> backup
        r.outputs.add(""); // push
        r.outputs.add(""); // power
        r.outputs.add("{\"attributes\":{\"current_state\":\"offline\"}}"); // verify fails
        r.outputs.add(""); // delete bad jar (rollback)
        r.outputs.add(""); // rename backup back (rollback)
        r.outputs.add(""); // power (rollback restart)
        r.outputs.add("{\"attributes\":{\"current_state\":\"running\"}}"); // rollback verify
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(List.of(target("srv-1", "default")), "flat", true, false);
        assertThrows(Exception.class,
            () -> new RolloutOrchestrator(c, plan, config("restore"), Logging.getLogger(RolloutOrchestratorTest.class)).execute());
        assertEquals(10, r.i); // rollback consumed 4 more outputs (delete, rename, power, verify)
    }

    @Test
    void restoreRollsBackSuccessfulPeersInFailedGroup() throws Exception {
        // Thread-safe, command-aware fake: responds per server ID and records
        // every command so we can assert rollback happened for BOTH peers.
        ServerAwareRunner r = new ServerAwareRunner();
        r.on("srv-ok", "files", "{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.on("srv-ok", "resources", "{\"attributes\":{\"current_state\":\"running\"}}");
        r.on("srv-bad", "files", "{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.on("srv-bad", "resources", "{\"attributes\":{\"current_state\":\"offline\"}}");
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(
            List.of(target("srv-ok", "default"), target("srv-bad", "default")), "flat", true, false);
        assertThrows(Exception.class,
            () -> new RolloutOrchestrator(c, plan, config("restore"), Logging.getLogger(RolloutOrchestratorTest.class)).execute());
        // Both peers must have been rolled back: delete + rename-back + power + verify each.
        assertRolledBack(r, "srv-ok");
        assertRolledBack(r, "srv-bad");
    }

    @Test
    void uploadFailurePreservesBackupForRollback() throws Exception {
        // Single server, flat deploy -> one-thread pool, deterministic order.
        // The fake throws on the push command so the backup path is preserved.
        ServerAwareRunner r = new ServerAwareRunner();
        r.on("srv-1", "files", "{\"data\":[{\"attributes\":{\"name\":\"a.jar\"}}]}");
        r.on("srv-1", "resources", "{\"attributes\":{\"current_state\":\"running\"}}");
        r.failOn("srv-1", "file"); // pb file push throws
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        RolloutPlanner.RolloutPlan plan = RolloutPlanner.plan(List.of(target("srv-1", "default")), "flat", true, false);
        assertThrows(Exception.class,
            () -> new RolloutOrchestrator(c, plan, config("restore"), Logging.getLogger(RolloutOrchestratorTest.class)).execute());
        assertRolledBack(r, "srv-1");
    }

    private void assertRolledBack(ServerAwareRunner r, String serverId) {
        List<List<String>> cmds = r.commandsFor(serverId);
        // Must contain a delete of the bad jar and a rename of the backup back.
        assertTrue(cmds.stream().anyMatch(c -> c.contains("delete")),
            "expected delete for " + serverId + " but got: " + cmds);
        assertTrue(cmds.stream().anyMatch(c -> c.contains("rename")),
            "expected rename-back for " + serverId + " but got: " + cmds);
        assertTrue(cmds.stream().anyMatch(c -> c.contains("power")),
            "expected restart for " + serverId + " but got: " + cmds);
    }

    /** Thread-safe fake pb keyed by server ID; records every command. */
    static class ServerAwareRunner implements CommandRunner {
        private final Map<String, Map<String, String>> responses = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, List<List<String>>> recorded = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, String> failOn = new java.util.concurrent.ConcurrentHashMap<>();

        void on(String serverId, String subcommand, String output) {
            responses.computeIfAbsent(serverId, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(subcommand, output);
        }
        void failOn(String serverId, String subcommand) {
            failOn.put(serverId, subcommand);
        }
        List<List<String>> commandsFor(String serverId) {
            return recorded.getOrDefault(serverId, List.of());
        }
        @Override
        public String run(List<String> command, Map<String, String> env, Duration timeout) {
            String serverId = command.contains("srv-1") ? "srv-1"
                : command.contains("srv-ok") ? "srv-ok"
                : command.contains("srv-bad") ? "srv-bad"
                : "unknown";
            recorded.computeIfAbsent(serverId, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(command);
            String sub = command.stream().skip(1).filter(s -> !s.startsWith("-")).findFirst().orElse("");
            if (failOn.containsKey(serverId) && failOn.get(serverId).equals(sub)) {
                throw new RuntimeException("upload failed");
            }
            Map<String, String> bySub = responses.get(serverId);
            return bySub == null ? "" : bySub.getOrDefault(sub, "");
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests dev.pebblehost.deploy.RolloutOrchestratorTest`
Expected: FAIL — `ServerResult`/`RolloutOrchestrator` do not exist.

- [ ] **Step 3: Write the implementation**

`ServerResult.java`:
```java
package dev.pebblehost.deploy;

public record ServerResult(String serverId, boolean success, String backupPath, String message) {
    public static ServerResult success(String serverId, String backupPath, boolean restarted) {
        return new ServerResult(serverId, true, backupPath, restarted ? "deployed and verified" : "deployed (no restart)");
    }
    public static ServerResult failure(String serverId, String message, String backupPath) {
        return new ServerResult(serverId, false, backupPath, message);
    }
}
```

`RolloutOrchestrator.java`:
```java
package dev.pebblehost.deploy;

import dev.pebblehost.deploy.operations.*;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RolloutOrchestrator {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final PebbleHostClient client;
    private final RolloutPlanner.RolloutPlan plan;
    private final DeployConfig config;
    private final Logger logger;

    public RolloutOrchestrator(PebbleHostClient client, RolloutPlanner.RolloutPlan plan, DeployConfig config, Logger logger) {
        this.client = client;
        this.plan = plan;
        this.config = config;
        this.logger = logger;
    }

    public void execute() throws Exception {
        client.validateBinary();
        List<ServerResult> all = new ArrayList<>();
        for (RolloutPlanner.RolloutGroup group : plan.groups()) {
            logger.lifecycle("Deploying group '{}' ({} server(s))", group.name(), group.targets().size());
            List<ServerResult> results = deployGroup(group);
            all.addAll(results);
            boolean failed = results.stream().anyMatch(r -> !r.success());
            if (failed) {
                handleFailure(results);
                throw new GradleException(buildReport(all));
            }
            if (plan.canaryGate() && !plan.continueAfterCanary() && !plan.isLast(group)) {
                logger.lifecycle("Canary group '{}' deployed and verified. Re-run with --continue-after-canary to deploy the remaining groups.", group.name());
                break;
            }
        }
        logger.lifecycle(buildReport(all));
    }

    private List<ServerResult> deployGroup(RolloutPlanner.RolloutGroup group) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(group.targets().size());
        List<Future<ServerResult>> futures = new ArrayList<>();
        for (Target t : group.targets()) {
            futures.add(pool.submit(() -> deployServer(t)));
        }
        List<ServerResult> results = new ArrayList<>();
        for (Future<ServerResult> f : futures) {
            try {
                results.add(f.get());
            } catch (ExecutionException e) {
                results.add(ServerResult.failure("unknown", String.valueOf(e.getCause()), null));
            }
        }
        pool.shutdown();
        return results;
    }

    ServerResult deployServer(Target target) {
        String serverId = target.getServerId().get();
        boolean restart = target.getRestart().getOrElse(config.restart());
        String jarName = config.jar().getName();
        String timestamp = LocalDateTime.now().format(TS);
        String backupPath = null;
        try {
            backupPath = new BackupOperation(client).backup(serverId, config.targetDir(), jarName, timestamp);
            new UploadOperation(client).push(serverId, config.jar().getAbsolutePath(), config.targetDir());
            if (restart) {
                new PowerOperation(client).restart(serverId);
                boolean ok = new VerifyOperation(client).verify(serverId, config.verifyState(), config.verifyTimeoutMs());
                if (!ok) {
                    return ServerResult.failure(serverId,
                        "server did not reach state '" + config.verifyState() + "' within " + config.verifyTimeoutMs() + "ms",
                        backupPath);
                }
            }
            return ServerResult.success(serverId, backupPath, restart);
        } catch (Exception e) {
            return ServerResult.failure(serverId, e.getMessage(), backupPath);
        }
    }

    private void handleFailure(List<ServerResult> results) {
        if (!config.rollback().equals("restore")) return;
        // Roll back ALL servers in the failed group that have a backup, so the
        // group is left consistent (not a mix of old and new versions).
        for (ServerResult r : results) {
            if (r.backupPath() != null) {
                try {
                    new RollbackOperation(client).restore(r.serverId(), config.targetDir(), config.jar().getName(),
                        r.backupPath(), true, config.verifyState(), config.verifyTimeoutMs());
                    logger.warn("Rolled back {} to {}", r.serverId(), r.backupPath());
                } catch (Exception e) {
                    logger.error("Rollback failed for {}: {}", r.serverId(), e.getMessage());
                }
            }
        }
    }

    private String buildReport(List<ServerResult> results) {
        StringBuilder sb = new StringBuilder("PebbleHost deploy report:\n");
        for (ServerResult r : results) {
            sb.append("  ").append(r.serverId()).append(": ")
              .append(r.success() ? "OK" : "FAILED").append(" — ").append(r.message());
            if (r.backupPath() != null) sb.append(" (backup: ").append(r.backupPath()).append(")");
            sb.append('\n');
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests dev.pebblehost.deploy.RolloutOrchestratorTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pebblehost/deploy/ServerResult.java src/main/java/dev/pebblehost/deploy/RolloutOrchestrator.java src/test/java/dev/pebblehost/deploy/RolloutOrchestratorTest.java
git commit -m "feat: add rollout orchestrator with per-server deploy and rollback"
```

---

### Task 7: Plugin wiring + DeployPebbleHostTask

**Files:**
- Create: `src/main/java/dev/pebblehost/deploy/PebbleHostPlugin.java`
- Create: `src/main/java/dev/pebblehost/deploy/DeployPebbleHostTask.java`
- Test: `src/test/java/dev/pebblehost/deploy/PebbleHostPluginTest.java`

**Interfaces:**
- Consumes: `PebbleHostExtension`, `DeployConfig`, `PebbleHostClient`, `ProcessCommandRunner`, `RolloutPlanner`, `RolloutOrchestrator` (Tasks 2-6).
- Produces: `PebbleHostPlugin` (plugin id `dev.pebblehost.deploy`), `DeployPebbleHostTask` with `@Option`s `deploy-restart`, `deploy-rollback`, `continue-after-canary`.

- [ ] **Step 1: Write the failing test**

```java
package dev.pebblehost.deploy;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PebbleHostPluginTest {
    @Test
    void registersExtensionAndTask() {
        Project p = ProjectBuilder.builder().build();
        p.getPlugins().apply("dev.pebblehost.deploy");
        assertNotNull(p.getExtensions().findByName("pebblehost"));
        assertNotNull(p.getTasks().findByName("deployPebbleHost"));
    }

    @Test
    void taskWiresExtensionDefaults() {
        Project p = ProjectBuilder.builder().build();
        p.getPlugins().apply("dev.pebblehost.deploy");
        DeployPebbleHostTask t = (DeployPebbleHostTask) p.getTasks().findByName("deployPebbleHost");
        assertEquals("groups", t.getStrategy().get());
        assertEquals("abort", t.getRollback().get());
        assertEquals("running", t.getVerifyState().get());
        assertTrue(t.getRestart().get());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests dev.pebblehost.deploy.PebbleHostPluginTest`
Expected: FAIL — `PebbleHostPlugin`/`DeployPebbleHostTask` do not exist.

- [ ] **Step 3: Write the implementation**

`PebbleHostPlugin.java`:
```java
package dev.pebblehost.deploy;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class PebbleHostPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        PebbleHostExtension ext = project.getExtensions().create("pebblehost", PebbleHostExtension.class);
        project.getTasks().register("deployPebbleHost", DeployPebbleHostTask.class, task -> {
            task.setGroup("deployment");
            task.setDescription("Deploy the built jar to PebbleHost servers via the pb CLI.");
            task.getToken().set(ext.getToken());
            task.getBaseUrl().set(ext.getBaseUrl());
            task.getJar().set(ext.getJar());
            task.getTargetDir().set(ext.getTargetDir());
            task.getStrategy().set(ext.getStrategy());
            task.getCanaryGate().set(ext.getCanaryGate());
            task.getContinueAfterCanary().set(ext.getContinueAfterCanary());
            task.getRestart().set(ext.getRestart());
            task.getVerifyState().set(ext.getVerifyState());
            task.getVerifyTimeoutMs().set(ext.getVerifyTimeoutMs());
            task.getRollback().set(ext.getRollback());
            task.getTargets().set(ext.getTargets());
        });
    }
}
```

`DeployPebbleHostTask.java`:
```java
package dev.pebblehost.deploy;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.UntrackedTask;

@UntrackedTask(because = "Deploys to remote servers; always runs")
public abstract class DeployPebbleHostTask extends DefaultTask {
    @Input @Optional public abstract Property<String> getToken();
    @Input public abstract Property<String> getBaseUrl();
    @InputFile public abstract RegularFileProperty getJar();
    @Input public abstract Property<String> getTargetDir();
    @Input public abstract Property<String> getStrategy();
    @Input public abstract Property<Boolean> getCanaryGate();
    @Input public abstract Property<Boolean> getContinueAfterCanary();
    @Input public abstract Property<Boolean> getRestart();
    @Input public abstract Property<String> getVerifyState();
    @Input public abstract Property<Long> getVerifyTimeoutMs();
    @Input public abstract Property<String> getRollback();
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
        PebbleHostClient client = new PebbleHostClient("pb", token, config.baseUrl(), new ProcessCommandRunner());
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

    private String resolveToken() {
        if (getToken().isPresent() && !getToken().get().isBlank()) return getToken().get();
        String env = System.getenv("PEBBLEHOST_API_TOKEN");
        if (env != null && !env.isBlank()) return env;
        Object prop = getProject().findProperty("pebblehostToken");
        if (prop != null && !prop.toString().isBlank()) return prop.toString();
        throw new GradleException("Missing PebbleHost API token: set pebblehost.token, PEBBLEHOST_API_TOKEN, or gradle.properties pebblehostToken");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests dev.pebblehost.deploy.PebbleHostPluginTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/pebblehost/deploy/PebbleHostPlugin.java src/main/java/dev/pebblehost/deploy/DeployPebbleHostTask.java src/test/java/dev/pebblehost/deploy/PebbleHostPluginTest.java
git commit -m "feat: wire plugin extension and deploy task"
```

---

### Task 8: Functional TestKit test with a fake pb

**Files:**
- Create: `src/test/java/dev/pebblehost/deploy/DeployFunctionalTest.java`
- Create: `src/test/resources/fake-pb/fake-pb.sh`

**Interfaces:**
- Consumes: the fully wired plugin (Task 7).
- Produces: proof the plugin works end-to-end against a fake `pb` on PATH.

- [ ] **Step 1: Write the fake pb script**

`src/test/resources/fake-pb/fake-pb.sh`:
```bash
#!/usr/bin/env bash
# Fake pb for functional tests. Emits canned responses for the commands the
# plugin issues. Real pb would talk to the PebbleHost API.
set -euo pipefail

case "$1" in
  --version) echo "pb 0.0.0-test"; exit 0 ;;
  files)
    # pb files <server> --directory <dir>
    echo '{"data":[{"attributes":{"name":"a.jar","is_file":true}}]}'
    ;;
  resources)
    # pb resources <server>
    echo '{"attributes":{"current_state":"running"}}'
    ;;
  api-call)
    # pb api-call PUT .../files/rename --body '...'
    echo '{}'
    ;;
  file)
    # pb file push <local> --server <id> --directory <dir>
    echo "uploaded"
    ;;
  power)
    # pb power <server> --action restart
    echo '{}'
    ;;
  *) echo "unexpected args: $*" >&2; exit 2 ;;
esac
```

- [ ] **Step 2: Write the failing functional test**

```java
package dev.pebblehost.deploy;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.*;

class DeployFunctionalTest {
    @TempDir Path testProjectDir;

    private void write(String rel, String content) throws IOException {
        Path p = testProjectDir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private Path fakeBinDir() throws IOException {
        Path dir = testProjectDir.resolve("fakebin");
        Files.createDirectories(dir);
        Path script = dir.resolve("pb");
        Files.copy(getClass().getResourceAsStream("/fake-pb/fake-pb.sh"), script);
        script.toFile().setExecutable(true);
        return dir;
    }

    @Test
    void deployTaskRunsAgainstFakePb() throws IOException {
        write("settings.gradle.kts", "rootProject.name = 'consumer'\n");
        write("build.gradle.kts", """
            plugins {
                id 'dev.pebblehost.deploy'
            }
            pebblehost {
                jar = file('build/libs/a.jar')
                targetDir = 'plugins'
                strategy = 'flat'
                targets {
                    create('t1') {
                        serverId = 'srv-1'
                    }
                }
            }
            """);
        write("build/libs/a.jar", "fake jar bytes");

        Path fakeBin = fakeBinDir();
        BuildResult result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withArguments("deployPebbleHost", "--deploy-restart=false")
            .withEnvironment("PATH", fakeBin + ":" + System.getenv("PATH"))
            .withEnvironment("PEBBLEHOST_API_TOKEN", "test-token")
            .build();

        assertEquals(SUCCESS, result.task(":deployPebbleHost").getOutcome());
        assertTrue(result.getOutput().contains("PebbleHost deploy report"));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests dev.pebblehost.deploy.DeployFunctionalTest`
Expected: FAIL — `DeployFunctionalTest` does not exist (compile error).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests dev.pebblehost.deploy.DeployFunctionalTest`
Expected: PASS. (The implementation is the plugin from Tasks 2-7; this task only adds the test. If it fails, debug the fake script PATH handling.)

- [ ] **Step 5: Commit**

```bash
git add src/test/java/dev/pebblehost/deploy/DeployFunctionalTest.java src/test/resources/fake-pb/fake-pb.sh
git commit -m "test: add TestKit functional test with fake pb"
```

---

### Task 9: CI workflow + README

**Files:**
- Create: `.github/workflows/deploy.yml`
- Create: `README.md`

- [ ] **Step 1: Write the reusable workflow**

`.github/workflows/deploy.yml`:
```yaml
name: Deploy to PebbleHost

on:
  workflow_dispatch:
    inputs:
      restart:
        description: Restart servers after upload
        type: boolean
        default: true
      rollback:
        description: Rollback policy on failure (abort|restore)
        type: choice
        options: [abort, restore]
        default: abort
      continue-after-canary:
        description: Proceed past the canary group
        type: boolean
        default: false

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - name: Install pb
        run: curl -sSL https://raw.githubusercontent.com/mintychochip/pebblehost-cli/master/scripts/install.sh | sh
      - name: Build
        run: ./gradlew build
      - name: Deploy
        env:
          PEBBLEHOST_API_TOKEN: ${{ secrets.PEBBLEHOST_API_TOKEN }}
        run: ./gradlew deployPebbleHost --deploy-restart=${{ inputs.restart }} --deploy-rollback=${{ inputs.rollback }} --continue-after-canary=${{ inputs.continue-after-canary }}
```

- [ ] **Step 2: Write the README**

`README.md`:
```markdown
# pebblehost-deploy

A Gradle plugin that deploys a built Minecraft plugin/mod jar to PebbleHost
servers via the [pebblehost-cli](https://github.com/mintychochip/pebblehost-cli)
`pb` binary. Supports manual (`deployPebbleHost`) and CI (reusable
`deploy.yml` workflow) deployment, with flat or grouped canary→prod rollout.

## Prerequisites

- Java 17+ and the Gradle wrapper (included).
- The `pb` binary on PATH (see pebblehost-cli install instructions).
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
    targets {
        create("canary") { serverId = "abc123"; group = "canary" }
        create("prod1")  { serverId = "def456"; group = "prod" }
    }
}
```

Manual: `./gradlew deployPebbleHost`

CI: run the reusable `deploy.yml` workflow (workflow_dispatch). It builds the
jar, installs `pb`, and runs the same task with `PEBBLEHOST_API_TOKEN` from
secrets.

## Rollout

- `flat`: all targets deploy in parallel.
- `groups`: targets grouped by `group` deploy in parallel within a group;
  groups run in order. With `canaryGate=true` (default), only the first group
  deploys until you re-run with `--continue-after-canary`.

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
```

- [ ] **Step 3: Verify README + workflow are consistent with the plugin**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/deploy.yml README.md
git commit -m "docs: add CI workflow and README"
```

---

### Task 10: Full verification

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL — all unit + functional tests pass.

- [ ] **Step 2: Confirm git history**

Run: `git log --oneline`
Expected: 9 feature commits + the design-spec commit, clean linear history.

- [ ] **Step 3: Note the prerequisite**

The plugin requires `pb file push` (CLI extension). Until the CLI plan
(`2026-08-20-pebblehost-cli-file-push.md`) is implemented and released, the
deploy task fails at `push` with a clear "pb command failed" message. The
functional test's fake `pb` covers the plugin side independently.
