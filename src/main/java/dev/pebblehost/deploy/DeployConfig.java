package dev.pebblehost.deploy;

import java.io.File;

public record DeployConfig(
        String token, String baseUrl, File jar, String targetDir,
        boolean restart, String verifyState, long verifyTimeoutMs, String rollback) {

    public static DeployConfig from(String token, String baseUrl, File jar, String targetDir,
                                    boolean restart, String verifyState, long verifyTimeoutMs, String rollback) {
        if (!rollback.equals("abort") && !rollback.equals("restore")) {
            throw new IllegalArgumentException("rollback must be 'abort' or 'restore', got: " + rollback);
        }
        if (!jar.exists()) {
            throw new IllegalArgumentException("jar does not exist: " + jar);
        }
        return new DeployConfig(token, baseUrl, jar, targetDir, restart, verifyState, verifyTimeoutMs, rollback);
    }
}
