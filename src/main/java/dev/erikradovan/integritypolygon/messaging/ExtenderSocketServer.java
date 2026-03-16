package dev.erikradovan.integritypolygon.messaging;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Length-prefixed TCP server for Extender-to-Proxy communication.
 *
 * <h3>Wire Protocol</h3>
 * Each frame is: {@code [4-byte big-endian length][UTF-8 JSON bytes]}
 *
 * <h3>Handshake</h3>
 * <ol>
 *   <li>Extender sends auth frame:
 *       {@code {"type":"auth","secret":"…","extender_id":"<hash>","server_label":"lobby","version":"1.0.0"}}</li>
 *   <li>Proxy responds: {@code {"type":"auth_ok"}} or {@code {"type":"auth_fail","reason":"…"}}</li>
 *   <li>After auth, both sides freely exchange length-prefixed JSON frames</li>
 * </ol>
 *
 * <h3>Message Envelope (post-auth)</h3>
 * <pre>{@code
 * {
 *   "module": "server-monitor",
 *   "type":   "heartbeat",
 *   "source": "<extender-hash>",
 *   "server_label": "lobby",
 *   "payload": { … }
 * }
 * }</pre>
 *
 * Modules never interact with this class directly — they use
 * {@link dev.erikradovan.integritypolygon.api.ExtenderService}.
 */
public class ExtenderSocketServer {

    private final int port;
    private final String secret;
    private final Logger logger;
    private final Gson gson = new Gson();

    /**
     * Handler: receives (extenderId, JsonObject envelope) for every post-auth message.
     */
    private volatile BiConsumer<String, JsonObject> messageHandler;

    // extenderId (hash) -> connection
    private final ConcurrentHashMap<String, ExtenderConnection> connections = new ConcurrentHashMap<>();
    // extenderId -> server label (human-readable name)
    private final ConcurrentHashMap<String, String> serverLabels = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private ExecutorService executor;

    public ExtenderSocketServer(int port, String secret, Logger logger) {
        this.port = port;
        this.secret = secret;
        this.logger = logger;
    }

    /**
     * Register the single message handler. Called by ExtenderServiceImpl.
     */
    public void setMessageHandler(BiConsumer<String, JsonObject> handler) {
        this.messageHandler = handler;
    }

