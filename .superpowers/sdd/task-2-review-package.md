## Commits
b2e5185 test(plugin): exercise digest mismatch and installer chmod
ad00b5f test(plugin): cover pb download, digest verification, and caching

## Stat
 .../pebblehost/deploy/PbInstallerTest.java         | 82 ++++++++++++++++++++++
 1 file changed, 82 insertions(+)

## Diff
diff --git a/plugin/src/test/java/dev/mintychochip/pebblehost/deploy/PbInstallerTest.java b/plugin/src/test/java/dev/mintychochip/pebblehost/deploy/PbInstallerTest.java
index 2e1983a..74f3ff4 100644
--- a/plugin/src/test/java/dev/mintychochip/pebblehost/deploy/PbInstallerTest.java
+++ b/plugin/src/test/java/dev/mintychochip/pebblehost/deploy/PbInstallerTest.java
@@ -172,11 +172,93 @@ class PbInstallerTest {
         Stub stub = startStub("v2026.9.1.43", "2026.9.1.43", sha, garbage);
         Path cache = tmp.resolve("cache");
         PbInstaller installer = new PbInstaller(cache, List.of(), LOG, stub.apiBase());
 
         GradleException ex = assertThrows(GradleException.class, () -> installer.resolve("pb", "latest"));
         assertTrue(ex.getMessage().toLowerCase().contains("extract"), ex.getMessage());
         assertEquals(0, countBinaryFiles(cache), "no pb file should exist anywhere under cache root");
 
         stub.server().stop(0);
     }
+
+    // ---- network install path (offline stub) ----
+
+    private byte[] realTarballContainingPb() throws IOException, InterruptedException {
+        Path work = Files.createDirectories(tmp.resolve("work"));
+        String binName = currentBinName();
+        Path bin = work.resolve(binName);
+        Files.writeString(bin, "#!/bin/sh\necho ok\n");
+        Path tarball = tmp.resolve("asset.tar.gz");
+        new ProcessBuilder("tar", "-czf", tarball.toString(), "-C", work.toString(), binName)
+            .start().waitFor();
+        return Files.readAllBytes(tarball);
+    }
+
+    @Test
+    void installsFromReleasesVerifyingDigestThenServesFromCache() throws Exception {
+        byte[] tarball = realTarballContainingPb();
+        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tarball));
+        Stub stub = startStub("v2026.9.1.42", "2026.9.1.42", sha, tarball);
+        Path cache = tmp.resolve("cache");
+        PbInstaller installer = new PbInstaller(cache, List.of(), LOG, stub.apiBase());
+
+        String resolved = installer.resolve("pb", "latest");
+
+        Path installed = cache.resolve("v2026.9.1.42").resolve(currentBinName());
+        assertEquals(installed.toAbsolutePath().toString(), resolved);
+        assertTrue(Files.isRegularFile(installed));
+        assertTrue(Files.isExecutable(installed));
+
+        stub.server().stop(0);
+        String again = installer.resolve("pb", "2026.9.1.42");
+        assertEquals(installed.toAbsolutePath().toString(), again);
+    }
+
+    @Test
+    void digestMismatchRejectsDownloadedAsset() throws Exception {
+        byte[] tarball = realTarballContainingPb();
+        Stub stub = startStub("v2026.9.1.42", "2026.9.1.42",
+            "0000000000000000000000000000000000000000000000000000000000000000", tarball);
+        Path cache = tmp.resolve("cache");
+        PbInstaller installer = new PbInstaller(cache, List.of(), LOG, stub.apiBase());
+
+        GradleException ex = assertThrows(GradleException.class, () -> installer.resolve("pb", "latest"));
+        assertTrue(ex.getMessage().contains("sha256 verification"), ex.getMessage());
+        assertEquals(0, countBinaryFiles(cache), "no pb file should be extracted under cache");
+
+        stub.server().stop(0);
+    }
+
+    @Test
+    void pinnedVersionIsOfflineOnCacheHit() throws Exception {
+        Path cached = Files.createDirectories(tmp.resolve("cache").resolve("v2026.9.1.42"))
+            .resolve(currentBinName());
+        Files.writeString(cached, "#!/bin/sh\n");
+        PbInstaller installer = new PbInstaller(tmp.resolve("cache"), List.of(), LOG,
+            "http://127.0.0.1:1/releases/");
+
+        assertEquals(cached.toAbsolutePath().toString(), installer.resolve("pb", "2026.9.1.42"));
+    }
+
+    @Test
+    void missingPlatformAssetFailsWithClearMessage() throws IOException {
+        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
+        String metadata = "{\"tag_name\":\"v2026.9.1.42\",\"assets\":[]}";
+        server.createContext("/releases/", exchange -> {
+            byte[] body = metadata.getBytes();
+            exchange.getResponseHeaders().add("Content-Type", "application/json");
+            exchange.sendResponseHeaders(200, body.length);
+            try (OutputStream out = exchange.getResponseBody()) {
+                out.write(body);
+            }
+        });
+        server.start();
+        try {
+            PbInstaller installer = new PbInstaller(tmp.resolve("cache"), List.of(), LOG,
+                "http://127.0.0.1:" + server.getAddress().getPort() + "/releases/");
+            GradleException ex = assertThrows(GradleException.class, () -> installer.resolve("pb", "latest"));
+            assertTrue(ex.getMessage().contains("publishes no asset"), ex.getMessage());
+        } finally {
+            server.stop(0);
+        }
+    }
 }
