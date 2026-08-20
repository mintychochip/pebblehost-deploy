package dev.pebblehost.deploy;

import org.gradle.api.model.ObjectFactory;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeployConfigTest {
    @TempDir Path tempDir;

    private File existingJar() throws IOException {
        Path jar = tempDir.resolve("a.jar");
        Files.writeString(jar, "fake jar");
        return jar.toFile();
    }

    private Target target(String id) {
        ObjectFactory objects = ProjectBuilder.builder().build().getObjects();
        Target t = objects.newInstance(Target.class);
        t.getServerId().set(id);
        return t;
    }

    @Test
    void validatesRollbackStrategyAndTargets() throws IOException {
        File jar = existingJar();
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", jar, "plugins", true, "running", 1000L, "bogus", "groups", List.of(target("s1"))));
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", jar, "plugins", true, "running", 1000L, "abort", "bogus", List.of(target("s1"))));
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", jar, "plugins", true, "running", 1000L, "abort", "groups", List.of()));
    }

    @Test
    void acceptsValidConfig() throws IOException {
        File jar = existingJar();
        DeployConfig c = DeployConfig.from("t", "http://x", jar, "plugins", true, "running", 1000L, "restore", "groups", List.of(target("s1")));
        assertEquals("restore", c.rollback());
        assertEquals("plugins", c.targetDir());
    }

    @Test
    void rejectsMissingJar() throws IOException {
        assertThrows(IllegalArgumentException.class,
            () -> DeployConfig.from("t", "http://x", new File(tempDir.resolve("no-such.jar").toString()), "plugins", true, "running", 1000L, "abort", "groups", List.of(target("s1"))));
    }
}
