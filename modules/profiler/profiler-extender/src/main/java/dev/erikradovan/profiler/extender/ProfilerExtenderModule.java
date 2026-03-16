package dev.erikradovan.profiler.extender;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.erikradovan.extender.api.ExtenderModule;
import dev.erikradovan.extender.api.ExtenderModuleContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Paper-side JVM profiler running as a dynamically deployed extender module.
 * Uses only the JDK management beans and Paper/Bukkit APIs.
 */
public class ProfilerExtenderModule implements ExtenderModule {

    private static final int MSPT_BUFFER_SIZE = 1200; // 1 minute at 20 TPS
    private static final int FLAME_STACK_LIMIT = 1200;
    private static final int SAMPLE_TREE_STACK_LIMIT = 2000;
    private static final int SAMPLE_TREE_MAX_DEPTH = 18;
    private static final int HOTSPOT_TOP_PLUGINS = 30;
    private static final int HOTSPOT_TOP_CLASSES_PER_PLUGIN = 25;
    private static final int HOTSPOT_TOP_METHODS_PER_CLASS = 20;
    private static final int SNAPSHOT_MAX_ROWS = 60;
    private static final int SNAPSHOT_MAX_ROWS_PER_PLUGIN = 12;

    private ExtenderModuleContext context;
    private Logger logger;
    private JavaPlugin plugin;

    private volatile int sampleIntervalMs = 1;
    private volatile int reportIntervalSec = 10;
    private String machineHostname;

    private Thread samplerThread;
    private volatile boolean samplerRunning;
    private ScheduledExecutorService reportScheduler;

    private final ConcurrentHashMap<String, AtomicLong> cumulativeMethodSamples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> windowMethodSamples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> windowPluginSamples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> cumulativeFoldedStacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> windowPluginCpuNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> threadLastCpuTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> classOwnerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> methodOwnerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> packageOwnerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GcSnapshot> gcBaselines = new ConcurrentHashMap<>();
    private final AtomicLong cumulativeSampleCount = new AtomicLong();
    private final AtomicLong windowSampleCount = new AtomicLong();

    private int tpsTaskId = -1;
    private final Deque<Long> tickTimestamps = new ConcurrentLinkedDeque<>();
    private final long[] msptBuffer = new long[MSPT_BUFFER_SIZE];
    private int msptIndex;
    private int msptCount;
    private long lastTickNano;
    private volatile long lastGcSnapshotMs;

    @Override
    public void onEnable(ExtenderModuleContext ctx) {
        this.context = ctx;
        this.logger = ctx.getLogger();
        this.plugin = ctx.getPlugin();
        this.machineHostname = resolveMachineHostname();
        this.lastGcSnapshotMs = System.currentTimeMillis();
        initializeGcBaselines();
        rebuildPackageOwnerCache();

        ctx.onMessage(this::handleCommand);

        Bukkit.getScheduler().runTask(plugin, () -> {
            lastTickNano = System.nanoTime();
            tpsTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::onTick, 1L, 1L);
        });

        startSampler();
        restartReportScheduler();
        sendReady();

