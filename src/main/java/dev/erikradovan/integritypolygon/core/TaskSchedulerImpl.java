package dev.erikradovan.integritypolygon.core;

import dev.erikradovan.integritypolygon.api.TaskScheduler;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.*;

/**
 * Per-module task scheduler backed by a shared {@link ScheduledExecutorService}.
 * Tracks all tasks so they can be bulk-cancelled on module unload.
 */
public class TaskSchedulerImpl implements TaskScheduler {

    private final ScheduledExecutorService executor;
    private final Set<ScheduledTaskImpl> tasks = ConcurrentHashMap.newKeySet();
    private final String moduleId;
    private final Logger logger;

    public TaskSchedulerImpl(ScheduledExecutorService executor, String moduleId, Logger logger) {
        this.executor = executor;
        this.moduleId = moduleId;
        this.logger = logger;
    }

    @Override
    public ScheduledTask runAsync(Runnable task) {
        Future<?> future = executor.submit(wrapTask(task));
        ScheduledTaskImpl handle = new ScheduledTaskImpl(future);
        tasks.add(handle);
        return handle;
    }

    @Override
    public ScheduledTask schedule(Runnable task, long delay, TimeUnit unit) {
        ScheduledFuture<?> future = executor.schedule(wrapTask(task), delay, unit);
        ScheduledTaskImpl handle = new ScheduledTaskImpl(future);
        tasks.add(handle);
        return handle;
    }

    @Override
    public ScheduledTask scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(wrapTask(task), initialDelay, period, unit);
        ScheduledTaskImpl handle = new ScheduledTaskImpl(future);
        tasks.add(handle);
        return handle;
    }

    @Override
    public void cancelAll() {
        for (ScheduledTaskImpl task : tasks) {
            task.cancel();
        }
        tasks.clear();
    }

    private Runnable wrapTask(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("[{}] Uncaught exception in scheduled task", moduleId, e);
            }
        };
    }

    private static class ScheduledTaskImpl implements ScheduledTask {
        private final Future<?> future;

        ScheduledTaskImpl(Future<?> future) {
            this.future = future;
        }

        @Override
        public void cancel() {
            future.cancel(false);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }
}

