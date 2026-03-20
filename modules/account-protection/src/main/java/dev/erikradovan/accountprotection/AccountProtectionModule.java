package dev.erikradovan.accountprotection;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.erikradovan.integritypolygon.api.ModuleContext;
import dev.erikradovan.integritypolygon.api.ModuleConfigOption;
import dev.erikradovan.integritypolygon.api.ModuleConfigStore;
import dev.erikradovan.integritypolygon.api.ModuleDashboard;
import dev.erikradovan.integritypolygon.api.ModuleDashboard.RequestContext;
import dev.erikradovan.integritypolygon.api.ModuleStorage;
import dev.erikradovan.integritypolygon.api.ServiceRegistry;
import dev.erikradovan.integritypolygon.logging.LogManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.security.InvalidKeyException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
public class AccountProtectionModule implements dev.erikradovan.integritypolygon.api.Module {
    private ModuleContext context;
    private Logger logger;
    private final Gson gson = new Gson();
    // TOTP generator (RFC 6238, 6-digit, 30s step)
    private TimeBasedOneTimePasswordGenerator totp;
    // Staff registry: username -> StaffEntry
    private final ConcurrentHashMap<String, StaffEntry> staff = new ConcurrentHashMap<>();
    // Active sessions: player UUID -> session IP
    private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();
    // Players awaiting 2FA verification: UUID -> expected IP
    private final Set<String> pendingVerification = ConcurrentHashMap.newKeySet();
    // Config
    private volatile boolean enableSessionLocking = true;
    private volatile boolean enable2fa = true;
    // Services
    private ModuleConfigStore configStore;
    private ModuleStorage storage;
    private ProxyServer proxyServer;
    private LogManager logManager;
    private dev.erikradovan.integritypolygon.api.ExtenderService extenderService;
    @Override
    public void onEnable(ModuleContext ctx) {
        this.context = ctx;
        this.logger = ctx.getLogger();
        this.configStore = ctx.getConfigStore();
        this.storage = ctx.getStorage();
        ServiceRegistry reg = ctx.getServiceRegistry();
        this.proxyServer = reg.get(ProxyServer.class).orElse(null);
        this.logManager = reg.get(LogManager.class).orElse(null);
        this.extenderService = reg.get(dev.erikradovan.integritypolygon.api.ExtenderService.class).orElse(null);
        totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30));

        ensureStorage();
        loadStaffRegistry();
        loadConfig();
        ctx.getEventManager().subscribe(new AuthListener());
        registerCommand();
        registerDashboard();

        if (extenderService != null) {
            extenderService.onMessage("account-protection", "event:verify_2fa", msg -> {
                JsonObject data = msg.payload().has("data") ? msg.payload().getAsJsonObject("data") : msg.payload();
                String uuid = data.has("uuid") ? data.get("uuid").getAsString() : "";
                String code = data.has("code") ? data.get("code").getAsString() : "";
                String name = data.has("player") ? data.get("player").getAsString() : "";

                if (uuid.isEmpty() || code.isEmpty() || name.isEmpty() || proxyServer == null) return;
                StaffEntry entry = staff.get(name.toLowerCase());
                if (entry == null || !pendingVerification.contains(uuid)) return;

                Optional<Player> optPlayer = proxyServer.getPlayer(java.util.UUID.fromString(uuid));
                if (verifyCode(entry.secretBase32, code)) {
                    pendingVerification.remove(uuid);
                    if (optPlayer.isPresent()) {
                        Player player = optPlayer.get();
                        String ip = player.getRemoteAddress().getAddress().getHostAddress();
                        entry.lastIp = ip;
                        entry.lastLogin = System.currentTimeMillis();
                        sessions.put(uuid, ip);

                        // Notify extender to unfreeze the player
                        extenderService.sendCommand("account-protection", uuid, Map.of(
                                "action", "2fa_verified",
                                "player", player.getUsername()
                        ));

                        if (!entry.enrolled) {
                            entry.enrolled = true;
                            saveStaffRegistry();
                            player.sendMessage(Component.text("2FA enrollment complete! Your account is now protected.").color(NamedTextColor.GREEN));
                            log("INFO", "2FA", "Enrollment completed for " + name + " from IP " + ip);
                        } else {
                            saveStaffRegistry();
                            player.sendMessage(Component.text("2FA verified. Welcome back!").color(NamedTextColor.GREEN));
                            log("INFO", "2FA", "Verified " + name + " from IP " + ip);
                        }
                    }
                } else {
                    optPlayer.ifPresent(p -> p.sendMessage(Component.text("Invalid code. Try again.").color(NamedTextColor.RED)));
                    log("WARN", "2FA", "Failed verification attempt via dialog for " + name);

                    // Re-trigger the dialog immediately
                    extenderService.sendCommand("account-protection", uuid, Map.of(
                            "action", "show_2fa_prompt",
                            "player", name,
                            "uuid", uuid,
                            "timeout", 60
                    ));
                }
            });
        }

        log("INFO", "LIFECYCLE", "Account Protection enabled [" + staff.size() + " staff, session_lock=" + enableSessionLocking + ", 2fa=" + enable2fa + "]");
        logger.info("Account Protection enabled [{} staff enrolled, session_lock={}, 2fa={}]",
                staff.size(), enableSessionLocking, enable2fa);
    }
    @Override
    public void onDisable() {
        saveStaffRegistry();
        if (proxyServer != null) {
            proxyServer.getCommandManager().unregister("2fa");
        }
        log("INFO", "LIFECYCLE", "Account Protection disabled");
        logger.info("Account Protection disabled");
    }
    @Override
    public void onReload() {
        loadConfig();
        loadStaffRegistry();
        log("INFO", "LIFECYCLE", "Account Protection configuration reloaded");
    }

    private void log(String level, String tag, String message) {
        if (logManager != null) logManager.log("account-protection", level, tag, message);
    }
    // ================================================================
    //  STAFF ENTRY
    // ================================================================
    static class StaffEntry {
        String username;
        String secretBase32;
        boolean enrolled;        // true = completed enrollment (scanned QR, verified code)
        String lastIp;
        long lastLogin;
        List<String> trustedIps; // IPs that don't require 2FA
        StaffEntry(String username, String secretBase32) {
            this.username = username;
            this.secretBase32 = secretBase32;
            this.enrolled = false;
            this.trustedIps = new ArrayList<>();
        }
    }
    // ================================================================
    //  CONFIG
    // ================================================================
    private void loadConfig() {
        if (configStore == null) return;
        configStore.registerOptions(List.of(
                ModuleConfigOption.bool("enable_session_locking", true, false, "Lock staff sessions to last trusted IP."),
                ModuleConfigOption.bool("enable_2fa", true, false, "Require TOTP verification for staff logins.")
        ));
        enableSessionLocking = configStore.getBoolean("enable_session_locking", true);
        enable2fa = configStore.getBoolean("enable_2fa", true);
    }
    // ================================================================
    //  STAFF REGISTRY PERSISTENCE
    // ================================================================
    private void loadStaffRegistry() {
        if (storage == null) return;
        staff.clear();
        try {
            String table = storage.qualifyTable("staff_registry");
            storage.query("SELECT username, data_json FROM " + table, rs -> {
                while (rs.next()) {
                    String username = rs.getString("username");
                    String dataJson = rs.getString("data_json");
                    StaffEntry entry = gson.fromJson(dataJson, StaffEntry.class);
                    if (entry != null && username != null) {
                        staff.put(username, entry);
                    }
                }
            });
            logger.info("Loaded {} staff accounts", staff.size());
        } catch (Exception e) {
            logger.warn("Failed to load staff registry: {}", e.getMessage());
        }
    }
    private void saveStaffRegistry() {
        if (storage == null) return;
        try {
            String table = storage.qualifyTable("staff_registry");
            storage.update("DELETE FROM " + table);
            for (Map.Entry<String, StaffEntry> entry : staff.entrySet()) {
                storage.update("INSERT INTO " + table + " (username, data_json) VALUES (?, ?)",
                        entry.getKey(), gson.toJson(entry.getValue()));
            }
        } catch (Exception e) {
            logger.warn("Failed to save staff registry: {}", e.getMessage());
        }
    }

    private void ensureStorage() {
        if (storage == null) return;
        storage.ensureTable("staff_registry", "(username TEXT PRIMARY KEY, data_json TEXT NOT NULL)");
    }
    // ================================================================
    //  TOTP LOGIC
    // ================================================================
    private String generateSecret() {
        byte[] keyBytes = new byte[20]; // 160 bits, standard for TOTP
        new SecureRandom().nextBytes(keyBytes);
        return Base32.encode(keyBytes);
    }
    private boolean verifyCode(String secretBase32, String code) {
        try {
            byte[] keyBytes = Base32.decode(secretBase32);
            SecretKey key = new SecretKeySpec(keyBytes, totp.getAlgorithm());
            Instant now = Instant.now();
            // Check current window and +/- 1 for clock drift
            for (int offset = -1; offset <= 1; offset++) {
                Instant check = now.plus(Duration.ofSeconds(30L * offset));
                int expected = totp.generateOneTimePassword(key, check);
                if (String.format("%06d", expected).equals(code)) return true;
            }
        } catch (InvalidKeyException e) {
            logger.warn("Invalid TOTP key for verification: {}", e.getMessage());
        }
        return false;
    }
    private String buildOtpAuthUri(String username, String secret) {
        return "otpauth://totp/IntegrityPolygon:" + username
                + "?secret=" + secret
                + "&issuer=IntegrityPolygon"
                + "&algorithm=SHA1&digits=6&period=30";
    }
    // ================================================================
    //  EVENT LISTENER
    // ================================================================
    public class AuthListener {
        @Subscribe
        public void onLogin(LoginEvent event) {
            if (!enable2fa && !enableSessionLocking) return;
            Player player = event.getPlayer();
            String name = player.getUsername().toLowerCase();
            String ip = player.getRemoteAddress().getAddress().getHostAddress();
            String uuid = player.getUniqueId().toString();
            StaffEntry entry = staff.get(name);
            if (entry == null) return;

            // Staff who haven't completed initial enrollment — let them through
            // (they need to join and run /2fa <code> to complete enrollment)
            if (!entry.enrolled) return;

            // Fully enrolled staff: always require 2FA verification on login
            if (enable2fa) {
                // Check if same IP and session locking allows bypass
                if (enableSessionLocking && entry.lastIp != null && entry.lastIp.equals(ip)) {
                    // Same IP - allow without 2FA
                    entry.lastLogin = System.currentTimeMillis();
                    sessions.put(uuid, ip);
                    return;
                }
                // Check trusted IPs
                if (entry.trustedIps != null && entry.trustedIps.contains(ip)) {
                    entry.lastIp = ip;
                    entry.lastLogin = System.currentTimeMillis();
                    sessions.put(uuid, ip);
                    return;
                }
                // Require 2FA
                pendingVerification.add(uuid);
                logger.info("2FA required for {} (IP: {}, prev: {})", name, ip, entry.lastIp);
                log("INFO", "2FA", "2FA challenge for " + name + " from IP " + ip);
            } else if (enableSessionLocking && entry.lastIp != null && !entry.lastIp.equals(ip)) {
                // 2FA off but session locking on: deny if IP changed
                if (entry.trustedIps != null && entry.trustedIps.contains(ip)) {
                    entry.lastIp = ip;
                    entry.lastLogin = System.currentTimeMillis();
                    sessions.put(uuid, ip);
                    return;
                }
                event.setResult(ResultedEvent.ComponentResult.denied(
                        Component.text("Session locked: login from new IP requires 2FA.").color(NamedTextColor.RED)));
                logger.warn("Session lock denied {} from IP {} (prev: {})", name, ip, entry.lastIp);
                log("WARN", "SESSION", "Denied " + name + " - session locked (IP: " + ip + ", prev: " + entry.lastIp + ")");
                return;
            } else {
                // No 2FA, no session lock trigger - allow
                entry.lastIp = ip;
                entry.lastLogin = System.currentTimeMillis();
                sessions.put(uuid, ip);
            }
        }
        @Subscribe
        public void onServerPreConnect(ServerPreConnectEvent event) {
            Player player = event.getPlayer();
            String uuid = player.getUniqueId().toString();
            // Only send the visual prompt if they're still pending 2FA authentication
            if (pendingVerification.contains(uuid)) {
                if (extenderService != null) {
                    String extenderId = resolveExtenderForTarget(event);
                    if (extenderId != null) {
                        JsonObject payload = new JsonObject();
                        payload.addProperty("action", "show_2fa_prompt");
                        payload.addProperty("player", player.getUsername());
                        payload.addProperty("uuid", uuid);
                        payload.addProperty("timeout", 60);
                        extenderService.sendMessage("account-protection", extenderId, "command", payload);
                    } else {
                        // Do not allow bypassing pending 2FA when no mapped extender is available.
                        event.setResult(ServerPreConnectEvent.ServerResult.denied());
                        player.sendMessage(Component.text("2FA requires an extender on the target server.").color(NamedTextColor.RED));
                        player.sendMessage(Component.text("Contact an administrator or use /2fa after extender mapping is fixed.").color(NamedTextColor.YELLOW));
                        log("WARN", "2FA", "Denied pre-connect for " + player.getUsername() + " due to missing mapped extender");
                    }
                }
            }
        }
    }

    private String resolveExtenderForTarget(ServerPreConnectEvent event) {
        if (extenderService == null || event.getResult() == null) {
            return null;
        }

        var targetServer = event.getResult().getServer().orElse(null);
        if (targetServer == null) {
            return null;
        }

        return resolveExtenderForTarget(targetServer);
    }

    private String resolveExtenderForTarget(com.velocitypowered.api.proxy.server.RegisteredServer targetServer) {
        if (extenderService == null || targetServer == null) {
            return null;
        }

        String host = targetServer.getServerInfo().getAddress().getHostString();
        int port = targetServer.getServerInfo().getAddress().getPort();
        Set<String> targetHostVariants = buildHostVariants(host);

        for (Map.Entry<String, Map<String, Object>> entry : extenderService.getServerStates().entrySet()) {
            Map<String, Object> state = entry.getValue();
            String stateIp = String.valueOf(state.getOrDefault("server_ip", "")).trim();
            int statePort = state.get("server_port") instanceof Number n ? n.intValue() : 0;
            if (port != statePort) {
                continue;
            }
            Set<String> stateVariants = buildHostVariants(stateIp);
            boolean hostMatch = !Collections.disjoint(targetHostVariants, stateVariants);
            if (hostMatch) {
                return entry.getKey();
            }
        }

        if (extenderService.getServerStates().size() == 1) {
            return extenderService.getServerStates().keySet().iterator().next();
        }
        return null;
    }

    private Set<String> buildHostVariants(String host) {
        Set<String> variants = new HashSet<>();
        if (host == null || host.isBlank()) {
            return variants;
        }
        String trimmed = host.trim().toLowerCase(Locale.ROOT);
        variants.add(trimmed);
        try {
            InetAddress addr = InetAddress.getByName(trimmed);
            variants.add(addr.getHostAddress().toLowerCase(Locale.ROOT));
            variants.add(addr.getHostName().toLowerCase(Locale.ROOT));
            variants.add(addr.getCanonicalHostName().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
        }
        return variants;
    }
    // ================================================================
    //  /2fa COMMAND
    // ================================================================
    private void registerCommand() {
        if (proxyServer == null) return;
        proxyServer.getCommandManager().register("2fa", new TwoFaCommand());
    }
    class TwoFaCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player player)) {
                invocation.source().sendMessage(Component.text("Players only."));
                return;
            }
            String uuid = player.getUniqueId().toString();
            String name = player.getUsername().toLowerCase();
            String[] args = invocation.arguments();
            if (args.length != 1) {
                player.sendMessage(Component.text("Usage: /2fa <code>").color(NamedTextColor.YELLOW));
                return;
            }
            StaffEntry entry = staff.get(name);
            if (entry == null) {
                player.sendMessage(Component.text("You are not enrolled in 2FA.").color(NamedTextColor.RED));
                return;
            }
            String code = args[0].trim();
            if (!code.matches("\\d{6}")) {
                player.sendMessage(Component.text("Invalid code. Enter 6 digits.").color(NamedTextColor.RED));
                return;
            }
            if (verifyCode(entry.secretBase32, code)) {
                pendingVerification.remove(uuid);
                String ip = player.getRemoteAddress().getAddress().getHostAddress();
                entry.lastIp = ip;
                entry.lastLogin = System.currentTimeMillis();
                sessions.put(uuid, ip);

                // Notify extender to unfreeze the player
                if (extenderService != null) {
                    extenderService.sendCommand("account-protection", uuid, Map.of(
                            "action", "2fa_verified",
                            "player", player.getUsername()
                    ));
                }

                if (!entry.enrolled) {
                    entry.enrolled = true;
                    saveStaffRegistry();
                    player.sendMessage(Component.text("2FA enrollment complete! Your account is now protected.").color(NamedTextColor.GREEN));
                    log("INFO", "2FA", "Enrollment completed for " + name + " from IP " + ip);
                } else {
                    saveStaffRegistry();
                    player.sendMessage(Component.text("2FA verified. Welcome back!").color(NamedTextColor.GREEN));
                    log("INFO", "2FA", "Verified " + name + " from IP " + ip);
                }
            } else {
                player.sendMessage(Component.text("Invalid code. Try again.").color(NamedTextColor.RED));
                log("WARN", "2FA", "Failed verification attempt for " + name);
            }
        }
    }
    // ================================================================
    //  DASHBOARD
    // ================================================================
    private void registerDashboard() {
        ModuleDashboard d = context.getDashboard();
        d.get("status", this::apiStatus);
        d.get("staff", this::apiListStaff);
        d.post("staff/enroll", this::apiEnroll);
        d.post("staff/remove", this::apiRemove);
        d.post("staff/trust-ip", this::apiTrustIp);
        d.get("config", this::apiGetConfig);
        d.post("config", this::apiSaveConfig);
    }
    private void apiStatus(RequestContext ctx) {
        ctx.json(Map.of(
                "enrolled_staff", staff.size(),
                "active_sessions", sessions.size(),
                "pending_verification", pendingVerification.size(),
                "session_locking", enableSessionLocking,
                "totp_enabled", enable2fa
        ));
    }
    private void apiListStaff(RequestContext ctx) {
        List<Map<String, Object>> list = new ArrayList<>();
        staff.forEach((name, entry) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", entry.username);
            m.put("enrolled", entry.enrolled);
            m.put("last_ip", entry.lastIp);
            m.put("last_login", entry.lastLogin);
            m.put("trusted_ips", entry.trustedIps != null ? entry.trustedIps : List.of());
            list.add(m);
        });
        ctx.json(Map.of("staff", list));
    }
    private void apiEnroll(RequestContext ctx) {
        try {
            JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
            String username = b.has("username") ? b.get("username").getAsString().trim().toLowerCase() : "";
            if (username.isBlank()) { ctx.status(400).json(Map.of("error", "Username required")); return; }
            if (staff.containsKey(username)) {
                ctx.status(400).json(Map.of("error", "Already enrolled"));
                return;
            }
            String secret = generateSecret();
            StaffEntry entry = new StaffEntry(username, secret);
            staff.put(username, entry);
            saveStaffRegistry();
            String otpUri = buildOtpAuthUri(username, secret);
            ctx.json(Map.of(
                    "success", true,
                    "username", username,
                    "secret", secret,
                    "otp_uri", otpUri,
                    "message", "Staff must verify with /2fa <code> on first login to complete enrollment."
            ));
            logger.info("Enrolled staff: {} (pending first verification)", username);
            log("INFO", "ENROLL", "Enrolled staff " + username + " (pending verification)");
        } catch (Exception e) {
            logger.error("Failed to enroll staff", e);
            log("ERROR", "ENROLL", "Failed to enroll: " + e.getMessage());
            ctx.status(500).json(Map.of("error", "Enrollment failed: " + e.getMessage()));
        }
    }
    private void apiRemove(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String username = b.has("username") ? b.get("username").getAsString().trim().toLowerCase() : "";
        if (staff.remove(username) != null) {
            saveStaffRegistry();
            log("INFO", "STAFF", "Removed staff member: " + username);
            ctx.json(Map.of("success", true));
        } else {
            ctx.status(404).json(Map.of("error", "Not found"));
        }
    }
    private void apiTrustIp(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String username = b.has("username") ? b.get("username").getAsString().trim().toLowerCase() : "";
        String ip = b.has("ip") ? b.get("ip").getAsString().trim() : "";
        StaffEntry entry = staff.get(username);
        if (entry == null) { ctx.status(404).json(Map.of("error", "Staff not found")); return; }
        if (ip.isBlank()) { ctx.status(400).json(Map.of("error", "IP required")); return; }
        if (entry.trustedIps == null) entry.trustedIps = new ArrayList<>();
        if (!entry.trustedIps.contains(ip)) entry.trustedIps.add(ip);
        saveStaffRegistry();
        log("INFO", "TRUST", "Added trusted IP " + ip + " for " + username);
        ctx.json(Map.of("success", true));
    }
    private void apiGetConfig(RequestContext ctx) {
        ctx.json(Map.of("enable_session_locking", enableSessionLocking, "enable_2fa", enable2fa));
    }
    private void apiSaveConfig(RequestContext ctx) {
        if (configStore == null) { ctx.status(500).json(Map.of("error", "Config unavailable")); return; }
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        if (b.has("enable_session_locking")) configStore.set("enable_session_locking", b.get("enable_session_locking").getAsBoolean());
        if (b.has("enable_2fa")) configStore.set("enable_2fa", b.get("enable_2fa").getAsBoolean());
        loadConfig();
        log("INFO", "CONFIG", "Configuration updated via dashboard");
        ctx.json(Map.of("success", true));
    }
    // ================================================================
    //  BASE32 ENCODER/DECODER (RFC 4648)
    // ================================================================
    static class Base32 {
        private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        static String encode(byte[] data) {
            StringBuilder sb = new StringBuilder();
            int i = 0, index = 0, digit;
            int currByte, nextByte;
            while (i < data.length) {
                currByte = data[i] & 0xFF;
                if (index > 3) {
                    if ((i + 1) < data.length) nextByte = data[i + 1] & 0xFF;
                    else nextByte = 0;
                    digit = currByte & (0xFF >> index);
                    index = (index + 5) % 8;
                    digit <<= index;
                    digit |= nextByte >> (8 - index);
                    i++;
                } else {
                    digit = (currByte >> (8 - (index + 5))) & 0x1F;
                    index = (index + 5) % 8;
                    if (index == 0) i++;
                }
                sb.append(CHARS.charAt(digit));
            }
            return sb.toString();
        }
        static byte[] decode(String base32) {
            String s = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
            int numBytes = s.length() * 5 / 8;
            byte[] result = new byte[numBytes];
            int buffer = 0, bitsLeft = 0, idx = 0;
            for (char c : s.toCharArray()) {
                int val = CHARS.indexOf(c);
                if (val < 0) continue;
                buffer = (buffer << 5) | val;
                bitsLeft += 5;
                if (bitsLeft >= 8) {
                    bitsLeft -= 8;
                    result[idx++] = (byte)(buffer >> bitsLeft);
                }
            }
            return result;
        }
    }
}