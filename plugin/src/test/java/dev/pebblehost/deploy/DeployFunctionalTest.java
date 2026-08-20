package dev.pebblehost.deploy;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
        write("settings.gradle.kts", "rootProject.name = \"consumer\"\n");
        Path fakePb = fakeBinDir().resolve("pb");
        String pbPath = fakePb.toString().replace("\\", "\\\\");
        write("build.gradle.kts", """
            plugins {
                id("dev.pebblehost.deploy")
            }
            pebblehost {
                jar = file("build/libs/a.jar")
                targetDir = "plugins"
                strategy = "flat"
                pbBinary = "%s"
                target("srv-1")
            }
            """.formatted(pbPath));
        write("build/libs/a.jar", "fake jar bytes");

        Map<String, String> env = new java.util.HashMap<>();
        env.put("PEBBLEHOST_API_TOKEN", "test-token");
        BuildResult result = GradleRunner.create()
            .withProjectDir(testProjectDir.toFile())
            .withPluginClasspath()
            .withArguments("deployPebbleHost", "--deploy-restart=false")
            .withEnvironment(env)
            .build();

        assertEquals(SUCCESS, result.task(":deployPebbleHost").getOutcome());
        assertTrue(result.getOutput().contains("PebbleHost deploy report"));
    }
}
