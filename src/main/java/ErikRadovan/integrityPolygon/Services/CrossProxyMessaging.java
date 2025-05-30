package ErikRadovan.integrityPolygon.Services;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class CrossProxyMessaging {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("integritypolygon", "main");

    private final ProxyServer proxy;

    public CrossProxyMessaging(ProxyServer proxy) {
        this.proxy = proxy;

        // Register the plugin messaging channel
        proxy.getChannelRegistrar().register(CHANNEL);

        // Register this class to receive plugin messages
        proxy.getEventManager().register(this, this); // Can be your main plugin instance too
    }

    /**
     * Sends a message to the server the player is currently connected to.
     * @param player The player to send the message to.
     * @param message The message to send.
     *
     */
    public void message(Player player, String message) {
        Optional<ServerConnection> server = player.getCurrentServer();
        server.ifPresent(conn -> {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            conn.sendPluginMessage(CHANNEL, data);
        });
    }

    /**
     * Sends a message to all servers via connected players.
     * @param message The message to send.
     */
    public void messageAllServers(String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);

        // Collect unique server connections
        Set<ServerConnection> uniqueServers = proxy.getAllPlayers()
                .stream()
                .map(player -> player.getCurrentServer())
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());

        // Send message to each unique server
        uniqueServers.forEach(serverConn -> serverConn.sendPluginMessage(CHANNEL, data));
    }

    /**
     * Handles incoming messages from servers (optional).
     */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;

        String msg = new String(event.getData(), StandardCharsets.UTF_8);
        System.out.println("📩 Received plugin message: " + msg);

        // You can dispatch or handle the message here
    }

    /**
     * Unregisters the messaging channel (optional cleanup).
     */
    public void unregister() {
        proxy.getChannelRegistrar().unregister(CHANNEL);
    }
}