    public void start() {
        if (running) return;
        running = true;
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "IP-ExtenderSocket");
            t.setDaemon(true);
            return t;
        });

        executor.submit(() -> {
            try {
                serverSocket = new ServerSocket(port);
                logger.info("Extender socket listening on port {}", port);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.submit(() -> handleConnection(client));
                    } catch (SocketException e) {
                        if (running) logger.warn("Socket accept error: {}", e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to start extender socket on port {}: {}", port, e.getMessage());
            }
        });
    }

    public void stop() {
        running = false;
        connections.values().forEach(ExtenderConnection::close);
        connections.clear();
        serverLabels.clear();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        if (executor != null) executor.shutdownNow();
    }

    // ── Sending ─────────────────────────────────────────────────────

    /**
     * Send a length-prefixed JSON frame to a specific extender.
     */
    public void sendToExtender(String extenderId, JsonObject envelope) {
        ExtenderConnection conn = connections.get(extenderId);
        if (conn != null && conn.isAlive()) {
            conn.sendFrame(gson.toJson(envelope));
        }
    }

    /**
     * Send a length-prefixed JSON frame to ALL connected extenders.
     */
    public void sendToAll(JsonObject envelope) {
        String json = gson.toJson(envelope);
        connections.values().forEach(c -> {
            if (c.isAlive()) c.sendFrame(json);
        });
    }

    // ── Connection info ─────────────────────────────────────────────

    /**
     * @return identity hashes of all currently connected extenders
     */
    public Collection<String> getConnectedExtenders() {
        connections.entrySet().removeIf(e -> !e.getValue().isAlive());
        return Set.copyOf(connections.keySet());
    }

    /**
     * @return the human-readable server label for an extender hash, or the hash itself
     */
    public String getServerLabel(String extenderId) {
        return serverLabels.getOrDefault(extenderId, extenderId);
    }

    /**
     * @return all extenderId -> serverLabel mappings
     */
    public Map<String, String> getAllServerLabels() {
        return Map.copyOf(serverLabels);
    }

    // ── Connection handling ─────────────────────────────────────────

    private void handleConnection(Socket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        logger.debug("Extender connection from {}", remote);

        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // Step 1: Wait for auth frame (5s timeout)
            socket.setSoTimeout(5000);
            String authJson = readFrame(in);
            if (authJson == null) {
                logger.debug("Extender {} disconnected before auth", remote);
                return;
            }

            JsonObject authMsg;
            try {
                authMsg = JsonParser.parseString(authJson).getAsJsonObject();
            } catch (Exception e) {
                logger.warn("Invalid auth frame from {}", remote);
                writeFrame(out, "{\"type\":\"auth_fail\",\"reason\":\"Invalid JSON\"}");
                return;
            }

            String type = authMsg.has("type") ? authMsg.get("type").getAsString() : "";
            if (!"auth".equals(type)) {
                writeFrame(out, "{\"type\":\"auth_fail\",\"reason\":\"Expected auth\"}");
                return;
            }

            String providedSecret = authMsg.has("secret") ? authMsg.get("secret").getAsString() : "";
            if (!secret.equals(providedSecret)) {
                logger.warn("Extender auth failed from {} — bad secret", remote);
                writeFrame(out, "{\"type\":\"auth_fail\",\"reason\":\"Invalid secret\"}");
                return;
            }

            String extenderId = authMsg.has("extender_id") ? authMsg.get("extender_id").getAsString() : "";
            String serverLabel = authMsg.has("server_label") ? authMsg.get("server_label").getAsString() : "unknown";
            String version = authMsg.has("version") ? authMsg.get("version").getAsString() : "?";

            if (extenderId.isEmpty()) {
                writeFrame(out, "{\"type\":\"auth_fail\",\"reason\":\"Missing extender_id\"}");
                return;
            }

            // Auth OK
            writeFrame(out, "{\"type\":\"auth_ok\"}");
            socket.setSoTimeout(0);
            logger.info("Extender connected: {} [{}] (v{}) from {}",
                    serverLabel, extenderId.substring(0, Math.min(8, extenderId.length())), version, remote);

            serverLabels.put(extenderId, serverLabel);
            ExtenderConnection conn = new ExtenderConnection(extenderId, serverLabel, socket, out, logger);
            ExtenderConnection old = connections.put(extenderId, conn);
            if (old != null) old.close();

            // Dispatch synthetic announce message
            JsonObject announce = new JsonObject();
            announce.addProperty("module", "system");
            announce.addProperty("type", "extender_announce");
            announce.addProperty("source", extenderId);
            announce.addProperty("server_label", serverLabel);
            JsonObject announcePayload = new JsonObject();
            announcePayload.addProperty("version", version);
            announce.add("payload", announcePayload);
            dispatchMessage(extenderId, announce);

            // Step 2: Read frames forever
            while (running) {
                String frame = readFrame(in);
                if (frame == null) break; // EOF
                try {
                    JsonObject msg = JsonParser.parseString(frame).getAsJsonObject();
                    // Inject source/server_label if not present
                    if (!msg.has("source")) msg.addProperty("source", extenderId);
                    if (!msg.has("server_label")) msg.addProperty("server_label", serverLabel);
                    dispatchMessage(extenderId, msg);
                } catch (Exception e) {
                    logger.debug("Bad JSON frame from {}: {}", extenderId, e.getMessage());
                }
            }

        } catch (SocketTimeoutException e) {
            logger.debug("Extender {} timed out during auth", remote);
        } catch (IOException e) {
            if (running) logger.debug("Extender connection lost: {}", remote);
        } finally {
            connections.entrySet().removeIf(e -> !e.getValue().isAlive());
        }
    }

    private void dispatchMessage(String extenderId, JsonObject envelope) {
        BiConsumer<String, JsonObject> handler = this.messageHandler;
        if (handler == null) return;
        try {
            handler.accept(extenderId, envelope);
        } catch (Exception e) {
            logger.error("Error in extender message handler", e);
        }
    }

    // ── Length-prefixed framing ──────────────────────────────────────

    /**
     * Read a single frame: [4-byte big-endian length][UTF-8 bytes]
     * Returns null on EOF.
     */
    private String readFrame(DataInputStream in) throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (length <= 0 || length > 16 * 1024 * 1024) { // max 16 MB
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * Write a single frame: [4-byte big-endian length][UTF-8 bytes]
     */
    private void writeFrame(DataOutputStream out, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    // ── Extender Connection ─────────────────────────────────────────

    static class ExtenderConnection {
        final String extenderId;
        final String serverLabel;
        final Socket socket;
        final DataOutputStream output;
        final Logger logger;

        ExtenderConnection(String extenderId, String serverLabel, Socket socket,
                           DataOutputStream output, Logger logger) {
            this.extenderId = extenderId;
            this.serverLabel = serverLabel;
            this.socket = socket;
            this.output = output;
            this.logger = logger;
        }

        synchronized void sendFrame(String json) {
            try {
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                output.writeInt(data.length);
                output.write(data);
                output.flush();
            } catch (IOException e) {
                logger.debug("Failed to send frame to extender '{}': {}", extenderId, e.getMessage());
            }
        }

        boolean isAlive() {
            return socket != null && !socket.isClosed() && socket.isConnected();
        }

        void close() {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}

