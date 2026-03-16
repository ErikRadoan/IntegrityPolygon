package dev.erikradovan.integritypolygon.core;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Watches the modules directory for new or modified JAR files and triggers
 * automatic loading via the {@link ModuleManager}.
 * <p>
 * Uses a debounce strategy: file events are recorded with timestamps, and
 * processing only occurs after a configurable stability period has elapsed
 * since the last event for a given file. This avoids reloading while a file
 * is still being written/copied.
 */
public class ModuleWatcher implements Runnable {

    private final Path modulesDir;
    private final ModuleManager moduleManager;
    private final Logger logger;
    private final Map<Path, Long> pendingReloads = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "IP-ModuleWatcher-Scheduler");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    /** How long a file must be unchanged before we consider it stable. */
    private static final long STABILITY_DELAY_MS = 2000;
    /** How often we check pending files for stability. */
    private static final long CHECK_INTERVAL_MS = 500;

    public ModuleWatcher(Path modulesDir, ModuleManager moduleManager, Logger logger) {
        this.modulesDir = modulesDir;
        this.moduleManager = moduleManager;
        this.logger = logger;
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    @Override
    public void run() {
        try {
            Files.createDirectories(modulesDir);
        } catch (IOException e) {
            logger.error("Cannot create modules directory: {}", modulesDir, e);
            return;
        }

        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            modulesDir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

            scheduler.scheduleAtFixedRate(
                    this::processPendingFiles,
                    CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

            logger.info("Module hot-reload watcher started on {}", modulesDir);

            while (running) {
                WatchKey key;
                try {
                    key = watcher.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    Path fileName = ((WatchEvent<Path>) event).context();
                    if (!fileName.toString().endsWith(".jar")) continue;

                    Path fullPath = modulesDir.resolve(fileName);
                    pendingReloads.put(fullPath, System.currentTimeMillis());
                }
                key.reset();
            }
        } catch (IOException e) {
            logger.error("Error in ModuleWatcher", e);
        }
    }

    /**
     * Process files that have been stable for {@link #STABILITY_DELAY_MS}.
     * Runs on a scheduled thread — must not block.
     */
    private void processPendingFiles() {
        long now = System.currentTimeMillis();

        var iterator = pendingReloads.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Path path = entry.getKey();
            long lastEventTime = entry.getValue();

            if (now - lastEventTime < STABILITY_DELAY_MS) continue;

            iterator.remove();

            if (!Files.exists(path)) continue;

            logger.info("Detected stable change: {}", path.getFileName());
            try {
                moduleManager.loadModule(path.toFile());
            } catch (Exception e) {
                logger.error("Failed to load module from {}", path.getFileName(), e);
            }
        }
    }
}
