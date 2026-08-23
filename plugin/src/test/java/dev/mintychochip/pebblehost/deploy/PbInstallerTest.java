package dev.mintychochip.pebblehost.deploy;

import org.gradle.api.GradleException;
import org.gradle.api.logging.Logging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
