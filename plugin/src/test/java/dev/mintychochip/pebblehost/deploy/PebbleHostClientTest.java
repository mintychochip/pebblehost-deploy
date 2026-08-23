package dev.mintychochip.pebblehost.deploy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PebbleHostClientTest {
    static class FakeRunner implements CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        String output = "{}";
        @Override
        public String run(List<String> command, Map<String, String> env, Duration timeout) {
            calls.add(new ArrayList<>(command));
            return output;
        }
    }

    @Test
    void validateBinaryRunsVersion() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", "https://panel.pebblehost.com", r);
        c.validateBinary();
        assertEquals(List.of("pb", "--base-url", "https://panel.pebblehost.com", "--version"), r.calls.get(0));
    }

    @Test
    void currentStateParsesAttributes() {
        FakeRunner r = new FakeRunner();
        r.output = "{\"attributes\":{\"current_state\":\"running\"}}";
        PebbleHostClient c = new PebbleHostClient("pb", "tok", "https://panel.pebblehost.com", r);
        assertEquals("running", c.currentState("srv-1"));
        assertEquals(List.of("pb", "--base-url", "https://panel.pebblehost.com", "resources", "srv-1"), r.calls.get(0));
    }

    @Test
    void listFilesParsesNames() {
        FakeRunner r = new FakeRunner();
        r.output = "{\"data\":[{\"attributes\":{\"name\":\"a.jar\",\"is_file\":true}},{\"attributes\":{\"name\":\"b.jar\",\"is_file\":true}}]}";
        PebbleHostClient c = new PebbleHostClient("pb", null, null, r);
        assertEquals(List.of("a.jar", "b.jar"), c.listFiles("srv-1", "plugins"));
        assertEquals(List.of("pb", "files", "srv-1", "--directory", "plugins"), r.calls.get(0));
    }

    @Test
    void renameSendsApiCallPut() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        c.rename("srv-1", "plugins/a.jar", "plugins/a-deploy-1.bak");
        assertEquals(List.of("pb", "api-call", "PUT", "/api/client/servers/srv-1/files/rename",
            "--body", "{\"root\":\"/\",\"from\":\"plugins/a.jar\",\"to\":\"plugins/a-deploy-1.bak\"}"), r.calls.get(0));
    }

    @Test
    void deleteSendsApiCallPost() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        c.delete("srv-1", "plugins/a.jar");
        assertEquals(List.of("pb", "api-call", "POST", "/api/client/servers/srv-1/files/delete",
            "--body", "{\"root\":\"/\",\"files\":[\"plugins/a.jar\"]}"), r.calls.get(0));
    }

    @Test
    void pushCallsFilePush() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        c.push("srv-1", "/build/libs/a.jar", "plugins");
        assertEquals(List.of("pb", "file", "push", "/build/libs/a.jar", "--server", "srv-1", "--directory", "plugins"), r.calls.get(0));
    }

    @Test
    void powerSendsAction() {
        FakeRunner r = new FakeRunner();
        PebbleHostClient c = new PebbleHostClient("pb", "tok", null, r);
        c.power("srv-1", "restart");
        assertEquals(List.of("pb", "power", "srv-1", "--action", "restart"), r.calls.get(0));
    }
}
