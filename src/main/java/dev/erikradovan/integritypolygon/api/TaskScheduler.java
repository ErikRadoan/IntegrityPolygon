package dev.erikradovan.integritypolygon.api;

import java.util.concurrent.TimeUnit;

/**
 * Module-scoped task scheduler for running asynchronous and periodic tasks.
 * Each module receives its own instance via {@link ModuleContext#getTaskScheduler()}.
 *
 * <p>All tasks scheduled through this interface are automatically cancelled
 * when the module is unloaded — modules do not need to track tasks manually.
 *
 * <p>Example usage:
 * <pre>{@code
 * // Run something asynchronously
 * context.getTaskScheduler().runAsync(() -> downloadDatabase());
 *
 * // Schedule a repeating task (e.g., refresh IP database every hour)
 * context.getTaskScheduler().scheduleAtFixedRate(
 *     () -> refreshDatabase(),
 *     0, 1, TimeUnit.HOURS
 * );
 * }</pre>
 */
public interface TaskScheduler {

    /**
     * Run a task asynchronously on the shared thread pool.
     *
     * @param task the task to execute
     * @return a handle that can be used to cancel the task
     */
    ScheduledTask runAsync(Runnable task);

    /**
     * Schedule a task to run after a delay.
     *
     * @param task  the task to execute
     * @param delay the delay before execution
     * @param unit  the time unit of the delay
     * @return a handle that can be used to cancel the task
     */
    ScheduledTask schedule(Runnable task, long delay, TimeUnit unit);

    /**
     * Schedule a task to run repeatedly at a fixed rate.
     *
     * @param task         the task to execute
     * @param initialDelay the initial delay before first execution
     * @param period       the period between successive executions
     * @param unit         the time unit of the delay and period
     * @return a handle that can be used to cancel the task
     */
    ScheduledTask scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit);

    /**
     * Cancel all tasks scheduled through this scheduler.
     * Called automatically by the framework on module unload.
     */
    void cancelAll();

    /**
     * Handle for a scheduled task, allowing cancellation.
     */
    interface ScheduledTask {

        /**
         * Cancel this task.
         */
        void cancel();

        /**
         * @return true if this task has been cancelled
         */
        boolean isCancelled();
    }
}

