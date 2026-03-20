package dev.erikradovan.antibot;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.erikradovan.integritypolygon.api.ModuleContext;
import dev.erikradovan.integritypolygon.api.ModuleDashboard;
import dev.erikradovan.integritypolygon.api.ModuleDashboard.RequestContext;
import dev.erikradovan.integritypolygon.api.ServiceRegistry;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.logging.LogManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/**
 * Anti-Bot Module - comprehensive bot detection and prevention.
 *
 * Detection methods:
 *   - Connection rate limiting per IP (max conn/sec per IP, global threshold)
 *   - Connection cooldown enforcement
 *   - Spam login attempt detection (rapid re-logins from same IP/username)
 *   - Flood join detection (burst of unique IPs)
 *   - Bot name heuristics (regex patterns, name entropy)
 *   - Handshake validation (protocol version, hostname checks)
 *   - SYN flood detection (connections that never complete login)
 *   - Dynamic blocking (threat score escalation with increasing ban duration)
 *   - Lockdown mode (only known players during attack)
 *   - IP whitelist
 */
public class AntiBotModule implements dev.erikradovan.integritypolygon.api.Module {
    private ModuleContext context;
    private Logger logger;
    private final Gson gson = new Gson();
    // -- Feature toggles --
    private volatile boolean enableRateLimit = true;
    private volatile boolean enableCooldown = true;
    private volatile boolean enableSpamDetection = true;
    private volatile boolean enableFloodDetection = true;
    private volatile boolean enableNameFilter = true;
    private volatile boolean enableHandshakeValidation = true;
    private volatile boolean enableSynFloodDetection = true;
    private volatile boolean enableDynamicBlocking = true;
    private volatile boolean enableLockdown = true;
    // -- Config values --
    private volatile int maxConnPerIp = 3;
    private volatile int connWindowSec = 60;
    private volatile int cooldownMs = 2000;
    private volatile int globalJoinThreshold = 15;
    private volatile int attackCooldownSec = 30;
    private volatile String botNameRegex = "^(Bot|Player|User|Test)_?\\\\d{3,}";
    private volatile double nameEntropyThreshold = 1.5;
    private volatile int synTimeoutMs = 5000;
    private volatile int dynamicBlockBaseSec = 60;
    private volatile int dynamicBlockMaxSec = 3600;
    private volatile int threatScoreThreshold = 5;
    private volatile int minProtocolVersion = 47;    // MC 1.8
    private volatile int maxProtocolVersion = 99999; // effectively unlimited
    private volatile String kickMessage = "Connection denied. Please try again later.";
    private volatile String lockdownMessage = "Server under attack. Only known players may join.";
    // -- State --
    private final ConcurrentHashMap<String, Deque<Long>> ipConnTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ipLastConn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> ipThreatScore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> dynamicBans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> banEscalation = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Long> globalJoinTs = new ConcurrentLinkedDeque<>();
    private final Set<String> pendingHandshakes = ConcurrentHashMap.newKeySet();
    private final Set<String> knownPlayers = ConcurrentHashMap.newKeySet();
    private final Set<String> whitelistedIps = ConcurrentHashMap.newKeySet();
    private volatile boolean underAttack = false;
    private volatile Instant attackStart = null;
    private volatile Instant lastBotSeen = null;
    // -- Stats --
    private final AtomicLong totalBlocked = new AtomicLong(0);
    private final AtomicLong totalAllowed = new AtomicLong(0);
    private final AtomicLong totalAttacks = new AtomicLong(0);
    private final AtomicLong synFloodsDetected = new AtomicLong(0);
    private final AtomicInteger currentJoinRate = new AtomicInteger(0);
    private final List<Integer> rateHistory = Collections.synchronizedList(new ArrayList<>());
    // -- Services --
    private ConfigManager configManager;
    private LogManager logManager;
    @Override
    public void onEnable(ModuleContext ctx) {
        this.context = ctx;
        this.logger = ctx.getLogger();
        ServiceRegistry reg = ctx.getServiceRegistry();
        this.configManager = reg.get(ConfigManager.class).orElse(null);
        this.logManager = reg.get(LogManager.class).orElse(null);
        loadConfig();
        loadPersistentData();
        ctx.getEventManager().subscribe(new BotListener());
        registerDashboard();
        startAnalysisLoop();
        log("INFO", "LIFECYCLE", "Anti-Bot enabled [rate=" + maxConnPerIp + "/ip/" + connWindowSec + "s, flood=" + globalJoinThreshold + "/s, cooldown=" + cooldownMs + "ms]");
        logger.info("Anti-Bot enabled [rate={}/ip/{}s, flood={}/s, cooldown={}ms, lockdown={}]",
                maxConnPerIp, connWindowSec, globalJoinThreshold, cooldownMs, enableLockdown);
    }
    @Override
    public void onDisable() {
        savePersistentData();
        log("INFO", "LIFECYCLE", "Anti-Bot disabled [" + totalBlocked.get() + " blocked, " + totalAllowed.get() + " allowed, " + totalAttacks.get() + " attacks]");
        logger.info("Anti-Bot disabled [{} blocked, {} allowed, {} attacks]",
                totalBlocked.get(), totalAllowed.get(), totalAttacks.get());
    }
    @Override
    public void onReload() {
        loadConfig();
        log("INFO", "LIFECYCLE", "Anti-Bot configuration reloaded");
    }

