package dev.superchunk.ca.spottedleaf.starlight.common.thread;

import dev.superchunk.ca.spottedleaf.starlight.common.config.Config;
import dev.superchunk.com.ishland.flowsched.executor.ExecutorManager;

import java.util.concurrent.atomic.AtomicInteger;

public class GlobalExecutors {

    private static final AtomicInteger prioritizedSchedulerCounter = new AtomicInteger(0);
    // In externally managed mode C2ME merges lighting into its own pool: SchedulingUtil.scheduleTask
    // (the sole dereference of this field) is @Overwrite-routed to C2ME's prioritizedScheduler, so this
    // pool is never used. Skip building it to avoid spinning up ~availableProcessors()/3 idle daemon
    // worker threads plus an unused priority queue. ENABLED stays true, so behavior is unchanged.
    public static final ExecutorManager prioritizedScheduler = SchedulingUtil.isExternallyManaged() ? null : new ExecutorManager(Config.PARALLELISM, thread -> {
        thread.setDaemon(true);
        thread.setName("scalablelux-%d".formatted(prioritizedSchedulerCounter.getAndIncrement()));
    });
    private static final boolean FORCE_ENABLED = Boolean.getBoolean("scalablelux.force_enabled");
    public static final boolean ENABLED = SchedulingUtil.isExternallyManaged() || FORCE_ENABLED || Config.PARALLELISM > 1;

    static {
        if (SchedulingUtil.isExternallyManaged()) {
            System.out.println("[ScalableLux] Lighting scaling is enabled in externally managed mode");
        } else if (FORCE_ENABLED) {
            System.out.println("[ScalableLux] Lighting scaling is forced enabled, using %d threads".formatted(Config.PARALLELISM));
        } else if (ENABLED) {
            System.out.println("[ScalableLux] Lighting scaling is enabled, using %d threads".formatted(Config.PARALLELISM));
        } else {
            System.out.println("[ScalableLux] Lighting scaling is disabled (due to low parallelism in the settings)");
        }
    }

}
