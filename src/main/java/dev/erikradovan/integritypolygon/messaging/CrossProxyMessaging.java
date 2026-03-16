package dev.erikradovan.integritypolygon.messaging;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Handles plugin channel messaging between the Velocity proxy and backend servers.
 * Registered as a service so modules can send messages to backend Paper/Spigot servers.
 */
public class CrossProxyMessaging {

    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("integritypolygon", "main");

    private final ProxyServer proxy;
    private final Object pluginInstance;
    private final Logger logger;
    private final Set<Consumer<String>> messageHandlers = new CopyOnWriteArraySet<>();

    public CrossProxyMessaging(ProxyServer proxy, Object pluginInstance, Logger logger) {
        this.proxy = proxy;
        this.pluginInstance = pluginInstance;
        this.logger = logger;

        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getEventManager().register(pluginInstance, this);
    }

    /**
     * Send a message to the server a specific player is connected to.
     */
    public void sendToPlayer(Player player, String message) {
        Optional<ServerConnection> server = player.getCurrentServer();
        server.ifPresent(conn -> conn.sendPluginMessage(CHANNEL, message.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Send a message to all backend servers (via connected players).
     */
    public void sendToAllServers(String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);

        Set<ServerConnection> uniqueServers = proxy.getAllPlayers().stream()
                .map(Player::getCurrentServer)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());

        uniqueServers.forEach(conn -> conn.sendPluginMessage(CHANNEL, data));
    }

    /**
     * Register a handler for incoming messages from backend servers.
     */
    public void onMessage(Consumer<String> handler) {
        messageHandlers.add(handler);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;

        // Only process messages from backend servers, not from clients
        if (!(event.getSource() instanceof ServerConnection)) return;

        // Mark as handled so the message is not forwarded to the player's client
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        String msg = new String(event.getData(), StandardCharsets.UTF_8);
        logger.debug("Received plugin message: {}", msg);

        for (Consumer<String> handler : messageHandlers) {
            try {
                handler.accept(msg);
            } catch (Exception e) {
                logger.error("Error in plugin message handler", e);
            }
        }
    }

    /**
     * Unregister the messaging channel (cleanup on shutdown).
     */
    public void shutdown() {
        proxy.getChannelRegistrar().unregister(CHANNEL);
        proxy.getEventManager().unregisterListener(pluginInstance, this);
        messageHandlers.clear();
    }
}

