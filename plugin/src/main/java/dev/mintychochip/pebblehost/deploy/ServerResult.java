package dev.mintychochip.pebblehost.deploy;

public record ServerResult(String serverId, boolean success, String backupPath, String message) {
    public static ServerResult success(String serverId, String backupPath, boolean restarted) {
        return new ServerResult(serverId, true, backupPath, restarted ? "deployed and verified" : "deployed (no restart)");
    }
    public static ServerResult failure(String serverId, String message, String backupPath) {
        return new ServerResult(serverId, false, backupPath, message);
    }
}
