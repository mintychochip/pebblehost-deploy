package dev.mintychochip.pebblehost.deploy.operations;

import dev.mintychochip.pebblehost.deploy.PebbleHostClient;

import java.util.List;

public class BackupOperation {
    private final PebbleHostClient client;

    public BackupOperation(PebbleHostClient client) { this.client = client; }

    /** Returns the versioned backup path created, or null if the jar did not exist remotely. */
    public String backup(String serverId, String targetDir, String jarName, String timestamp) {
        List<String> files = client.listFiles(serverId, targetDir);
        if (!files.contains(jarName)) return null;
        String backupPath = targetDir + "/" + jarName + "-deploy-" + timestamp + ".bak";
        client.rename(serverId, targetDir + "/" + jarName, backupPath);
        return backupPath;
    }
}
