package dev.mintychochip.pebblehost.deploy.operations;

import dev.mintychochip.pebblehost.deploy.PebbleHostClient;

public class PowerOperation {
    private final PebbleHostClient client;

    public PowerOperation(PebbleHostClient client) { this.client = client; }

    public void restart(String serverId) {
        client.power(serverId, "restart");
    }
}
