package dev.pebblehost.test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Test plugin for the PebbleHost deploy plugin.
 *
 * The {@code /phdeploy info} command reports the running plugin version and a
 * deploy marker. After a rollout, this is the human/verification check that the
 * newly uploaded jar is actually the one loaded: a stale jar reports a stale
 * version, a fresh one reports the marker matching the deploy.
 */
public final class PebbleHostTestPlugin extends JavaPlugin {

    /** Bumped by the deploy pipeline to prove a fresh jar is live. */
    private static final String DEPLOY_MARKER = "deploy-26.2-001";

    @Override
    public void onEnable() {
        getLogger().info("PebbleHostTest enabled (marker " + DEPLOY_MARKER + ")");
    }

    @Override
    public void onDisable() {
        getLogger().info("PebbleHostTest disabled");
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("phdeploy")) {
            return false;
        }
        Component message = Component.text("PebbleHostTest ")
                .append(Component.text(getPluginMeta().getVersion(), NamedTextColor.GREEN))
                .append(Component.text(" | marker "))
                .append(Component.text(DEPLOY_MARKER, NamedTextColor.AQUA))
                .append(Component.text(" | api-version ")
                        .append(Component.text(getPluginMeta().getAPIVersion(), NamedTextColor.YELLOW)));
        sender.sendMessage(message);
        return true;
    }
}
