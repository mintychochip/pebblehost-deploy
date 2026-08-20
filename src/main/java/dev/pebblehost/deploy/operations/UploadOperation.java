package dev.pebblehost.deploy.operations;

import dev.pebblehost.deploy.PebbleHostClient;

public class UploadOperation {
    private final PebbleHostClient client;

    public UploadOperation(PebbleHostClient client) { this.client = client; }

    public void push(String serverId, String localPath, String targetDir) {
        client.push(serverId, localPath, targetDir);
    }
}
