package dev.pebblehost.deploy.operations;

import dev.pebblehost.deploy.PebbleHostClient;

public class RollbackOperation {
    private final PebbleHostClient client;

    public RollbackOperation(PebbleHostClient client) { this.client = client; }

    public void restore(String serverId, String targetDir, String jarName, String backupPath,
                        boolean restart, String verifyState, long verifyTimeoutMs) throws InterruptedException {
        if (backupPath == null) {
            throw new IllegalStateException("cannot restore: no backup exists for server " + serverId);
        }
        // Delete the bad jar first so the rename cannot collide/overwrite a live file.
        client.delete(serverId, targetDir + "/" + jarName);
        client.rename(serverId, backupPath, targetDir + "/" + jarName);
        if (restart) {
            client.power(serverId, "restart");
            boolean ok = new VerifyOperation(client).verify(serverId, verifyState, verifyTimeoutMs);
            if (!ok) {
                throw new IllegalStateException(
                    "rollback restore of " + serverId + " did not reach state '" + verifyState
                        + "' within " + verifyTimeoutMs + "ms; manual intervention required");
            }
        }
    }
}
