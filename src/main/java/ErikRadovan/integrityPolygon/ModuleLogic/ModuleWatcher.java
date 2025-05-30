package ErikRadovan.integrityPolygon.ModuleLogic;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;

public class ModuleWatcher implements Runnable {
    private final File modulesDir;
    private final ModuleLoader loader;
    private final Logger logger;

    private final Map<Path, Long> pendingReloads = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = true;

    private static final long STABILITY_DELAY_MS = 1500;
    private static final long CHECK_INTERVAL_MS = 1000;

    public ModuleWatcher(File modulesDir, ModuleLoader loader, Logger logger) {
        this.modulesDir = modulesDir;
        this.loader = loader;
        this.logger = logger;
    }

    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    @Override
    public void run() {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            Path path = modulesDir.toPath();
            path.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

            // Background thread that checks file stability
            scheduler.scheduleAtFixedRate(this::processPendingFiles, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

            System.out.println("➤  👀  Module directory watcher started");

            while (running) {
                WatchKey key = watcher.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();
                    if (!fileName.toString().endsWith(".jar")) continue;

                    Path fullPath = path.resolve(fileName);
                    pendingReloads.put(fullPath, System.currentTimeMillis());
                }
                key.reset();
            }
        } catch (Exception e) {
            logger.error("❌  Error in ModuleWatcher", e);
        }
    }

    private void processPendingFiles() {
        long now = System.currentTimeMillis();

        for (Map.Entry<Path, Long> entry : pendingReloads.entrySet()) {
            Path path = entry.getKey();
            long lastEventTime = entry.getValue();

            if (now - lastEventTime < STABILITY_DELAY_MS) continue; // Still settling

            try {
                long size1 = Files.size(path);
                Thread.sleep(200); // Wait a little to detect file still writing
                long size2 = Files.size(path);

                if (size1 == size2) {
                    System.out.println("➤ ✅  Detected Change in: " + path.getFileName());
                    loader.loadModules();
                    pendingReloads.remove(path);
                } else {
                    pendingReloads.put(path, now); // Update timestamp if still changing
                }
            } catch (IOException | InterruptedException e) {
                logger.warn("⚠️  Could not check file stability: " + path.getFileName(), e);
            }
        }
    }

    //TODO: Add function for new file uploads and removals
}
