package dev.mintychochip.pebblehost.deploy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DeployConfigTest {
    @TempDir Path tempDir;

    private File existingJar() throws IOException {
        Path jar = tempDir.resolve("a.jar");
        Files.writeString(jar, "fake jar");
        return jar.toFile();
    }

    @Test
    void validatesRollback() throws IOException {
        File jar = existingJar();
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", jar, "plugins", true, "running", 1000L, "bogus"));
    }

    @Test
    void acceptsValidConfig() throws IOException {
        File jar = existingJar();
        DeployConfig c = DeployConfig.from("t", "http://x", jar, "plugins", true, "running", 1000L, "restore");
        assertEquals("restore", c.rollback());
        assertEquals("plugins", c.targetDir());
    }

    @Test
    void rejectsMissingJar() throws IOException {
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", new File(tempDir.resolve("no-such.jar").toString()), "plugins", true, "running", 1000L, "abort"));
    }
}
