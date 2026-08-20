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
            return outputs.get(Math.min(i++, outputs.size() - 1));
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
    void verifyReturnsTrueWhenStateReached() throws InterruptedException {
        ScriptedRunner r = new ScriptedRunner();
        r.outputs.add("{\"attributes\":{\"current_state\":\"starting\"}}");
        r.outputs.add("{\"attributes\":{\"current_state\":\"running\"}}");
        PebbleHostClient c = client(r);
        assertTrue(new VerifyOperation(c).verify("srv-1", "running", 10_000));
    }

    @Test
    void verifyReturnsFalseOnTimeout() throws InterruptedException {
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

    @Test
    void restoreThrowsWhenServerDoesNotComeBack() throws InterruptedException {
        ScriptedRunner r = new ScriptedRunner();
        // delete, rename, power, then verify keeps returning offline -> rollback fails
        r.outputs.add(""); // delete
        r.outputs.add(""); // rename
        r.outputs.add(""); // power
        r.outputs.add("{\"attributes\":{\"current_state\":\"offline\"}}"); // verify fails
        PebbleHostClient c = client(r);
        assertThrows(IllegalStateException.class,
            () -> new RollbackOperation(c).restore("srv-1", "plugins", "a.jar", "plugins/a.jar-deploy-1.bak", true, "running", 1));
    }
}
