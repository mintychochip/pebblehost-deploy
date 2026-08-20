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
