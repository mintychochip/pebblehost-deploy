package dev.mintychochip.pebblehost.deploy.operations;

import dev.mintychochip.pebblehost.deploy.PebbleHostClient;

public class VerifyOperation {
    private static final long POLL_MS = 3000;
    private final PebbleHostClient client;

    public VerifyOperation(PebbleHostClient client) { this.client = client; }

    public boolean verify(String serverId, String desiredState, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (desiredState.equals(client.currentState(serverId))) return true;
            Thread.sleep(POLL_MS);
        }
        return desiredState.equals(client.currentState(serverId));
    }
}
