package dev.pebblehost.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ProcessCommandRunner implements CommandRunner {
    @Override
    public String run(List<String> command, Map<String, String> env, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(env);
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("command timed out after " + timeout + ": " + command);
        }
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IOException("command failed (exit " + p.exitValue() + "): " + command
                + "\nstderr: " + stderr + "\nstdout: " + stdout);
        }
        return stdout;
    }
}
