package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import dev.superchunk.com.ishland.c2me.base.common.config.ConfigSystem;

import java.util.concurrent.TimeUnit;

public class Config {

    // Forge 1.20.1 port: default OFF (neo default is true) — off-thread ChunkSerializer.read
    // posts Forge ChunkDataEvent.Load from worker threads (plan §5 risk 3).
    //
    // "flip after modpack QA" — DONE, 2026-08-17, and the answer is KEEP IT OFF. Measured on the
    // r2048 bench (interleaved, warm-up leg first, harness matched to the neo recipe):
    //   OFF: 1246.2 / 1200.9 cps      ON: 1223.1 cps, then the second leg DIED.
    // It is not faster, and the second ON leg threw ConcurrentModificationException out of
    // "Feature placement" (chunk [-59,102]) at 99.57% and then stalled the last ~286 chunks for
    // 59 minutes — 1:00:10 wall for a pregen that takes 53 s with it off. That is the same
    // concurrency hazard PORT-NOTES records as the KNOWN-TRANSIENT CME in the storage thread,
    // amplified from "retried 4/4 successfully" into a hard stall.
    //
    // So this is no longer a pending port task: on 1.20.1 the flag costs throughput AND risks the
    // run. Do not flip it without first fixing the feature-placement CME. neo keeps true because
    // its 1.21.1 chunk system does not share that path.
    public static final boolean asyncSerialization = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.asyncSerialization")
            .comment("""
                    Whether to enable async serialization
                    """)
            .getBoolean(false, false);

    public static final boolean recoverFromErrors = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.recoverFromErrors")
            .comment("""
                    Whether to recover from errors when loading chunks\s
                     This will cause errored chunk to be regenerated entirely, which may cause data loss\s
                     Only applies when async chunk loading is enabled
                     """)
            .getBoolean(false, false);

    public static final boolean allowPOIUnloading = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.allowPOIUnloading")
            .comment("""
                    Whether to allow POIs (Point of Interest) to be unloaded
                    Unloaded POIs are reloaded on-demand or when the corresponding chunks are loaded again,
                    which should not cause any behavior change
                    \s
                    Note:
                    Vanilla never unloads POIs when chunks unload, causing small memory leaks
                    These leaks adds up and eventually cause issues after generating millions of chunks
                    in a single world instance
                    """)
            .getBoolean(true, false);

    public static final boolean suppressGhostMushrooms = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.suppressGhostMushrooms")
            .comment("""
                    This option workarounds MC-276863, a bug that makes mushrooms appear in non-postprocessed chunks
                    This bug is amplified with notickvd as it exposes non-postprocessed chunks to players
                    
                    This should not affect other worldgen behavior and game mechanics in general
                    """)
            .getBoolean(false, false);

    public static final boolean syncPlayerTickets = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.syncPlayerTickets")
            .comment("""
                    Whether to synchronize the management of player tickets
                    
                    In vanilla Minecraft, player tickets are not always removed immediately when players leave an area.
                    The delay in removal increases with the chunk system’s throughput, but due to vanilla’s typically
                    slow chunk loading, tickets are almost always removed immediately. However, some contraptions rely
                    on this immediate removal behavior and tend to be broken with the increased chunk throughput.
                    Enabling this option synchronizes player ticket handling, making it more predictable and
                    thus improving compatibility with these contraptions.
                    """)
            .getBoolean(true, false);

    public static final boolean fluidPostProcessingToScheduledTick = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.fluidPostProcessingToScheduledTick")
            .comment("""
                    Whether to turn fluid postprocessing into scheduled tick
                    
                    Fluid post-processing is very expensive when loading in new chunks, and this can affect
                    MSPT significantly. This option delays fluid post-processing to scheduled tick to hopefully
                    mitigate this issue.
                    """)
            .getBoolean(true, false);

    public static final boolean filterFluidPostProcessing = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.filterFluidPostProcessing")
            .comment("""
                    Whether to filter fluid post-processing on worldgen threads
                    
                    The worldgen processes creates a lot of unnecessary fluid post-processing tasks,
                    which can overload the server thread and cause stutters.
                    This applies a rough filter to filter out fluids that are definitely not going to flow
                    """)
            .getBoolean(true, false);

    public static final boolean useLegacyScheduling = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.useLegacyScheduling")
            .comment("""
                    Whether to use legacy scheduling for neighbor chunks
                    
                    Enabling this restores the behavior of always loading in neighbor chunks when a chunk is loaded.
                    """)
            .getBoolean(true, false);

    public static final boolean lowMemoryMode = new ConfigSystem.ConfigAccessor()
            .key("chunkSystem.lowMemoryMode")
            .comment("""
                    Whether to enable low memory mode
                    
                    This option will attempt to aggressively unload unused chunks.
                    Only applies when useLegacyScheduling is disabled.
                    """)
            .getBoolean(false, false);

    /**
     * How long a {@code create == false} {@code ServerChunkCache.getChunk} may keep draining
     * main-thread tasks waiting for an in-flight chunk before it gives up and returns null, in
     * nanoseconds. Negative by default, preserving vanilla's wait for healthy in-flight chunks.
     * {@code 0} = never wait; positive values opt into a timeout. A timeout can make collision
     * queries treat a still-loading chunk as empty, so it is only a diagnostic escape hatch.
     * See {@code MixinServerChunkManager#superchunk$boundNonCreatingGetChunk}.
     *
     * <p>A system property rather than a config key: the only reasons to change it are bisection
     * and reproducing GitHub issue #7.
     */
    public static final long nonCreatingGetChunkBudgetNanos =
            TimeUnit.MILLISECONDS.toNanos(Long.getLong("superchunk.chunkSystem.nonCreatingGetChunkBudgetMillis", -1L));

    public static void init() {
        // intentionally empty
    }

}
