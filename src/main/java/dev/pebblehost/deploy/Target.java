package dev.pebblehost.deploy;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

public class Target {
    private final Property<String> serverId;
    private final Property<String> group;
    private final Property<Boolean> restart;

    @Inject
    public Target(ObjectFactory objects) {
        this.serverId = objects.property(String.class);
        this.group = objects.property(String.class).convention("default");
        this.restart = objects.property(Boolean.class).convention(true);
    }

    public Property<String> getServerId() { return serverId; }
    public Property<String> getGroup() { return group; }
    public Property<Boolean> getRestart() { return restart; }
}