        logger.info("Profiler extender module enabled [sample=" + sampleIntervalMs + "ms, report=" + reportIntervalSec + "s]");
    }

    @Override
    public void onDisable() {
        samplerRunning = false;
        if (samplerThread != null) {
            samplerThread.interrupt();
        }
        if (tpsTaskId != -1) {
            Bukkit.getScheduler().cancelTask(tpsTaskId);
        }
        if (reportScheduler != null) {
            reportScheduler.shutdownNow();
        }
        logger.info("Profiler extender module disabled");
    }

    private void handleCommand(String type, JsonObject payload) {
        if (!"command".equals(type) || !isAddressedToThisServer(payload)) {
            return;
        }

        String action = payload.has("action") ? payload.get("action").getAsString() : "";
        switch (action) {
            case "configure" -> {
                int newSampleInterval = payload.has("sample_interval_ms") ? payload.get("sample_interval_ms").getAsInt() : sampleIntervalMs;
                int newReportInterval = payload.has("report_interval_sec") ? payload.get("report_interval_sec").getAsInt() : reportIntervalSec;
                sampleIntervalMs = Math.max(1, newSampleInterval);
                if (newReportInterval != reportIntervalSec) {
                    reportIntervalSec = Math.max(1, newReportInterval);
                    restartReportScheduler();
                }
                logger.info("Profiler config updated [sample=" + sampleIntervalMs + "ms, report=" + reportIntervalSec + "s]");
            }
            case "generate_flame" -> sendFlameGraph();
            case "reset" -> resetSamples();
            case "report_now" -> sendReport();
            default -> logger.fine("Ignoring unknown profiler action: " + action);
        }
    }

    private boolean isAddressedToThisServer(JsonObject payload) {
        if (!payload.has("server")) {
            return true;
        }
        String target = payload.get("server").getAsString();
        return target.isBlank()
                || context.getServerLabel().equalsIgnoreCase(target)
                || machineHostname.equalsIgnoreCase(target);
    }

    private void onTick() {
        long now = System.nanoTime();
        if (lastTickNano > 0L) {
            long tickDuration = now - lastTickNano;
            synchronized (msptBuffer) {
                msptBuffer[msptIndex] = tickDuration;
                msptIndex = (msptIndex + 1) % msptBuffer.length;
                if (msptCount < msptBuffer.length) {
                    msptCount++;
                }
            }
        }
        lastTickNano = now;

        long nowMs = System.currentTimeMillis();
        tickTimestamps.addLast(nowMs);
        while (!tickTimestamps.isEmpty() && tickTimestamps.peekFirst() < nowMs - 300_000L) {
            tickTimestamps.pollFirst();
        }
    }

    private double calculateTps(int windowMs) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;
        int count = 0;
        long oldest = now;
        for (Long timestamp : tickTimestamps) {
            if (timestamp >= cutoff) {
                if (count == 0) {
                    oldest = timestamp;
                }
                count++;
            }
        }
        if (count <= 1) {
            return 20.0;
        }
        long elapsedMs = Math.max(1L, now - oldest);
        return Math.min(20.0, count / (elapsedMs / 1000.0));
    }

    private double[] calculateMsptStats() {
        long[] snapshot;
        int count;
        synchronized (msptBuffer) {
            count = msptCount;
            snapshot = Arrays.copyOf(msptBuffer, count);
        }
        if (count == 0) {
            return new double[]{0.0, 0.0, 0.0, 0.0};
        }

        Arrays.sort(snapshot, 0, count);
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += snapshot[i];
        }

        double mean = (sum / count) / 1_000_000.0;
        double min = snapshot[0] / 1_000_000.0;
        double max = snapshot[count - 1] / 1_000_000.0;
        int p95Index = Math.min(count - 1, Math.max(0, (int) Math.ceil(count * 0.95) - 1));
        double p95 = snapshot[p95Index] / 1_000_000.0;
        return new double[]{mean, min, max, p95};
    }

    private void startSampler() {
        samplerRunning = true;
        samplerThread = new Thread(() -> {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            boolean cpuTimeSupported = threadBean.isThreadCpuTimeSupported();
            if (cpuTimeSupported && !threadBean.isThreadCpuTimeEnabled()) {
                try {
                    threadBean.setThreadCpuTimeEnabled(true);
                } catch (UnsupportedOperationException ignored) {
                    cpuTimeSupported = false;
                }
            }

            while (samplerRunning) {
                try {
                    Set<Long> seenThreads = new HashSet<>();
                    Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
                    for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
                        Thread thread = entry.getKey();
                        StackTraceElement[] frames = entry.getValue();
                        if (shouldSkipThread(thread, frames)) {
                            continue;
                        }

                        long threadId = thread.threadId();
                        seenThreads.add(threadId);

                        Sample sample = snapshotSample(frames);
                        if (sample == null) {
                            continue;
                        }

                        cumulativeSampleCount.incrementAndGet();
                        windowSampleCount.incrementAndGet();

                        for (String method : sample.methods()) {
                            increment(cumulativeMethodSamples, method, 1L);
                            increment(windowMethodSamples, method, 1L);
                        }
                        for (String pluginName : sample.plugins()) {
                            increment(windowPluginSamples, pluginName, 1L);
                        }
                        increment(cumulativeFoldedStacks, sample.foldedStack(), 1L);

                        if (cpuTimeSupported) {
                            long cpuTime = threadBean.getThreadCpuTime(threadId);
                            if (cpuTime >= 0L) {
                                Long previous = threadLastCpuTime.put(threadId, cpuTime);
                                if (previous != null && cpuTime > previous) {
                                    increment(windowPluginCpuNanos, sample.primaryPlugin(), cpuTime - previous);
                                }
                            }
                        }
                    }

                    threadLastCpuTime.keySet().removeIf(threadId -> !seenThreads.contains(threadId));
                    Thread.sleep(Math.max(1, sampleIntervalMs));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warning("Profiler sampler error: " + e.getMessage());
                }
            }
        }, "IP-Profiler-Sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    private boolean shouldSkipThread(Thread thread, StackTraceElement[] frames) {
        if (thread == null || frames == null || frames.length == 0) {
            return true;
        }
        if (thread == Thread.currentThread()) {
            return true;
        }
        String name = thread.getName();
        return name != null && name.startsWith("IP-Profiler-");
    }

    private Sample snapshotSample(StackTraceElement[] frames) {
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        LinkedHashSet<String> plugins = new LinkedHashSet<>();
        List<String> callChain = new ArrayList<>(frames.length);

        for (int i = frames.length - 1; i >= 0; i--) {
            StackTraceElement frame = frames[i];
            String method = frame.getClassName() + "#" + frame.getMethodName();
            methods.add(method);
            callChain.add(method);
            String owner = resolveClassOwner(frame.getClassName());
            methodOwnerCache.putIfAbsent(method, owner);
            plugins.add(owner);
        }

        if (methods.isEmpty()) {
            return null;
        }

        String primaryPlugin = determinePrimaryPlugin(frames);
        String foldedStack = String.join(";", callChain);
        return new Sample(primaryPlugin, foldedStack, methods, plugins);
    }

    private String determinePrimaryPlugin(StackTraceElement[] frames) {
        if (frames == null || frames.length == 0) {
            return "jvm";
        }

        Map<String, Long> scoreByOwner = new LinkedHashMap<>();
        boolean sawServer = false;

        // Bias toward leaf frames (where work is actually spent) similar to Spark blame selection.
        for (int i = 0; i < frames.length; i++) {
            StackTraceElement frame = frames[i];
            String owner = resolveClassOwner(frame.getClassName());
            if ("server".equals(owner)) {
                sawServer = true;
            }
            if (isInfrastructureOwner(owner)) {
                continue;
            }
            long weight = (long) (frames.length - i);
            scoreByOwner.merge(owner, weight, Long::sum);
        }

        if (!scoreByOwner.isEmpty()) {
            return scoreByOwner.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("jvm");
        }
        if (sawServer) {
            return "server";
        }
        return "jvm";
    }

    private String resolveClassOwner(String className) {
        return classOwnerCache.computeIfAbsent(className, this::detectClassOwner);
    }

    private String detectClassOwner(String className) {
        if (className == null || className.isBlank()) {
            return "jvm";
        }
        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.")
                || className.startsWith("sun.") || className.startsWith("com.sun.")) {
            return "jvm";
        }
        if (className.startsWith("org.bukkit.") || className.startsWith("org.spigotmc.")
                || className.startsWith("io.papermc.") || className.startsWith("net.minecraft.")) {
            return "server";
        }

        // Use longest matching package prefix for accurate plugin attribution
        // when multiple plugins share overlapping package hierarchies
        String bestMatch = null;
        int bestLength = -1;
        for (Map.Entry<String, String> entry : packageOwnerCache.entrySet()) {
            if (className.startsWith(entry.getKey()) && entry.getKey().length() > bestLength) {
                bestMatch = entry.getValue();
                bestLength = entry.getKey().length();
            }
        }
        if (bestMatch != null) {
            return bestMatch;
        }

        for (Plugin pluginCandidate : Bukkit.getPluginManager().getPlugins()) {
            if (!pluginCandidate.isEnabled()) {
                continue;
            }
            ClassLoader classLoader = pluginCandidate.getClass().getClassLoader();
            try {
                Class<?> resolved = Class.forName(className, false, classLoader);
                if (resolved.getClassLoader() == classLoader) {
                    // Cache the resolved package for future lookups
                    int lastDot = className.lastIndexOf('.');
                    if (lastDot > 0) {
                        packageOwnerCache.putIfAbsent(className.substring(0, lastDot) + ".", pluginCandidate.getName());
                    }
                    return pluginCandidate.getName();
                }
            } catch (ClassNotFoundException | LinkageError ignored) {
            }
        }

        return "jvm";
    }

    private boolean isInfrastructureOwner(String owner) {
        return owner == null || owner.isBlank() || "jvm".equals(owner) || "server".equals(owner);
    }

    private void rebuildPackageOwnerCache() {
        packageOwnerCache.clear();
        for (Plugin pluginCandidate : Bukkit.getPluginManager().getPlugins()) {
            try {
                String owner = pluginCandidate.getName();
                String mainClass = pluginCandidate.getDescription().getMain();
                if (mainClass == null || mainClass.isBlank()) {
                    continue;
                }
                int lastDot = mainClass.lastIndexOf('.');
                if (lastDot <= 0) {
                    continue;
                }
                String pkg = mainClass.substring(0, lastDot) + ".";
                packageOwnerCache.put(pkg, owner);
            } catch (Exception ignored) {
            }
        }
    }

    private void sendReport() {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("server", machineHostname);
            payload.addProperty("sample_interval_ms", sampleIntervalMs);
            payload.addProperty("report_interval_sec", reportIntervalSec);
            payload.addProperty("cumulative_samples", cumulativeSampleCount.get());

            payload.addProperty("tps_10s", round(calculateTps(10_000)));
            payload.addProperty("tps_1m", round(calculateTps(60_000)));
            payload.addProperty("tps_5m", round(calculateTps(300_000)));

            double[] mspt = calculateMsptStats();
            payload.addProperty("mspt_mean", round(mspt[0]));
            payload.addProperty("mspt_min", round(mspt[1]));
            payload.addProperty("mspt_max", round(mspt[2]));
            payload.addProperty("mspt_p95", round(mspt[3]));

            populateCpuMetrics(payload);
            populateMemoryMetrics(payload);
            populateGcMetrics(payload);

            long reportSamples = windowSampleCount.getAndSet(0L);
            payload.addProperty("total_samples", reportSamples);

            Map<String, Long> methodWindow = snapshotAndReset(windowMethodSamples);
            Map<String, Long> pluginSampleWindow = snapshotAndReset(windowPluginSamples);
            Map<String, Long> pluginCpuWindow = snapshotAndReset(windowPluginCpuNanos);

            payload.add("top_methods", buildSnapshotTopMethods(methodWindow, reportSamples));
            payload.add("hot_method_tree", buildHotMethodTreeJson(methodWindow, reportSamples));

            JsonObject pluginCpuJson = new JsonObject();
            long totalCpuWindow = pluginCpuWindow.values().stream().mapToLong(Long::longValue).sum();
            pluginCpuWindow.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(entry -> pluginCpuJson.addProperty(entry.getKey(),
                            totalCpuWindow > 0 ? round(entry.getValue() * 100.0 / totalCpuWindow) : 0.0));
            payload.add("plugin_cpu", pluginCpuJson);

            JsonObject pluginSamplesJson = new JsonObject();
            pluginSampleWindow.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(entry -> pluginSamplesJson.addProperty(entry.getKey(), entry.getValue()));
            payload.add("plugin_samples", pluginSamplesJson);

            Map<String, Long> foldedStacks = snapshot(cumulativeFoldedStacks);
            payload.add("sample_tree", buildSampleTreeJson(foldedStacks));

            context.sendMessage("profiling_data", payload);
        } catch (Exception e) {
            logger.warning("Failed to send profiling report: " + e.getMessage());
        }
    }

    private JsonObject buildSampleTreeJson(Map<String, Long> foldedStacks) {
        TreeNode root = new TreeNode("(root)");
        List<Map.Entry<String, Long>> sortedStacks = new ArrayList<>(foldedStacks.entrySet());
        sortedStacks.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        int limit = Math.min(SAMPLE_TREE_STACK_LIMIT, sortedStacks.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Long> entry = sortedStacks.get(i);
            long count = entry.getValue() != null ? entry.getValue() : 0L;
            if (count <= 0L) {
                continue;
            }

            String[] frames = entry.getKey().split(";");
            TreeNode node = root;
            node.total += count;

            int depth = 0;
            for (String frame : frames) {
                if (frame == null || frame.isBlank()) {
                    continue;
                }
                if (++depth > SAMPLE_TREE_MAX_DEPTH) {
                    break;
                }
                node = node.children.computeIfAbsent(frame, TreeNode::new);
                node.total += count;
            }
            node.self += count;
        }

        return toJson(root, root.total);
    }

    private JsonObject buildHotMethodTreeJson(Map<String, Long> methodWindow, long totalSamples) {
        Map<String, PluginNode> plugins = new LinkedHashMap<>();

        methodWindow.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(entry -> {
                    String fullMethod = entry.getKey();
                    long samples = entry.getValue() != null ? entry.getValue() : 0L;
                    if (samples <= 0L) {
                        return;
                    }

                    String pluginName = methodOwnerCache.getOrDefault(fullMethod, guessPluginForMethod(fullMethod));
                    String className = extractClassName(fullMethod);
                    String methodName = extractMethodName(fullMethod);

                    PluginNode pluginNode = plugins.computeIfAbsent(pluginName, PluginNode::new);
                    pluginNode.samples += samples;

                    ClassNode classNode = pluginNode.classes.computeIfAbsent(className, ClassNode::new);
                    classNode.samples += samples;

                    MethodNode methodNode = classNode.methods.computeIfAbsent(methodName, MethodNode::new);
                    methodNode.samples += samples;
                    methodNode.fullName = fullMethod;
                });

        JsonArray pluginArray = new JsonArray();
        plugins.values().stream()
                .sorted((a, b) -> Long.compare(b.samples, a.samples))
                .limit(HOTSPOT_TOP_PLUGINS)
                .forEach(pluginNode -> {
                    JsonObject pluginJson = new JsonObject();
                    pluginJson.addProperty("name", pluginNode.name);
                    pluginJson.addProperty("samples", pluginNode.samples);
                    pluginJson.addProperty("percent", totalSamples > 0 ? round(pluginNode.samples * 100.0 / totalSamples) : 0.0);
                    pluginJson.addProperty("is_infrastructure", isInfrastructureOwner(pluginNode.name));

                    JsonArray classArray = new JsonArray();
                    pluginNode.classes.values().stream()
                            .sorted((a, b) -> Long.compare(b.samples, a.samples))
                            .limit(HOTSPOT_TOP_CLASSES_PER_PLUGIN)
                            .forEach(classNode -> {
                                JsonObject classJson = new JsonObject();
                                classJson.addProperty("name", classNode.name);
                                classJson.addProperty("samples", classNode.samples);
                                classJson.addProperty("percent", totalSamples > 0 ? round(classNode.samples * 100.0 / totalSamples) : 0.0);

                                JsonArray methodArray = new JsonArray();
                                classNode.methods.values().stream()
                                        .sorted((a, b) -> Long.compare(b.samples, a.samples))
                                        .limit(HOTSPOT_TOP_METHODS_PER_CLASS)
                                        .forEach(methodNode -> {
                                            JsonObject methodJson = new JsonObject();
                                            methodJson.addProperty("name", methodNode.name);
                                            methodJson.addProperty("full_name", methodNode.fullName);
                                            methodJson.addProperty("samples", methodNode.samples);
                                            methodJson.addProperty("percent", totalSamples > 0 ? round(methodNode.samples * 100.0 / totalSamples) : 0.0);
                                            methodArray.add(methodJson);
                                        });
                                classJson.add("methods", methodArray);
                                classArray.add(classJson);
                            });

                    pluginJson.add("classes", classArray);
                    pluginArray.add(pluginJson);
                });

        JsonObject result = new JsonObject();
        result.addProperty("total_samples", totalSamples);
        result.add("plugins", pluginArray);
        return result;
    }

    private JsonArray buildSnapshotTopMethods(Map<String, Long> methodWindow, long totalSamples) {
        // Build per-plugin buckets so filtering out infrastructure still keeps useful rows.
        Map<String, List<MethodSnapshot>> byPlugin = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : methodWindow.entrySet()) {
            String fullMethod = entry.getKey();
            long samples = entry.getValue() != null ? entry.getValue() : 0L;
            if (samples <= 0L) {
                continue;
            }

            String pluginName = methodOwnerCache.getOrDefault(fullMethod, guessPluginForMethod(fullMethod));
            MethodSnapshot snap = new MethodSnapshot(
                    fullMethod,
                    pluginName,
                    extractClassName(fullMethod),
                    extractMethodName(fullMethod),
                    samples,
                    isInfrastructureOwner(pluginName)
            );
            byPlugin.computeIfAbsent(pluginName, ignored -> new ArrayList<>()).add(snap);
        }

        List<Map.Entry<String, List<MethodSnapshot>>> orderedPlugins = new ArrayList<>(byPlugin.entrySet());
        orderedPlugins.sort((a, b) -> {
            long aTotal = a.getValue().stream().mapToLong(MethodSnapshot::samples).sum();
            long bTotal = b.getValue().stream().mapToLong(MethodSnapshot::samples).sum();
            boolean aInfra = isInfrastructureOwner(a.getKey());
            boolean bInfra = isInfrastructureOwner(b.getKey());
            if (aInfra != bInfra) {
                return aInfra ? 1 : -1;
            }
            return Long.compare(bTotal, aTotal);
        });

        List<MethodSnapshot> selected = new ArrayList<>();
        for (Map.Entry<String, List<MethodSnapshot>> pluginEntry : orderedPlugins) {
            List<MethodSnapshot> pluginMethods = pluginEntry.getValue();
            pluginMethods.sort((a, b) -> Long.compare(b.samples(), a.samples()));
            int cap = isInfrastructureOwner(pluginEntry.getKey())
                    ? Math.max(2, SNAPSHOT_MAX_ROWS_PER_PLUGIN / 3)
                    : SNAPSHOT_MAX_ROWS_PER_PLUGIN;
            for (int i = 0; i < pluginMethods.size() && i < cap; i++) {
                selected.add(pluginMethods.get(i));
            }
            if (selected.size() >= SNAPSHOT_MAX_ROWS) {
                break;
            }
        }

        selected.sort((a, b) -> {
            if (a.infrastructure() != b.infrastructure()) {
                return a.infrastructure() ? 1 : -1;
            }
            return Long.compare(b.samples(), a.samples());
        });

        JsonArray topMethods = new JsonArray();
        int limit = Math.min(SNAPSHOT_MAX_ROWS, selected.size());
        for (int i = 0; i < limit; i++) {
            MethodSnapshot m = selected.get(i);
            JsonObject method = new JsonObject();
            method.addProperty("method", m.fullMethod());
            method.addProperty("plugin", m.plugin());
            method.addProperty("class_name", m.className());
            method.addProperty("method_name", m.methodName());
            method.addProperty("is_infrastructure", m.infrastructure());
            method.addProperty("samples", m.samples());
            method.addProperty("percent", totalSamples > 0 ? round(m.samples() * 100.0 / totalSamples) : 0.0);
            topMethods.add(method);
        }

        return topMethods;
    }

    private JsonObject toJson(TreeNode node, long rootTotal) {
        JsonObject json = new JsonObject();
        json.addProperty("name", node.name);
        json.addProperty("samples", node.total);
        json.addProperty("self_samples", node.self);
        json.addProperty("percent", rootTotal > 0 ? round(node.total * 100.0 / rootTotal) : 0.0);

        JsonArray children = new JsonArray();
        node.children.values().stream()
                .sorted((a, b) -> Long.compare(b.total, a.total))
                .forEach(child -> children.add(toJson(child, rootTotal)));
        json.add("children", children);
        return json;
    }

    private void populateCpuMetrics(JsonObject payload) {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double systemCpu = sunBean.getCpuLoad();
                double processCpu = sunBean.getProcessCpuLoad();
                payload.addProperty("system_cpu", systemCpu >= 0 ? round(systemCpu * 100.0) : 0.0);
                payload.addProperty("process_cpu", processCpu >= 0 ? round(processCpu * 100.0) : 0.0);
            }
        } catch (Exception ignored) {
        }
    }

    private void populateMemoryMetrics(JsonObject payload) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        payload.addProperty("heap_used_mb", heap.getUsed() / (1024L * 1024L));
        payload.addProperty("heap_max_mb", heap.getMax() / (1024L * 1024L));
    }

    private void populateGcMetrics(JsonObject payload) {
        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(1L, now - lastGcSnapshotMs);
        long totalCollections = 0L;
        long totalTimeMs = 0L;
        long deltaCollections = 0L;
        long deltaTimeMs = 0L;
        JsonArray collectors = new JsonArray();

        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long currentCollections = Math.max(0L, collector.getCollectionCount());
            long currentTimeMs = Math.max(0L, collector.getCollectionTime());
            GcSnapshot previous = gcBaselines.getOrDefault(collector.getName(), new GcSnapshot(currentCollections, currentTimeMs));
            long diffCollections = Math.max(0L, currentCollections - previous.collections());
            long diffTimeMs = Math.max(0L, currentTimeMs - previous.timeMs());

            JsonObject gc = new JsonObject();
            gc.addProperty("name", collector.getName());
            gc.addProperty("collections", currentCollections);
            gc.addProperty("collection_time_ms", currentTimeMs);
            gc.addProperty("delta_collections", diffCollections);
            gc.addProperty("delta_time_ms", diffTimeMs);
            gc.addProperty("frequency_per_min", round(diffCollections * (60_000.0 / elapsedMs)));
            gc.addProperty("avg_pause_ms", diffCollections > 0 ? round(diffTimeMs / (double) diffCollections) : 0.0);
            collectors.add(gc);

            gcBaselines.put(collector.getName(), new GcSnapshot(currentCollections, currentTimeMs));
            totalCollections += currentCollections;
            totalTimeMs += currentTimeMs;
            deltaCollections += diffCollections;
            deltaTimeMs += diffTimeMs;
        }

        lastGcSnapshotMs = now;
        payload.addProperty("gc_collections", totalCollections);
        payload.addProperty("gc_time_ms", totalTimeMs);
        payload.addProperty("gc_frequency_per_min", round(deltaCollections * (60_000.0 / elapsedMs)));
        payload.addProperty("gc_avg_pause_ms", deltaCollections > 0 ? round(deltaTimeMs / (double) deltaCollections) : 0.0);
        payload.add("gc_collectors", collectors);
    }

    private void sendFlameGraph() {
        try {
            Map<String, Long> foldedStacks = snapshot(cumulativeFoldedStacks);
            List<Map.Entry<String, Long>> sortedStacks = new ArrayList<>(foldedStacks.entrySet());
            sortedStacks.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

            JsonArray stacks = new JsonArray();
            int limit = Math.min(FLAME_STACK_LIMIT, sortedStacks.size());
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Long> entry = sortedStacks.get(i);
                JsonObject stack = new JsonObject();
                stack.addProperty("stack", entry.getKey());
                stack.addProperty("count", entry.getValue());
                stacks.add(stack);
            }

            JsonObject flameGraph = new JsonObject();
            flameGraph.addProperty("format", "folded");
            flameGraph.addProperty("server", machineHostname);
            flameGraph.addProperty("generated_at", System.currentTimeMillis());
            flameGraph.addProperty("total_samples", cumulativeSampleCount.get());
            flameGraph.addProperty("truncated", sortedStacks.size() > limit);
            flameGraph.add("stacks", stacks);

            JsonObject payload = new JsonObject();
            payload.add("flame_graph", flameGraph);
            context.sendMessage("flame_data", payload);
        } catch (Exception e) {
            logger.warning("Failed to generate flame graph: " + e.getMessage());
        }
    }

    private void sendReady() {
        JsonObject ready = new JsonObject();
        ready.addProperty("server", machineHostname);
        ready.addProperty("sample_interval_ms", sampleIntervalMs);
        ready.addProperty("report_interval_sec", reportIntervalSec);
        context.sendMessage("ready", ready);
    }

    private String resolveMachineHostname() {
        // Prefer OS hostname for human-readable dashboard labels.
        String env = System.getenv("COMPUTERNAME");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) {
                return host.trim();
            }
        } catch (Exception ignored) {
        }
        return context != null ? context.getServerLabel() : "unknown-server";
    }

    private void resetSamples() {
        cumulativeMethodSamples.clear();
        windowMethodSamples.clear();
        windowPluginSamples.clear();
        cumulativeFoldedStacks.clear();
        windowPluginCpuNanos.clear();
        cumulativeSampleCount.set(0L);
        windowSampleCount.set(0L);
        logger.info("Profiler samples reset");
    }

    private void restartReportScheduler() {
        if (reportScheduler != null) {
            reportScheduler.shutdownNow();
        }
        reportScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "IP-Profiler-Reporter");
            thread.setDaemon(true);
            return thread;
        });
        reportScheduler.scheduleAtFixedRate(this::sendReport, reportIntervalSec, reportIntervalSec, TimeUnit.SECONDS);
    }

    private void initializeGcBaselines() {
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcBaselines.put(collector.getName(), new GcSnapshot(
                    Math.max(0L, collector.getCollectionCount()),
                    Math.max(0L, collector.getCollectionTime())
            ));
        }
    }

    private Map<String, Long> snapshotAndReset(ConcurrentHashMap<String, AtomicLong> map) {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : map.entrySet()) {
            long value = entry.getValue().getAndSet(0L);
            if (value > 0L) {
                snapshot.put(entry.getKey(), value);
            }
        }
        map.entrySet().removeIf(entry -> entry.getValue().get() == 0L);
        return snapshot;
    }

    private Map<String, Long> snapshot(ConcurrentHashMap<String, AtomicLong> map) {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : map.entrySet()) {
            long value = entry.getValue().get();
            if (value > 0L) {
                snapshot.put(entry.getKey(), value);
            }
        }
        return snapshot;
    }

    private void increment(ConcurrentHashMap<String, AtomicLong> map, String key, long delta) {
        if (key == null || key.isBlank() || delta <= 0L) {
            return;
        }
        map.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(delta);
    }

    private String guessPluginForMethod(String method) {
        if (method == null || method.isBlank() || !method.contains("#")) {
            return "jvm";
        }
        String className = method.substring(0, method.indexOf('#'));
        return resolveClassOwner(className);
    }

    private String extractClassName(String fullMethod) {
        if (fullMethod == null || fullMethod.isBlank()) {
            return "?";
        }
        int split = fullMethod.indexOf('#');
        if (split <= 0) {
            return fullMethod;
        }
        return fullMethod.substring(0, split);
    }

    private String extractMethodName(String fullMethod) {
        if (fullMethod == null || fullMethod.isBlank()) {
            return "?";
        }
        int split = fullMethod.indexOf('#');
        if (split < 0 || split >= fullMethod.length() - 1) {
            return fullMethod;
        }
        return fullMethod.substring(split + 1);
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Sample(String primaryPlugin, String foldedStack, Set<String> methods, Set<String> plugins) {
    }

    private record GcSnapshot(long collections, long timeMs) {
    }

    private static class TreeNode {
        final String name;
        long total;
        long self;
        final Map<String, TreeNode> children = new LinkedHashMap<>();

        TreeNode(String name) {
            this.name = name;
        }
    }

    private static class PluginNode {
        final String name;
        long samples;
        final Map<String, ClassNode> classes = new LinkedHashMap<>();

        PluginNode(String name) {
            this.name = name;
        }
    }

    private static class ClassNode {
        final String name;
        long samples;
        final Map<String, MethodNode> methods = new LinkedHashMap<>();

        ClassNode(String name) {
            this.name = name;
        }
    }

    private static class MethodNode {
        final String name;
        long samples;
        String fullName;

        MethodNode(String name) {
            this.name = name;
        }
    }

    private record MethodSnapshot(String fullMethod,
                                  String plugin,
                                  String className,
                                  String methodName,
                                  long samples,
                                  boolean infrastructure) {}
}

