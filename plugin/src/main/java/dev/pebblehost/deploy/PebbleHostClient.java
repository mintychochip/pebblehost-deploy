package dev.pebblehost.deploy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PebbleHostClient {
    private final String pbBinary;
    private final String token;
    private final String baseUrl;
    private final CommandRunner runner;

    public PebbleHostClient(String pbBinary, String token, String baseUrl, CommandRunner runner) {
        this.pbBinary = pbBinary;
        this.token = token;
        this.baseUrl = baseUrl;
        this.runner = runner;
    }

    private Map<String, String> env() {
        return token == null || token.isBlank()
            ? Map.of()
            : Map.of("PEBBLEHOST_API_TOKEN", token);
    }

    private List<String> base() {
        List<String> cmd = new ArrayList<>();
        cmd.add(pbBinary);
        if (baseUrl != null && !baseUrl.isBlank()) {
            cmd.add("--base-url");
            cmd.add(baseUrl);
        }
        return cmd;
    }

    private String run(List<String> command, Duration timeout) {
        try {
            return runner.run(command, env(), timeout);
        } catch (Exception e) {
            throw new RuntimeException("pb command failed: " + command + " — " + e.getMessage(), e);
        }
    }

    public void validateBinary() {
        List<String> cmd = base();
        cmd.add("--version");
        run(cmd, Duration.ofSeconds(30));
    }

    public List<String> listFiles(String serverId, String directory) {
        List<String> cmd = base();
        cmd.addAll(List.of("files", serverId, "--directory", directory));
        String out = run(cmd, Duration.ofSeconds(60));
        JsonObject root = JsonParser.parseString(out).getAsJsonObject();
        JsonArray data = root.getAsJsonArray("data");
        List<String> names = new ArrayList<>();
        for (JsonElement e : data) {
            JsonObject attrs = e.getAsJsonObject().getAsJsonObject("attributes");
            if (attrs.has("name")) names.add(attrs.get("name").getAsString());
        }
        return names;
    }

    public String currentState(String serverId) {
        List<String> cmd = base();
        cmd.addAll(List.of("resources", serverId));
        String out = run(cmd, Duration.ofSeconds(60));
        JsonObject root = JsonParser.parseString(out).getAsJsonObject();
        JsonObject attrs = root.getAsJsonObject("attributes");
        return attrs != null && attrs.has("current_state") ? attrs.get("current_state").getAsString() : "";
    }

    public void rename(String serverId, String from, String to) {
        List<String> cmd = base();
        cmd.addAll(List.of("api-call", "PUT", "/api/client/servers/" + serverId + "/files/rename",
            "--body", "{\"root\":\"/\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}"));
        run(cmd, Duration.ofSeconds(60));
    }

    public void delete(String serverId, String path) {
        List<String> cmd = base();
        cmd.addAll(List.of("api-call", "POST", "/api/client/servers/" + serverId + "/files/delete",
            "--body", "{\"root\":\"/\",\"files\":[\"" + path + "\"]}"));
        run(cmd, Duration.ofSeconds(60));
    }

    public void push(String serverId, String localPath, String directory) {
        List<String> cmd = base();
        cmd.addAll(List.of("file", "push", localPath, "--server", serverId, "--directory", directory));
        run(cmd, Duration.ofSeconds(120));
    }

    public void power(String serverId, String action) {
        List<String> cmd = base();
        cmd.addAll(List.of("power", serverId, "--action", action));
        run(cmd, Duration.ofSeconds(60));
    }
}
