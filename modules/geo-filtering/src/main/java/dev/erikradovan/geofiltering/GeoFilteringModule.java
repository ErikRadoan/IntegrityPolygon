package dev.erikradovan.geofiltering;

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

/**
 * Geo-Filtering Module - Country-based access control using geojs.io.
 *
 * Modes:
 *   BLACKLIST - block connections from listed countries (allow everyone else)
 *   WHITELIST - only allow connections from listed countries (block everyone else)
 *
 * Uses geojs.io free API for IP geolocation with aggressive caching to avoid API spam.
 */
public class GeoFilteringModule implements dev.erikradovan.integritypolygon.api.Module {

    private ModuleContext context;
    private Logger logger;
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    // Config
    private volatile boolean enabled = true;
    private volatile String mode = "blacklist"; // "blacklist" or "whitelist"
    private volatile String kickMessage = "Connections from your country are not allowed.";
    private volatile int cacheTtlMinutes = 120;
    private final Set<String> countryList = ConcurrentHashMap.newKeySet(); // ISO country codes

    // Cache: IP -> GeoResult
    private final ConcurrentHashMap<String, GeoResult> ipCache = new ConcurrentHashMap<>();
    // Subnet cache: /24 prefix -> GeoResult
    private final ConcurrentHashMap<String, GeoResult> subnetCache = new ConcurrentHashMap<>();

    // Stats
    private final AtomicLong totalChecked = new AtomicLong(0);
    private final AtomicLong totalBlocked = new AtomicLong(0);
    private final AtomicLong totalAllowed = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong apiCalls = new AtomicLong(0);
    private final AtomicLong apiErrors = new AtomicLong(0);

    // Top countries stats
    private final ConcurrentHashMap<String, AtomicLong> countryStats = new ConcurrentHashMap<>();

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
        ctx.getEventManager().subscribe(new GeoListener());
        registerDashboard();
        ctx.getTaskScheduler().scheduleAtFixedRate(this::cleanupCache, 10, 10, TimeUnit.MINUTES);

