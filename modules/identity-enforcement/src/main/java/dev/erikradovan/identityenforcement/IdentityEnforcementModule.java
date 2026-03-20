package dev.erikradovan.identityenforcement;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import dev.erikradovan.integritypolygon.api.*;
import dev.erikradovan.integritypolygon.api.ModuleDashboard.RequestContext;
import dev.erikradovan.integritypolygon.logging.LogManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class IdentityEnforcementModule implements dev.erikradovan.integritypolygon.api.Module {

    private ModuleContext context;
    private Logger logger;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile boolean enabled = true;
    private volatile boolean blockVpn = true;
    private volatile boolean blockProxy = true;
    private volatile String apiKey = "";
    private volatile String kickMessage = "VPN/Proxy connections are not allowed on this server.";
    private volatile int cacheTtlMinutes = 60;

    // Subnet-level cache: /24 prefix -> CacheEntry (avoids spamming API for same subnet)
    private final ConcurrentHashMap<String, CacheEntry> subnetCache = new ConcurrentHashMap<>();
    // Per-IP cache for exact results
    private final ConcurrentHashMap<String, CacheEntry> ipCache = new ConcurrentHashMap<>();
    private final Set<String> whitelistedIps = ConcurrentHashMap.newKeySet();
    private final Set<String> whitelistedUuids = ConcurrentHashMap.newKeySet();

    private final AtomicLong totalChecked = new AtomicLong(0);
    private final AtomicLong totalBlocked = new AtomicLong(0);
    private final AtomicLong totalAllowed = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong apiCalls = new AtomicLong(0);
    private final AtomicLong apiErrors = new AtomicLong(0);

    private ModuleConfigStore configStore;
    private LogManager logManager;

    @Override
    public void onEnable(ModuleContext ctx) {
        this.context = ctx;
        this.logger = ctx.getLogger();
        this.configStore = ctx.getConfigStore();
        ServiceRegistry reg = ctx.getServiceRegistry();
        this.logManager = reg.get(LogManager.class).orElse(null);

        loadConfig();
        ctx.getEventManager().subscribe(new ConnectionListener());
        registerDashboard();
        ctx.getTaskScheduler().scheduleAtFixedRate(this::cleanupCache, 5, 5, TimeUnit.MINUTES);

        log("INFO", "LIFECYCLE", "Identity Enforcement enabled [vpn=" + blockVpn + ", proxy=" + blockProxy
                + ", cache_ttl=" + cacheTtlMinutes + "m, api_key=" + (apiKey.isEmpty() ? "NOT SET" : "configured") + "]");
    }

    @Override
    public void onDisable() {
        log("INFO", "LIFECYCLE", "Identity Enforcement disabled [blocked=" + totalBlocked.get()
                + ", allowed=" + totalAllowed.get() + ", api_calls=" + apiCalls.get() + "]");
    }

    @Override
    public void onReload() {
        loadConfig();
        log("INFO", "LIFECYCLE", "Configuration reloaded");
    }

    private void log(String level, String tag, String message) {
        if (logManager != null) logManager.log("identity-enforcement", level, tag, message);
    }

    // ── Config ──

    private void loadConfig() {
        if (configStore == null) return;
        configStore.registerOptions(List.of(
                ModuleConfigOption.bool("enabled", true, false, "Enable identity checks for incoming connections."),
                ModuleConfigOption.bool("block_vpn", true, false, "Block VPN connections reported by proxycheck.io."),
                ModuleConfigOption.bool("block_proxy", true, false, "Block proxy connections reported by proxycheck.io."),
                ModuleConfigOption.string("api_key", "", false, "proxycheck.io API key."),
                ModuleConfigOption.string("kick_message", "VPN/Proxy connections are not allowed on this server.", false, "Kick message for blocked clients."),
                ModuleConfigOption.integer("cache_ttl_minutes", 60, false, "IP/subnet cache TTL in minutes."),
                ModuleConfigOption.list("whitelisted_ips", List.of(), false, "Exact whitelisted IPs."),
                ModuleConfigOption.list("whitelisted_uuids", List.of(), false, "Whitelisted UUID values.")
        ));

        enabled = configStore.getBoolean("enabled", true);
        blockVpn = configStore.getBoolean("block_vpn", true);
        blockProxy = configStore.getBoolean("block_proxy", true);
        apiKey = configStore.getString("api_key", "");
        kickMessage = configStore.getString("kick_message", kickMessage);
        cacheTtlMinutes = configStore.getInt("cache_ttl_minutes", 60);
        whitelistedIps.clear();
        whitelistedIps.addAll(configStore.getStringList("whitelisted_ips"));
        whitelistedUuids.clear();
        whitelistedUuids.addAll(configStore.getStringList("whitelisted_uuids"));
    }

    // ── Event Listener ──

    public class ConnectionListener {
        @Subscribe
        public void onPreLogin(PreLoginEvent event) {
            if (!enabled) return;
            String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
            String name = event.getUsername();
            if (whitelistedIps.contains(ip)) { totalAllowed.incrementAndGet(); totalChecked.incrementAndGet(); return; }

            // Check per-IP cache first
            CacheEntry cached = ipCache.get(ip);
            if (cached != null && !cached.isExpired(cacheTtlMinutes)) {
                cacheHits.incrementAndGet();
                totalChecked.incrementAndGet();
                if (cached.blocked) deny(event, ip, name, cached.reason); else totalAllowed.incrementAndGet();
                return;
            }

            // Check subnet cache (/24)
            String subnet = getSubnet24(ip);
            if (subnet != null) {
                CacheEntry subCached = subnetCache.get(subnet);
                if (subCached != null && !subCached.isExpired(cacheTtlMinutes)) {
                    cacheHits.incrementAndGet();
                    totalChecked.incrementAndGet();
                    if (subCached.blocked) deny(event, ip, name, subCached.reason); else totalAllowed.incrementAndGet();
                    return;
                }
            }

            if (apiKey.isEmpty()) { totalAllowed.incrementAndGet(); totalChecked.incrementAndGet(); return; }

            try {
                ProxyCheckResult result = checkIp(ip);
                apiCalls.incrementAndGet();
                totalChecked.incrementAndGet();
                boolean shouldBlock = false;
                String reason = "";
                if (result.isProxy && blockProxy) { shouldBlock = true; reason = "Proxy detected (type: " + result.type + ")"; }
                if (result.isVpn && blockVpn) { shouldBlock = true; reason = "VPN detected (provider: " + result.provider + ")"; }
                CacheEntry entry = new CacheEntry(shouldBlock, reason, System.currentTimeMillis());
                ipCache.put(ip, entry);
                if (subnet != null) subnetCache.put(subnet, entry);
                if (shouldBlock) {
                    deny(event, ip, name, reason);
                    log("WARN", "BLOCK", "Blocked " + name + " (" + ip + ") - " + reason);
                } else {
                    totalAllowed.incrementAndGet();
                }
            } catch (Exception e) {
                apiErrors.incrementAndGet();
                totalChecked.incrementAndGet();
                totalAllowed.incrementAndGet();
                log("WARN", "API", "API error checking " + ip + ": " + e.getMessage());
            }
        }
    }

    private void deny(PreLoginEvent event, String ip, String name, String reason) {
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                Component.text(kickMessage).color(NamedTextColor.RED)));
        totalBlocked.incrementAndGet();
    }

    private String getSubnet24(String ip) {
        int last = ip.lastIndexOf('.');
        return last > 0 ? ip.substring(0, last) + ".0/24" : null;
    }

    // ── proxycheck.io API ──

    private ProxyCheckResult checkIp(String ip) throws Exception {
        String url = "https://proxycheck.io/v2/" + ip + "?key=" + apiKey + "&vpn=1&asn=1";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", "IntegrityPolygon/2.0").timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new RuntimeException("HTTP " + response.statusCode());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String status = json.has("status") ? json.get("status").getAsString() : "";
        if (!"ok".equalsIgnoreCase(status)) {
            String msg = json.has("message") ? json.get("message").getAsString() : "Unknown error";
            throw new RuntimeException("API returned: " + msg);
        }
        ProxyCheckResult result = new ProxyCheckResult();
        if (json.has(ip)) {
            JsonObject d = json.getAsJsonObject(ip);
            String proxy = d.has("proxy") ? d.get("proxy").getAsString() : "no";
            result.isProxy = "yes".equalsIgnoreCase(proxy);
            result.type = d.has("type") ? d.get("type").getAsString() : "unknown";
            result.isVpn = "VPN".equalsIgnoreCase(result.type);
            result.provider = d.has("provider") ? d.get("provider").getAsString() : "";
            result.country = d.has("country") ? d.get("country").getAsString() : "";
            result.isocode = d.has("isocode") ? d.get("isocode").getAsString() : "";
            result.risk = d.has("risk") ? d.get("risk").getAsInt() : 0;
        }
        return result;
    }

    // ── Cache ──

    static class CacheEntry {
        final boolean blocked;
        final String reason;
        final long timestamp;
        CacheEntry(boolean blocked, String reason, long timestamp) {
            this.blocked = blocked; this.reason = reason; this.timestamp = timestamp;
        }
        boolean isExpired(int ttlMinutes) { return System.currentTimeMillis() - timestamp > ttlMinutes * 60_000L; }
    }

    static class ProxyCheckResult {
        boolean isProxy, isVpn;
        String type = "", provider = "", country = "", isocode = "";
        int risk;
    }

    private void cleanupCache() {
        ipCache.entrySet().removeIf(e -> e.getValue().isExpired(cacheTtlMinutes));
        subnetCache.entrySet().removeIf(e -> e.getValue().isExpired(cacheTtlMinutes));
    }

    // ── Dashboard REST API ──

    private void registerDashboard() {
        ModuleDashboard d = context.getDashboard();
        d.get("status", this::apiStatus);
        d.get("config", this::apiGetConfig);
        d.post("config", this::apiSaveConfig);
        d.get("whitelist", this::apiGetWhitelist);
        d.post("whitelist/add-ip", this::apiAddWhitelistIp);
        d.post("whitelist/remove-ip", this::apiRemoveWhitelistIp);
        d.post("whitelist/add-uuid", this::apiAddWhitelistUuid);
        d.post("whitelist/remove-uuid", this::apiRemoveWhitelistUuid);
        d.post("cache/clear", this::apiClearCache);
        d.post("check", this::apiManualCheck);
    }

    private void apiStatus(RequestContext ctx) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", enabled); s.put("block_vpn", blockVpn); s.put("block_proxy", blockProxy);
        s.put("api_key_set", !apiKey.isEmpty());
        s.put("total_checked", totalChecked.get()); s.put("total_blocked", totalBlocked.get());
        s.put("total_allowed", totalAllowed.get()); s.put("cache_hits", cacheHits.get());
        s.put("cache_size", ipCache.size() + subnetCache.size());
        s.put("api_calls", apiCalls.get()); s.put("api_errors", apiErrors.get());
        s.put("whitelisted_ips", whitelistedIps.size()); s.put("whitelisted_uuids", whitelistedUuids.size());
        ctx.json(s);
    }

    private void apiGetConfig(RequestContext ctx) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", enabled); c.put("block_vpn", blockVpn); c.put("block_proxy", blockProxy);
        c.put("api_key", apiKey); c.put("kick_message", kickMessage); c.put("cache_ttl_minutes", cacheTtlMinutes);
        ctx.json(c);
    }

    private void apiSaveConfig(RequestContext ctx) {
        if (configStore == null) { ctx.status(500).json(Map.of("error", "Config unavailable")); return; }
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        b.entrySet().forEach(e -> { if (e.getValue().isJsonPrimitive()) {
            var p = e.getValue().getAsJsonPrimitive();
            if (p.isBoolean()) configStore.set(e.getKey(), p.getAsBoolean());
            else if (p.isNumber()) configStore.set(e.getKey(), p.getAsNumber());
            else configStore.set(e.getKey(), p.getAsString());
        }});
        loadConfig(); log("INFO", "CONFIG", "Configuration updated via dashboard");
        ctx.json(Map.of("success", true));
    }

    private void apiGetWhitelist(RequestContext ctx) { ctx.json(Map.of("ips", new ArrayList<>(whitelistedIps), "uuids", new ArrayList<>(whitelistedUuids))); }

    private void apiAddWhitelistIp(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String ip = b.has("ip") ? b.get("ip").getAsString().trim() : "";
        if (ip.isBlank()) { ctx.status(400).json(Map.of("error", "IP required")); return; }
        whitelistedIps.add(ip); saveWhitelist(); log("INFO", "WHITELIST", "Added IP: " + ip);
        ctx.json(Map.of("success", true));
    }

    private void apiRemoveWhitelistIp(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String ip = b.has("ip") ? b.get("ip").getAsString().trim() : "";
        whitelistedIps.remove(ip); saveWhitelist(); ctx.json(Map.of("success", true));
    }

    private void apiAddWhitelistUuid(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String uuid = b.has("uuid") ? b.get("uuid").getAsString().trim() : "";
        if (uuid.isBlank()) { ctx.status(400).json(Map.of("error", "UUID required")); return; }
        whitelistedUuids.add(uuid); saveWhitelist(); ctx.json(Map.of("success", true));
    }

    private void apiRemoveWhitelistUuid(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String uuid = b.has("uuid") ? b.get("uuid").getAsString().trim() : "";
        whitelistedUuids.remove(uuid); saveWhitelist(); ctx.json(Map.of("success", true));
    }

    private void apiClearCache(RequestContext ctx) {
        ipCache.clear(); subnetCache.clear(); log("INFO", "CACHE", "Cache cleared via dashboard");
        ctx.json(Map.of("success", true));
    }

    private void apiManualCheck(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String ip = b.has("ip") ? b.get("ip").getAsString().trim() : "";
        if (ip.isBlank()) { ctx.status(400).json(Map.of("error", "IP required")); return; }
        if (apiKey.isEmpty()) { ctx.status(400).json(Map.of("error", "API key not configured")); return; }
        try {
            ProxyCheckResult r = checkIp(ip);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ip", ip); res.put("is_proxy", r.isProxy); res.put("is_vpn", r.isVpn);
            res.put("type", r.type); res.put("provider", r.provider); res.put("country", r.country); res.put("risk", r.risk);
            ctx.json(res);
        } catch (Exception e) { ctx.status(500).json(Map.of("error", "Check failed: " + e.getMessage())); }
    }

    private void saveWhitelist() {
        if (configStore == null) return;
        configStore.set("whitelisted_ips", new ArrayList<>(whitelistedIps));
        configStore.set("whitelisted_uuids", new ArrayList<>(whitelistedUuids));
    }
}

