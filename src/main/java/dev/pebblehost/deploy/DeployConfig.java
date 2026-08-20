package dev.pebblehost.deploy;

import java.io.File;
import java.util.List;

public record DeployConfig(
        String token, String baseUrl, File jar, String targetDir,
        boolean restart, String verifyState, long verifyTimeoutMs, String rollback) {

    public static DeployConfig from(String token, String baseUrl, File jar, String targetDir,
                                    boolean restart, String verifyState, long verifyTimeoutMs, String rollback) {
        return from(token, baseUrl, jar, targetDir, restart, verifyState, verifyTimeoutMs, rollback, "groups", List.of());
    }

    public static DeployConfig from(String token, String baseUrl, File jar, String targetDir,
                                    boolean restart, String verifyState, long verifyTimeoutMs, String rollback,
                                    String strategy, List<Target> targets) {
        if (!rollback.equals("abort") && !rollback.equals("restore")) {
            throw new IllegalArgumentException("rollback must be 'abort' or 'restore', got: " + rollback);
        }
        if (!strategy.equals("flat") && !strategy.equals("groups")) {
            throw new IllegalArgumentException("strategy must be 'flat' or 'groups', got: " + strategy);
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("at least one target server is required");
        }
        if (!jar.exists()) {
            throw new IllegalArgumentException("jar does not exist: " + jar);
        }
        return new DeployConfig(token, baseUrl, jar, targetDir, restart, verifyState, verifyTimeoutMs, rollback);
    }
}