        log("INFO", "LIFECYCLE", "Geo-Filtering enabled [mode=" + mode + ", countries=" + countryList.size()
                + ", cache_ttl=" + cacheTtlMinutes + "m]");
    }

    @Override
    public void onDisable() {
        log("INFO", "LIFECYCLE", "Geo-Filtering disabled [blocked=" + totalBlocked.get()
                + ", allowed=" + totalAllowed.get() + "]");
    }

    @Override
    public void onReload() {
        loadConfig();
        log("INFO", "LIFECYCLE", "Configuration reloaded");
    }

    private void log(String level, String tag, String message) {
        if (logManager != null) logManager.log("geo-filtering", level, tag, message);
    }

    // ── Config ──

    private void loadConfig() {
        if (configStore == null) return;
        configStore.registerOptions(List.of(
                ModuleConfigOption.bool("enabled", true, false, "Enable geolocation filtering."),
                ModuleConfigOption.string("mode", "blacklist", false, "Filtering mode: blacklist or whitelist."),
                ModuleConfigOption.string("kick_message", "Connections from your country are not allowed.", false, "Kick message for blocked countries."),
                ModuleConfigOption.integer("cache_ttl_minutes", 120, false, "IP/subnet geolocation cache TTL."),
                ModuleConfigOption.list("country_list", List.of(), false, "Configured ISO country list.")
        ));

        enabled = configStore.getBoolean("enabled", true);
        mode = configStore.getString("mode", "blacklist");
        kickMessage = configStore.getString("kick_message", kickMessage);
        cacheTtlMinutes = configStore.getInt("cache_ttl_minutes", 120);
        countryList.clear();
        for (String c : configStore.getStringList("country_list")) {
            countryList.add(c.toUpperCase());
        }
    }

    // ── Event Listener ──

    public class GeoListener {
        @Subscribe
        public void onPreLogin(PreLoginEvent event) {
            if (!enabled || countryList.isEmpty()) return;
            String ip = event.getConnection().getRemoteAddress().getAddress().getHostAddress();
            String name = event.getUsername();

            // Check per-IP cache
            GeoResult cached = ipCache.get(ip);
            if (cached != null && !cached.isExpired(cacheTtlMinutes)) {
                cacheHits.incrementAndGet();
                totalChecked.incrementAndGet();
                evaluateAccess(event, ip, name, cached);
                return;
            }

            // Check subnet cache (/24)
            String subnet = getSubnet24(ip);
            if (subnet != null) {
                GeoResult subCached = subnetCache.get(subnet);
                if (subCached != null && !subCached.isExpired(cacheTtlMinutes)) {
                    cacheHits.incrementAndGet();
                    totalChecked.incrementAndGet();
                    evaluateAccess(event, ip, name, subCached);
                    return;
                }
            }

            // Query geojs.io
            try {
                GeoResult result = lookupIp(ip);
                apiCalls.incrementAndGet();
                totalChecked.incrementAndGet();
                ipCache.put(ip, result);
                if (subnet != null) subnetCache.put(subnet, result);
                evaluateAccess(event, ip, name, result);
            } catch (Exception e) {
                apiErrors.incrementAndGet();
                totalChecked.incrementAndGet();
                totalAllowed.incrementAndGet(); // fail-open
                log("WARN", "API", "geojs.io error for " + ip + ": " + e.getMessage());
            }
        }
    }

    private void evaluateAccess(PreLoginEvent event, String ip, String name, GeoResult geo) {
        String code = geo.countryCode.toUpperCase();
        if (!code.isEmpty()) {
            countryStats.computeIfAbsent(code, k -> new AtomicLong(0)).incrementAndGet();
        }

        boolean blocked;
        if ("whitelist".equalsIgnoreCase(mode)) {
            blocked = !countryList.contains(code);
        } else {
            blocked = countryList.contains(code);
        }

        if (blocked) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text(kickMessage).color(NamedTextColor.RED)));
            totalBlocked.incrementAndGet();
            log("WARN", "BLOCK", "Blocked " + name + " (" + ip + ") from " + geo.country + " [" + code + "]");
        } else {
            totalAllowed.incrementAndGet();
        }
    }

    private String getSubnet24(String ip) {
        int last = ip.lastIndexOf('.');
        return last > 0 ? ip.substring(0, last) + ".0/24" : null;
    }

    // ── geojs.io API ──

    private GeoResult lookupIp(String ip) throws Exception {
        String url = "https://get.geojs.io/v1/ip/geo/" + ip + ".json";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", "IntegrityPolygon/2.0").timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new RuntimeException("HTTP " + response.statusCode());

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        GeoResult result = new GeoResult();
        result.country = json.has("country") ? json.get("country").getAsString() : "Unknown";
        result.countryCode = json.has("country_code") ? json.get("country_code").getAsString() :
                (json.has("country_code3") ? json.get("country_code3").getAsString() : "");
        result.timestamp = System.currentTimeMillis();
        return result;
    }

    // ── Cache ──

    static class GeoResult {
        String country = "";
        String countryCode = "";
        long timestamp;
        boolean isExpired(int ttlMinutes) { return System.currentTimeMillis() - timestamp > ttlMinutes * 60_000L; }
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
        d.get("countries", this::apiGetCountries);
        d.post("countries/add", this::apiAddCountry);
        d.post("countries/remove", this::apiRemoveCountry);
        d.get("stats", this::apiCountryStats);
        d.post("cache/clear", this::apiClearCache);
        d.post("lookup", this::apiLookup);
    }

    private void apiStatus(RequestContext ctx) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", enabled); s.put("mode", mode);
        s.put("country_count", countryList.size());
        s.put("total_checked", totalChecked.get()); s.put("total_blocked", totalBlocked.get());
        s.put("total_allowed", totalAllowed.get()); s.put("cache_hits", cacheHits.get());
        s.put("cache_size", ipCache.size() + subnetCache.size());
        s.put("api_calls", apiCalls.get()); s.put("api_errors", apiErrors.get());
        ctx.json(s);
    }

    private void apiGetConfig(RequestContext ctx) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", enabled); c.put("mode", mode);
        c.put("kick_message", kickMessage); c.put("cache_ttl_minutes", cacheTtlMinutes);
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

    private void apiGetCountries(RequestContext ctx) {
        ctx.json(Map.of("mode", mode, "countries", new ArrayList<>(countryList)));
    }

    private void apiAddCountry(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String code = b.has("code") ? b.get("code").getAsString().trim().toUpperCase() : "";
        if (code.isBlank() || code.length() > 3) { ctx.status(400).json(Map.of("error", "Valid ISO country code required")); return; }
        countryList.add(code);
        saveCountryList();
        log("INFO", "COUNTRY", "Added country " + code + " to " + mode);
        ctx.json(Map.of("success", true));
    }

    private void apiRemoveCountry(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String code = b.has("code") ? b.get("code").getAsString().trim().toUpperCase() : "";
        countryList.remove(code);
        saveCountryList();
        log("INFO", "COUNTRY", "Removed country " + code + " from " + mode);
        ctx.json(Map.of("success", true));
    }

    private void apiCountryStats(RequestContext ctx) {
        Map<String, Long> stats = new LinkedHashMap<>();
        countryStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(20)
                .forEach(e -> stats.put(e.getKey(), e.getValue().get()));
        ctx.json(Map.of("countries", stats));
    }

    private void apiClearCache(RequestContext ctx) {
        ipCache.clear(); subnetCache.clear();
        log("INFO", "CACHE", "Cache cleared via dashboard");
        ctx.json(Map.of("success", true));
    }

    private void apiLookup(RequestContext ctx) {
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        String ip = b.has("ip") ? b.get("ip").getAsString().trim() : "";
        if (ip.isBlank()) { ctx.status(400).json(Map.of("error", "IP required")); return; }
        try {
            GeoResult r = lookupIp(ip);
            boolean wouldBlock;
            if ("whitelist".equalsIgnoreCase(mode)) {
                wouldBlock = !countryList.contains(r.countryCode.toUpperCase());
            } else {
                wouldBlock = countryList.contains(r.countryCode.toUpperCase());
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ip", ip); res.put("country", r.country); res.put("country_code", r.countryCode);
            res.put("would_block", wouldBlock); res.put("mode", mode);
            ctx.json(res);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Lookup failed: " + e.getMessage()));
        }
    }

    private void saveCountryList() {
        if (configStore == null) return;
        configStore.set("country_list", new ArrayList<>(countryList));
    }
}

