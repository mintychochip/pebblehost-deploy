package dev.mintychochip.pebblehost.deploy;

import com.sun.net.httpserver.HttpServer;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PbInstallerTest {
    private static final org.gradle.api.logging.Logger LOG = Logging.getLogger(PbInstallerTest.class);

    @TempDir Path tmp;

    // ---- platform mapping ----

    @Test void mapsLinuxX64() {
        assertEquals("x86_64-unknown-linux-gnu", PbInstaller.platformTarget("Linux", "amd64"));
    }

    @Test void mapsLinuxArm64() {
        assertEquals("aarch64-unknown-linux-gnu", PbInstaller.platformTarget("Linux", "aarch64"));
    }

    @Test void mapsLinuxArm32() {
        assertEquals("armv7-unknown-linux-gnueabihf", PbInstaller.platformTarget("Linux", "arm"));
    }

    @Test void mapsMacX64AndArm64() {
        assertEquals("x86_64-apple-darwin", PbInstaller.platformTarget("Mac OS X", "x86_64"));
        assertEquals("aarch64-apple-darwin", PbInstaller.platformTarget("Mac OS X", "arm64"));
    }

    @Test void mapsWindowsRegardlessOfArch() {
        assertEquals("x86_64-pc-windows-msvc", PbInstaller.platformTarget("Windows 11", "amd64"));
    }

    @Test void rejectsUnknownPlatformWithActionableMessage() {
        GradleException ex = assertThrows(GradleException.class,
            () -> PbInstaller.platformTarget("SunOS", "sparc"));
        assertTrue(ex.getMessage().contains("pbBinary"), ex.getMessage());
    }

    // ---- tag normalization ----

    @Test void normalizesVersionSpecsToTags() {
        assertEquals("latest", PbInstaller.normalizeTag("latest"));
        assertEquals("v2026.8.21.16", PbInstaller.normalizeTag("2026.8.21.16"));
        assertEquals("v2026.8.21.16", PbInstaller.normalizeTag("v2026.8.21.16"));
    }

    // ---- resolution tiers ----

    @Test void explicitExistingBinaryWinsAsAbsolutePath() throws Exception {
        Path pb = tmp.resolve("mypb");
        Files.writeString(pb, "#!/bin/sh\n");
        PbInstaller installer = new PbInstaller(tmp.resolve("cache"), List.of(), LOG, "http://127.0.0.1:1/releases/");

        String resolved = installer.resolve(tmp.resolve("mypb").toString(), "latest");

        assertEquals(pb.toAbsolutePath().toString(), resolved);
    }

    @Test void explicitMissingBinaryFailsWithoutSubstitution() {
        PbInstaller installer = new PbInstaller(tmp.resolve("cache"), List.of(), LOG, "http://127.0.0.1:1/releases/");

        GradleException ex = assertThrows(GradleException.class,
            () -> installer.resolve(tmp.resolve("nope").toString(), "latest"));
        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    @Test void pathHitShortCircuitsBeforeAutoInstall() throws Exception {
        Path binDir = Files.createDirectories(tmp.resolve("bin"));
        Path pb = binDir.resolve("pb");
        Files.writeString(pb, "#!/bin/sh\n");
        PbInstaller installer = new PbInstaller(tmp.resolve("cache"), List.of(binDir.toString()), LOG,
            "http://127.0.0.1:1/releases/");

        String resolved = installer.resolve("pb", "latest");

        assertEquals(pb.toAbsolutePath().toString(), resolved);
        assertTrue(!Files.exists(tmp.resolve("cache")), "auto-install must not run when PATH hit");
    }

    @Test void invalidExplicitPbBinaryFailsGracefully() {
        PbInstaller installer = new PbInstaller(tmp.resolve("cache"), List.of(), LOG, "http://127.0.0.1:1/releases/");

        GradleException ex = assertThrows(GradleException.class,
            () -> installer.resolve("bad\u0000path", "latest"));
        assertTrue(ex.getMessage().contains("not a valid filesystem path"), ex.getMessage());
    }

    // ---- network install hardening ----

    private record Stub(HttpServer server, String apiBase, String assetUrl) {}

    private Stub startStub(String tag, String version, String sha256, byte[] tarball) throws IOException {
        return startStub(tag, version, sha256, tarball, true);
    }

    private Stub startStub(String tag, String version, String sha256, byte[] tarball, boolean withDigest)
        throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String assetUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/asset.tar.gz";
        String assetName = "pebblehost-cli-" + version + "-x86_64-unknown-linux-gnu.tar.gz";
        String digestPart = withDigest ? ",\"digest\":\"sha256:" + sha256 + "\"" : "";
        String metadata = "{\"tag_name\":\"" + tag + "\",\"assets\":[{\"name\":\"" + assetName + "\"" + digestPart
            + ",\"browser_download_url\":\"" + assetUrl + "\"}]}";
        server.createContext("/releases/", exchange -> {
            byte[] body = metadata.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/asset.tar.gz", exchange -> {
            exchange.sendResponseHeaders(200, tarball.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(tarball);
            }
        });
        server.start();
        return new Stub(server, "http://127.0.0.1:" + server.getAddress().getPort() + "/releases/", assetUrl);
    }

    private long countPbFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().equals("pb")).count();
        }
    }

    @Test void digestMissingFailsHard() throws Exception {
        byte[] tarball = "not-a-real-archive".getBytes();
        Stub stub = startStub("v2026.9.1.42", "2026.9.1.42", null, tarball, false);
        Path cache = tmp.resolve("cache");
        PbInstaller installer = new PbInstaller(cache, List.of(), LOG, stub.apiBase());

        GradleException ex = assertThrows(GradleException.class, () -> installer.resolve("pb", "latest"));
        assertTrue(ex.getMessage().contains("publishes no sha256 digest"), ex.getMessage());
        assertEquals(0, countPbFiles(cache), "no pb file should be extracted under cache");

        stub.server().stop(0);
    }

    @Test void corruptedArchiveLeavesNoCachedBinary() throws Exception {
        byte[] garbage = "this is not valid gzip data at all".getBytes();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(garbage));
        Stub stub = startStub("v2026.9.1.43", "2026.9.1.43", sha, garbage);
        Path cache = tmp.resolve("cache");
        PbInstaller installer = new PbInstaller(cache, List.of(), LOG, stub.apiBase());

        GradleException ex = assertThrows(GradleException.class, () -> installer.resolve("pb", "latest"));
        assertTrue(ex.getMessage().toLowerCase().contains("extract"), ex.getMessage());
        assertEquals(0, countPbFiles(cache), "no pb file should exist anywhere under cache root");

        stub.server().stop(0);
    }
}
