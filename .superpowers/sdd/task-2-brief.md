
**Files:**
- Modify: `plugin/src/test/java/dev/mintychochip/pebblehost/deploy/PbInstallerTest.java` (append tests)

**Interfaces:**
- Consumes: `PbInstaller(Path, List<String>, Logger, String apiBase)` test seam from Task 1.
- Produces: verified behavior — `resolve("pb", "latest")` downloads, verifies sha256 against API `digest`, extracts into `<cache>/<tag>/pb`, marks executable, and subsequent `resolve("pb", "<pinned>")` is fully offline on cache hit.

- [ ] **Step 1: Write failing tests (append to PbInstallerTest)**

```java
    // ---- network install path (offline stub) ----

    private record Stub(HttpServer server, String apiBase, String assetUrl) {}

    private Stub startStub(String tag, String version, String sha256, byte[] tarball) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String assetUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/asset.tar.gz";
        String metadata = "{\"tag_name\":\"" + tag + "\",\"assets\":[{\"name\":\"pebblehost-cli-" + version
            + "-x86_64-unknown-linux-gnu.tar.gz\",\"digest\":\"sha256:" + sha256
            + "\",\"browser_download_url\":\"" + assetUrl + "\"}]}";
        server.createContext("/releases/", exchange -> {
            byte[] body = metadata.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/asset.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, tarball.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(tarball); }
        });
        server.start();
        return new Stub(server, "http://127.0.0.1:" + server.getAddress().getPort() + "/releases/", assetUrl);
    }

    private byte[] realTarballContainingPb() throws IOException, InterruptedException {
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path pb = work.resolve("pb");
        Files.writeString(pb, "#!/bin/sh\necho ok\n");
        pb.toFile().setExecutable(true);
        Path tarball = tmp.resolve("asset.tar.gz");
        new ProcessBuilder("tar", "-czf", tarball.toString(), "-C", work.toString(), "pb")
            .start().waitFor();
        return Files.readAllBytes(tarball);
    }

    @Test
    void installsFromReleasesVerifyingDigestThenServesFromCache() throws Exception {
        byte[] tarball = realTarballContainingPb();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tarball));
        Stub stub = startStub("v2026.9.1.42", "2026.9.1.42", sha, tarball);
        Path cache = tmp.resolve("cache");
        PbInstaller installer = new PbInstaller(cache, List.of(), LOG, stub.apiBase());

        String resolved = installer.resolve("pb", "latest");

        Path installed = cache.resolve("v2026.9.1.42").resolve("pb");
        assertEquals(installed.toAbsolutePath().toString(), resolved);
        assertTrue(Files.isRegularFile(installed));
        assertTrue(Files.isExecutable(installed));

        // Second resolve with the now-known pin must be fully offline.
        stub.server().stop(0);
        String again = installer.resolve("pb", "2026.9.1.42");
        assertEquals(installed.toAbsolutePath().toString(), again);
    }

    @Test
    void pinnedVersionIsOfflineOnCacheHit() throws Exception {
        Path cached = Files.createDirectories(tmp.resolve("cache").resolve("v2026.9.1.42")).resolve("pb");
        Files.writeString(cached, "#!/bin/sh\n");
        PbInstaller installer = new PbInstaller(tmp.resolve("cache"), List.of(), LOG,
            "http://127.0.0.1:1/releases/"); // port 1: any attempt fails the test loudly

        assertEquals(cached.toAbsolutePath().toString(), installer.resolve("pb", "2026.9.1.42"));
    }

    @Test
    void digestMismatchInstallsNothing() throws Exception {
        byte[] tarball = realTarballContainingPb();
        Stub stub = startStub("v2026.9.1.42", "2026.9.1.42",
            "0000000000000000000000000000000000000000000000000000000000000000", tarball);
        Path cache = tmp.resolve("cache");
        PbInstaller installer = new PbInstaller(cache, List.of(), LOG, stub.apiBase());

        GradleException ex = assertThrows(GradleException.class, () -> installer.resolve("pb", "latest"));
        assertTrue(ex.getMessage().contains("sha256 verification"), ex.getMessage());
        assertFalse(Files.exists(cache.resolve("v2026.9.1.42").resolve("pb")));
        stub.server().stop(0);
    }

    @Test
    void missingPlatformAssetFailsWithClearMessage() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String metadata = "{\"tag_name\":\"v2026.9.1.42\",\"assets\":[]}";
        server.createContext("/releases/", exchange -> {
            byte[] body = metadata.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            PbInstaller installer = new PbInstaller(tmp.resolve("cache"), List.of(), LOG,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/releases/");
            GradleException ex = assertThrows(GradleException.class, () -> installer.resolve("pb", "latest"));
            assertTrue(ex.getMessage().contains("publishes no asset"), ex.getMessage());
        } finally {
            server.stop(0);
        }
    }
```

Also add these imports at the top of the test file:

```java
import com.sun.net.httpserver.HttpServer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.OutputStream;
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :plugin:test --tests dev.mintychochip.pebblehost.deploy.PbInstallerTest`
Expected: PASS — Task 1's implementation already covers these paths (this step validates them; if any fail, fix PbInstaller before proceeding).

- [ ] **Step 3: Commit**

```bash
git add plugin/src/test/java/dev/mintychochip/pebblehost/deploy/PbInstallerTest.java
git commit -m "test(plugin): cover pb download, digest verification, and caching"
```

---

