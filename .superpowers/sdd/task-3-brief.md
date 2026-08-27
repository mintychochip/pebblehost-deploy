
**Files:**
- Modify: `plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostExtension.java`
- Modify: `plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostPlugin.java`
- Modify: `plugin/src/main/java/dev/mintychochip/pebblehost/deploy/DeployPebbleHostTask.java`

**Interfaces:**
- Consumes: `PbInstaller(Path cacheRoot, Logger logger)`, `resolve(String pbBinary, String pbVersion)`.
- Produces: extension/task property `Property<String> getPbVersion()` (convention `"latest"`); `deploy()` uses the resolved absolute binary path.

- [ ] **Step 1: Add the property to the extension**

In `PebbleHostExtension.java`, add the field next to `pbBinary` (line 23):

```java
    private final Property<String> pbVersion;
```

In the constructor after `this.pbBinary = objects.property(String.class).convention("pb");` (line 40):

```java
        this.pbVersion = objects.property(String.class).convention("latest");
```

After `public Property<String> getPbBinary() { return pbBinary; }` (line 55):

```java
    public Property<String> getPbVersion() { return pbVersion; }
```

- [ ] **Step 2: Wire it through plugin registration**

In `PebbleHostPlugin.java`, after `task.getPbBinary().set(ext.getPbBinary());` (line 24):

```java
            task.getPbVersion().set(ext.getPbVersion());
```

- [ ] **Step 3: Resolve pb in the task action**

In `DeployPebbleHostTask.java`, add next to line 33 (`@Input public abstract Property<String> getPbBinary();`):

```java
    @Input public abstract Property<String> getPbVersion();
```

Replace lines 51 (client construction) with:

```java
        PbInstaller installer = new PbInstaller(
            getProject().getGradle().getGradleUserHomeDir().toPath()
                .resolve("caches").resolve("pebblehost-deploy").resolve("pb"),
            getLogger());
        String pb = installer.resolve(getPbBinary().get(), getPbVersion().get());
        PebbleHostClient client = new PebbleHostClient(pb, token, config.baseUrl(), new ProcessCommandRunner());
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew clean build; echo "exit=$?"`
Expected: BUILD SUCCESSFUL, exit=0 — all existing suites plus PbInstallerTest green (functional tests pass explicit fake-pb paths, so they exercise tier 1).

- [ ] **Step 5: Commit**

```bash
git add plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostExtension.java \
        plugin/src/main/java/dev/mintychochip/pebblehost/deploy/PebbleHostPlugin.java \
        plugin/src/main/java/dev/mintychochip/pebblehost/deploy/DeployPebbleHostTask.java
git commit -m "feat(plugin): wire pbVersion DSL and auto-resolve pb in deploy task"
```

---