    private void log(String level, String tag, String message) {
        if (logManager != null) logManager.log("anti-bot", level, tag, message);
    }
    // ================================================================
    //  CONFIGURATION
    // ================================================================
    @SuppressWarnings("unchecked")
    private void loadConfig() {
        if (configManager == null) return;
        String id = context.getDescriptor().id();
        Map<String, Object> cfg = configManager.getModuleConfig(id);
        if (cfg.isEmpty()) { saveDefaultConfig(); cfg = configManager.getModuleConfig(id); }
        enableRateLimit = bool(cfg, "enable_rate_limit", true);
        enableCooldown = bool(cfg, "enable_cooldown", true);
        enableSpamDetection = bool(cfg, "enable_spam_detection", true);
        enableFloodDetection = bool(cfg, "enable_flood_detection", true);
        enableNameFilter = bool(cfg, "enable_name_filter", true);
        enableHandshakeValidation = bool(cfg, "enable_handshake_validation", true);
        enableSynFloodDetection = bool(cfg, "enable_syn_flood_detection", true);
        enableDynamicBlocking = bool(cfg, "enable_dynamic_blocking", true);
        enableLockdown = bool(cfg, "enable_lockdown", true);
        maxConnPerIp = num(cfg, "max_conn_per_ip", 3);
        connWindowSec = num(cfg, "conn_window_sec", 60);
        cooldownMs = num(cfg, "cooldown_ms", 2000);
        globalJoinThreshold = num(cfg, "global_join_threshold", 15);
        attackCooldownSec = num(cfg, "attack_cooldown_sec", 30);
        botNameRegex = str(cfg, "bot_name_regex", botNameRegex);
        nameEntropyThreshold = dbl(cfg, "name_entropy_threshold", 1.5);
        synTimeoutMs = num(cfg, "syn_timeout_ms", 5000);
        dynamicBlockBaseSec = num(cfg, "dynamic_block_base_sec", 60);
        dynamicBlockMaxSec = num(cfg, "dynamic_block_max_sec", 3600);
        threatScoreThreshold = num(cfg, "threat_score_threshold", 5);
        minProtocolVersion = num(cfg, "min_protocol_version", 47);
        maxProtocolVersion = num(cfg, "max_protocol_version", 99999);
        kickMessage = str(cfg, "kick_message", kickMessage);
        lockdownMessage = str(cfg, "lockdown_message", lockdownMessage);
        Object wl = cfg.get("whitelisted_ips");
        if (wl instanceof List<?> list) { whitelistedIps.clear(); list.forEach(ip -> whitelistedIps.add(String.valueOf(ip))); }
    }
    private void saveDefaultConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enable_rate_limit", true);
        cfg.put("enable_cooldown", true);
        cfg.put("enable_spam_detection", true);
        cfg.put("enable_flood_detection", true);
        cfg.put("enable_name_filter", true);
        cfg.put("enable_handshake_validation", true);
        cfg.put("enable_syn_flood_detection", true);
        cfg.put("enable_dynamic_blocking", true);
        cfg.put("enable_lockdown", true);
        cfg.put("max_conn_per_ip", 3);
        cfg.put("conn_window_sec", 60);
        cfg.put("cooldown_ms", 2000);
        cfg.put("global_join_threshold", 15);
        cfg.put("attack_cooldown_sec", 30);
        cfg.put("bot_name_regex", "^(Bot|Player|User|Test)_?\\\\d{3,}");
        cfg.put("name_entropy_threshold", 1.5);
        cfg.put("syn_timeout_ms", 5000);
        cfg.put("dynamic_block_base_sec", 60);
        cfg.put("dynamic_block_max_sec", 3600);
        cfg.put("threat_score_threshold", 5);
        cfg.put("min_protocol_version", 47);
        cfg.put("max_protocol_version", 99999);
        cfg.put("kick_message", "Connection denied. Please try again later.");
        cfg.put("lockdown_message", "Server under attack. Only known players may join.");
        cfg.put("whitelisted_ips", List.of());
        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
    }
    // ================================================================
    //  PERSISTENT DATA
    // ================================================================
    private void loadPersistentData() {
        Path f = context.getDataDirectory().resolve("known_players.json");
        if (Files.exists(f)) {
            try {
                Set<String> s = gson.fromJson(Files.readString(f), new TypeToken<Set<String>>(){}.getType());
                if (s != null) knownPlayers.addAll(s);
            } catch (Exception e) { logger.warn("Failed to load known players: {}", e.getMessage()); }
        }
    }
    private void savePersistentData() {
        try {
            Path f = context.getDataDirectory().resolve("known_players.json");
            Files.createDirectories(f.getParent());
            Files.writeString(f, gson.toJson(knownPlayers));
        } catch (Exception e) { logger.warn("Failed to save known players: {}", e.getMessage()); }
    }
    // ================================================================
    //  EVENT LISTENER
    // ================================================================
    public class BotListener {
        @Subscribe
        public void onPreLogin(PreLoginEvent event) {
            String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
            String name = event.getUsername();
            if (whitelistedIps.contains(ip)) {
                log("DEBUG", "WHITELIST", "Whitelisted IP bypass: " + name + " (" + ip + ")");
                return;
            }
            long now = System.currentTimeMillis();
            // -- Dynamic ban check --
            if (enableDynamicBlocking) {
                Long banExpiry = dynamicBans.get(ip);
                if (banExpiry != null && now < banExpiry) {
                    deny(event, ip, name, "Dynamic ban active");
                    return;
                } else if (banExpiry != null) {
                    dynamicBans.remove(ip);
                }
            }
            // -- Handshake validation --
            if (enableHandshakeValidation) {
                int proto = event.getConnection().getProtocolVersion().getProtocol();
                if (proto < minProtocolVersion || proto > maxProtocolVersion) {
                    addThreat(ip, 3, "Invalid protocol version: " + proto);
                    deny(event, ip, name, "Invalid protocol version: " + proto);
                    return;
                }
            }
            // -- SYN flood tracking (mark as pending) --
            if (enableSynFloodDetection) {
                pendingHandshakes.add(ip + ":" + name);
            }
            // -- Connection cooldown --
            if (enableCooldown) {
                Long last = ipLastConn.get(ip);
                if (last != null && (now - last) < cooldownMs) {
                    addThreat(ip, 1, "Cooldown violation");
                    deny(event, ip, name, "Connection cooldown");
                    return;
                }
                ipLastConn.put(ip, now);
            }
            // -- Rate limit per IP --
            if (enableRateLimit) {
                Deque<Long> times = ipConnTimes.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
                times.addLast(now);
                long windowStart = now - (connWindowSec * 1000L);
                while (!times.isEmpty() && times.peekFirst() < windowStart) times.pollFirst();
                if (times.size() > maxConnPerIp) {
                    addThreat(ip, 2, "Rate limit exceeded");
                    deny(event, ip, name, "Rate limit exceeded");
                    return;
                }
            }
            // -- Bot name heuristics --
            if (enableNameFilter) {
                try {
                    if (name.matches(botNameRegex)) {
                        addThreat(ip, 2, "Bot name pattern: " + name);
                        deny(event, ip, name, "Bot name pattern");
                        return;
                    }
                } catch (Exception ignored) {}
                double entropy = calcEntropy(name);
                if (name.length() > 3 && entropy < nameEntropyThreshold) {
                    addThreat(ip, 1, "Low name entropy: " + String.format("%.2f", entropy));
                }
            }
            // -- Spam detection (same username rapid retry) --
            if (enableSpamDetection) {
                String key = ip + ":" + name;
                Deque<Long> times = ipConnTimes.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
                times.addLast(now);
                while (!times.isEmpty() && times.peekFirst() < now - 10000) times.pollFirst();
                if (times.size() > 3) {
                    addThreat(ip, 3, "Spam login: " + name);
                    deny(event, ip, name, "Spam login attempts");
                    return;
                }
            }
            // -- Global flood tracking --
            if (enableFloodDetection) {
                globalJoinTs.addLast(now);
            }
        }
        @Subscribe
        public void onLogin(LoginEvent event) {
            Player player = event.getPlayer();
            String ip = player.getRemoteAddress().getAddress().getHostAddress();
            String uuid = player.getUniqueId().toString();
            // Clear SYN pending
            pendingHandshakes.remove(ip + ":" + player.getUsername());
            if (whitelistedIps.contains(ip)) {
                knownPlayers.add(uuid);
                totalAllowed.incrementAndGet();
                log("DEBUG", "ALLOW", "Whitelisted IP: " + player.getUsername() + " (" + ip + ")");
                return;
            }
            // -- Lockdown check --
            if (underAttack && enableLockdown && !knownPlayers.contains(uuid)) {
                event.setResult(ResultedEvent.ComponentResult.denied(
                        Component.text(lockdownMessage).color(NamedTextColor.RED)));
                totalBlocked.incrementAndGet();
                logger.info("Lockdown blocked: {} ({})", player.getUsername(), ip);
                log("HIGH", "LOCKDOWN", "Blocked unknown player during attack: " + player.getUsername() + " (" + ip + ")");
                return;
            }
            knownPlayers.add(uuid);
            totalAllowed.incrementAndGet();
            if (knownPlayers.size() % 100 == 0) context.getTaskScheduler().runAsync(this::savePersist);
        }
        @Subscribe
        public void onDisconnect(DisconnectEvent event) {
            // no-op for now, useful for session tracking
        }
        private void savePersist() { savePersistentData(); }
    }
    private void deny(PreLoginEvent event, String ip, String name, String reason) {
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                Component.text(kickMessage).color(NamedTextColor.RED)));
        totalBlocked.incrementAndGet();
        lastBotSeen = Instant.now();
        logger.debug("Blocked {} ({}) - {}", name, ip, reason);
        log("WARN", "BLOCK", "Blocked " + name + " (" + ip + ") - " + reason);
    }
    private void addThreat(String ip, int score, String reason) {
        if (!enableDynamicBlocking) return;
        AtomicInteger threat = ipThreatScore.computeIfAbsent(ip, k -> new AtomicInteger(0));
        int total = threat.addAndGet(score);
        if (total >= threatScoreThreshold) {
            int level = banEscalation.getOrDefault(ip, 0) + 1;
            banEscalation.put(ip, level);
            int banSec = Math.min(dynamicBlockBaseSec * level, dynamicBlockMaxSec);
            dynamicBans.put(ip, System.currentTimeMillis() + banSec * 1000L);
            threat.set(0);
            logger.info("Dynamic ban: {} for {}s (level {}) - {}", ip, banSec, level, reason);
            log("HIGH", "BAN", "Dynamic ban: " + ip + " for " + banSec + "s (level " + level + ") - " + reason);
        }
    }
    // ================================================================
    //  ANALYSIS LOOP
    // ================================================================
    private void startAnalysisLoop() {
        context.getTaskScheduler().scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            // -- Global join rate --
            while (!globalJoinTs.isEmpty() && globalJoinTs.peekFirst() < now - 1000) globalJoinTs.pollFirst();
            int rate = globalJoinTs.size();
            currentJoinRate.set(rate);
            synchronized (rateHistory) { rateHistory.add(rate); if (rateHistory.size() > 120) rateHistory.remove(0); }
            // -- Flood detection --
            if (enableFloodDetection && rate >= globalJoinThreshold) {
                if (!underAttack) {
                    underAttack = true;
                    attackStart = Instant.now();
                    totalAttacks.incrementAndGet();
                    logger.warn("BOT ATTACK - {} joins/sec (threshold: {})", rate, globalJoinThreshold);
                    log("HIGH", "ATTACK", "Bot attack detected: " + rate + " joins/sec (threshold: " + globalJoinThreshold + ")");
                }
                lastBotSeen = Instant.now();
            } else if (underAttack && lastBotSeen != null) {
                long quiet = (now - lastBotSeen.toEpochMilli()) / 1000;
                if (quiet > attackCooldownSec) {
                    underAttack = false;
                    attackStart = null;
                    logger.info("Bot attack ended after {}s quiet", attackCooldownSec);
                    log("INFO", "ATTACK", "Bot attack ended after " + attackCooldownSec + "s quiet. " + totalBlocked.get() + " total blocked.");
                }
            }
            // -- SYN flood check: pending handshakes older than timeout --
            if (enableSynFloodDetection && !pendingHandshakes.isEmpty()) {
                // We track by ip:name strings; if many pending after timeout, it's SYN-like
                if (pendingHandshakes.size() > globalJoinThreshold) {
                    synFloodsDetected.incrementAndGet();
                    logger.warn("SYN flood suspected: {} pending handshakes", pendingHandshakes.size());
                    pendingHandshakes.clear();
                }
            }
            // -- Cleanup expired bans and old tracking data --
            dynamicBans.entrySet().removeIf(e -> now > e.getValue());
            if (now % 30000 < 1000) {
                long window = now - (connWindowSec * 1000L);
                ipConnTimes.entrySet().removeIf(e -> {
                    Deque<Long> t = e.getValue();
                    while (!t.isEmpty() && t.peekFirst() < window) t.pollFirst();
                    return t.isEmpty();
                });
                ipThreatScore.entrySet().removeIf(e -> e.getValue().get() <= 0);
            }
        }, 1, 1, TimeUnit.SECONDS);
        // Save known players periodically
        context.getTaskScheduler().scheduleAtFixedRate(this::savePersistentData, 5, 5, TimeUnit.MINUTES);
    }
    // ================================================================
    //  HELPERS
    // ================================================================
    private double calcEntropy(String s) {
        if (s == null || s.isEmpty()) return 0;
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : s.toCharArray()) freq.merge(c, 1, Integer::sum);
        double entropy = 0;
        for (int count : freq.values()) {
            double p = (double) count / s.length();
            if (p > 0) entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }
    private String fmtDuration(Instant start) {
        if (start == null) return "N/A";
        long s = Instant.now().getEpochSecond() - start.getEpochSecond();
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m " + (s % 60) + "s";
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }
    private boolean bool(Map<String, Object> m, String k, boolean d) { Object v = m.get(k); return v instanceof Boolean b ? b : d; }
    private int num(Map<String, Object> m, String k, int d) { Object v = m.get(k); return v instanceof Number n ? n.intValue() : d; }
    private double dbl(Map<String, Object> m, String k, double d) { Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : d; }
    private String str(Map<String, Object> m, String k, String d) { Object v = m.get(k); return v != null ? v.toString() : d; }
    // ================================================================
    //  DASHBOARD REST API
    // ================================================================
    private void registerDashboard() {
        ModuleDashboard d = context.getDashboard();
        d.get("status", this::apiStatus);
        d.get("rates", this::apiRates);
        d.get("config", this::apiGetConfig);
        d.post("config", this::apiSaveConfig);
        d.get("whitelist", this::apiGetWhitelist);
        d.post("whitelist/add", this::apiAddWhitelist);
        d.post("whitelist/remove", this::apiRemoveWhitelist);
        d.post("clear", this::apiClear);
    }
    private void apiStatus(RequestContext ctx) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("under_attack", underAttack);
        s.put("lockdown_active", underAttack && enableLockdown);
        s.put("current_rate", currentJoinRate.get());
        s.put("threshold", globalJoinThreshold);
        s.put("total_blocked", totalBlocked.get());
        s.put("total_allowed", totalAllowed.get());
        s.put("total_attacks", totalAttacks.get());
        s.put("syn_floods", synFloodsDetected.get());
        s.put("known_players", knownPlayers.size());
        s.put("tracked_ips", ipConnTimes.size());
        s.put("dynamic_bans", dynamicBans.size());
        s.put("pending_handshakes", pendingHandshakes.size());
        if (attackStart != null) s.put("attack_duration", fmtDuration(attackStart));
        ctx.json(s);
    }
    private void apiRates(RequestContext ctx) {
        synchronized (rateHistory) { ctx.json(Map.of("rates", new ArrayList<>(rateHistory))); }
    }
    private void apiGetConfig(RequestContext ctx) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enable_rate_limit", enableRateLimit);
        c.put("enable_cooldown", enableCooldown);
        c.put("enable_spam_detection", enableSpamDetection);
        c.put("enable_flood_detection", enableFloodDetection);
        c.put("enable_name_filter", enableNameFilter);
        c.put("enable_handshake_validation", enableHandshakeValidation);
        c.put("enable_syn_flood_detection", enableSynFloodDetection);
        c.put("enable_dynamic_blocking", enableDynamicBlocking);
        c.put("enable_lockdown", enableLockdown);
        c.put("max_conn_per_ip", maxConnPerIp);
        c.put("conn_window_sec", connWindowSec);
        c.put("cooldown_ms", cooldownMs);
        c.put("global_join_threshold", globalJoinThreshold);
        c.put("attack_cooldown_sec", attackCooldownSec);
        c.put("bot_name_regex", botNameRegex);
        c.put("name_entropy_threshold", nameEntropyThreshold);
        c.put("syn_timeout_ms", synTimeoutMs);
        c.put("dynamic_block_base_sec", dynamicBlockBaseSec);
        c.put("dynamic_block_max_sec", dynamicBlockMaxSec);
        c.put("threat_score_threshold", threatScoreThreshold);
        c.put("min_protocol_version", minProtocolVersion);
        c.put("max_protocol_version", maxProtocolVersion);
        c.put("kick_message", kickMessage);
        c.put("lockdown_message", lockdownMessage);
        ctx.json(c);
    }
    private void apiSaveConfig(RequestContext ctx) {
        if (configManager == null) { ctx.status(500).json(Map.of("error", "Config unavailable")); return; }
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        Map<String, Object> cfg = configManager.getModuleConfig(context.getDescriptor().id());
        b.entrySet().forEach(e -> {
            if (e.getValue().isJsonPrimitive()) {
                var p = e.getValue().getAsJsonPrimitive();
                if (p.isBoolean()) cfg.put(e.getKey(), p.getAsBoolean());
                else if (p.isNumber()) cfg.put(e.getKey(), p.getAsNumber());
                else cfg.put(e.getKey(), p.getAsString());
            }
        });
        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
        loadConfig();
        log("INFO", "CONFIG", "Configuration updated via dashboard");
        ctx.json(Map.of("success", true));
    }
    private void apiGetWhitelist(RequestContext ctx) { ctx.json(Map.of("ips", new ArrayList<>(whitelistedIps))); }
    private void apiAddWhitelist(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String ip = b.has("ip") ? b.get("ip").getAsString().trim() : "";
        if (ip.isBlank()) { ctx.status(400).json(Map.of("error", "IP required")); return; }
        whitelistedIps.add(ip);
        saveWhitelist();
        log("INFO", "WHITELIST", "Added IP to whitelist: " + ip);
        ctx.json(Map.of("success", true));
    }
    private void apiRemoveWhitelist(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String ip = b.has("ip") ? b.get("ip").getAsString().trim() : "";
        whitelistedIps.remove(ip);
        saveWhitelist();
        log("INFO", "WHITELIST", "Removed IP from whitelist: " + ip);
        ctx.json(Map.of("success", true));
    }
    private void apiClear(RequestContext ctx) {
        underAttack = false;
        attackStart = null;
        dynamicBans.clear();
        pendingHandshakes.clear();
        log("INFO", "CLEAR", "Attack state cleared via dashboard");
        ctx.json(Map.of("success", true));
    }
    private void saveWhitelist() {
        if (configManager == null) return;
        Map<String, Object> cfg = configManager.getModuleConfig(context.getDescriptor().id());
        cfg.put("whitelisted_ips", new ArrayList<>(whitelistedIps));
        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
    }
}