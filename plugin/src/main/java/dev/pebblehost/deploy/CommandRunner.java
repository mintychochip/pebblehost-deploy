package dev.pebblehost.deploy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface CommandRunner {
    String run(List<String> command, Map<String, String> env, Duration timeout) throws IOException, InterruptedException;
}
