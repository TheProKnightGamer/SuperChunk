package dev.superchunk.compat;

import dev.superchunk.gpu.dfc.BiomeClimateCache;
import dev.superchunk.gpu.dfc.ChunkGridCache;
import dev.superchunk.gpu.dfc.GpuBatchPrefetch;
import dev.superchunk.gpu.dfc.GpuClimatePrefetch;
import dev.superchunk.gpu.dfc.GpuPrefetchHandle;
import dev.superchunk.mixin.IChunkAccessLevelHeight;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk &harr; <b>Distant Horizons</b> batch-generator bridge: gives DH's own world generator
 * the same CROSS-CHUNK GPU dispatches the server chunk system gets.
 *
 * <h2>Why this seam exists</h2>
 * In its {@code SURFACE}/{@code FEATURES} generator modes DH does not ask the server for chunks —
 * its {@code BatchGenerationEnvironment} drives the vanilla generation steps itself, on DH's own
 * thread pool, one STEP at a time over a whole GROUP of chunks:
 * <pre>{@code
 *   StepBiomes.generateGroup(params, region, chunkWrappers)  ->  for each chunk: generator.createBiomes(...)
 *   StepNoise .generateGroup(params, region, chunkWrappers)  ->  for each chunk: generator.fillFromNoise(...)
 * }</pre>
 * SuperChunk's per-chunk GPU path already engages there (it hangs off {@code NoiseChunk}, not off
 * any scheduler), but the two BATCHED paths did not: both are wired into the chunk system's status
 * delegate ({@code VanillaWorldGenerationDelegate}), which DH bypasses entirely. The cost of that
 * gap is not just one dispatch per chunk instead of one per ~32 — the batched submit is also the
 * ONLY carrier of the chained decide-kernel block ids (compact ids), so DH chunks re-ran the whole
 * CPU decide loop that server-generated chunks skip.
 *
 * <h2>What it does</h2>
 * A group boundary is a natural batch: every chunk in it is about to run the same step. So as each
 * group is filtered (the {@code getChunkWrappersToGenerate} seam, one call per group, immediately
 * before the loop) we submit ALL of its chunks through the exact same submit/deposit code the
 * chunk-system seam uses ({@link GpuBatchPrefetch#submitForChunk} /
 * {@link GpuClimatePrefetch#submitForChunk}), then wait for the deposits before returning. DH's
 * per-chunk {@code createBiomes}/{@code fillFromNoise} calls then consume them from
 * {@code GpuClimateStore}/{@code GpuBatchStore} with no per-chunk dispatch at all — the same
 * consume path, byte for byte, that a server-generated chunk takes.
 *
 * <h2>Why blocking here is correct</h2>
 * The chunk-system seam deliberately does NOT block: it returns an RxJava step so the C2ME worker
 * is released. Here the opposite is right. The waiting thread is DH's own worldgen thread (DH
 * sizes that pool itself), DH requires each step to complete synchronously
 * ({@code BatchGenerationEnvironment.confirmFutureWasRunSynchronously} throws otherwise), and the
 * wait is per GROUP, not per chunk — one bounded wait amortized over ~n chunks. The batcher's
 * flush window is sub-millisecond and every request completes or fails on its own, so the wait is
 * short; {@link DistantHorizonsCompat#WAIT_MILLIS} is only a backstop, and on timeout the
 * outstanding requests are cancelled and DH generates exactly as it did before.
 *
 * <h2>Failure is always "DH's old behaviour"</h2>
 * Nothing here can break DH generation. Every step is defensive: an unrecognised wrapper class, an
 * un-derivable extent, a batcher that isn't live, another dimension's geometry, a dispatch failure
 * or a timeout all end with no deposit — and a chunk with no deposit simply takes the per-chunk
 * GPU/CPU path it took before this class existed. The hook never throws into DH.
 */
public final class DhWorldGenBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    /**
     * {@code ChunkWrapper.getChunk()} on DH's chunk wrapper. Resolved once per wrapper class
     * (there is exactly one at runtime; the field is a single-entry cache, and a second class
     * would simply re-resolve). Reflection because SuperChunk does not compile against DH — and
     * because DH's class NAME differs between its jar layouts (2.x
     * {@code loaderCommon.neoforge.…ChunkWrapper}, 3.x {@code …ChunkWrapper_neoforge}).
     */
    private static volatile Class<?> wrapperClass;
    private static volatile Method wrapperGetChunk;
    /** Sticky: the wrapper's getChunk could not be resolved — stop trying (logged once). */
    private static volatile boolean wrapperUnresolvable;

    private static final AtomicLong biomeGroups = new AtomicLong();
    private static final AtomicLong noiseGroups = new AtomicLong();
    private static final AtomicLong chunksSubmitted = new AtomicLong();
    private static final AtomicLong waitTimeouts = new AtomicLong();
    private static final AtomicBoolean firstGroupLogged = new AtomicBoolean(false);

    private DhWorldGenBridge() {
    }

    // ------------------------------------------------------------------
    // Group seam (called from MixinDhWorldGenStep).
    // ------------------------------------------------------------------

    /**
     * The one entry point: DH has just filtered {@code wrappers} down to the chunks its
     * {@code stepStatus} step is about to generate. Prefetch what that step will need.
     *
     * <p>Only the two steps SuperChunk can serve from batched GPU grids are handled — BIOMES
     * (climate quart grids, consumed by {@code createBiomes}' climate sampling) and NOISE (fused
     * density corner grids + compact ids, consumed by {@code fillFromNoise}). Every other DH step
     * returns immediately. Never throws.
     */
    public static void onGroup(ChunkStatus stepStatus, List<?> wrappers) {
        if (stepStatus == null || wrappers == null || wrappers.isEmpty()
                || !DistantHorizonsCompat.applyBatchGeneratorHooks()) {
            return;
        }
        try {
            if (stepStatus == ChunkStatus.BIOMES) {
                prefetchBiomes(wrappers);
            } else if (stepStatus == ChunkStatus.NOISE) {
                prefetchNoise(wrappers);
            }
        } catch (Throwable t) {
            // The DH path must never break DH generation.
            LOGGER.debug("[SuperChunk-Compat] [dh] group prefetch threw — DH generates as before.", t);
        }
    }

    /**
     * BIOMES: batch-prefetch each chunk's climate quart grids before DH runs {@code createBiomes}
     * over the group. No-op whenever climate batching isn't live.
     */
    private static void prefetchBiomes(List<?> wrappers) {
        if (!BiomeClimateCache.isBatchClimateActive()) {
            return;
        }
        List<ChunkAccess> chunks = collectChunks(wrappers);
        if (chunks.isEmpty()) {
            return;
        }
        // DH builds its ProtoChunks with the ServerLevel as their height accessor, so the chunk
        // itself identifies the level (and hence the dimension's noise router + generator) —
        // no DH type needed. A chunk that doesn't carry one simply isn't prefetched.
        ServerLevel level = levelOf(chunks.get(0));
        if (level == null) {
            return;
        }
        final NoiseRouter router;
        final ChunkGenerator generator;
        try {
            RandomState rs = level.getChunkSource().randomState();
            router = rs == null ? null : rs.router();
            generator = level.getChunkSource().getGenerator();
        } catch (Throwable t) {
            return;
        }
        if (router == null) {
            return;
        }
        List<GpuPrefetchHandle> handles = new ArrayList<>(chunks.size());
        for (ChunkAccess chunk : chunks) {
            GpuPrefetchHandle h = GpuClimatePrefetch.submitForChunk(chunk, generator, router);
            if (h != null) {
                handles.add(h);
            }
        }
        biomeGroups.incrementAndGet();
        awaitAll(handles, "biomes");
    }

    /**
     * NOISE: batch-prefetch each chunk's fused density grids (and, with compact ids on, its
     * decide-kernel block ids) before DH runs {@code fillFromNoise} over the group. No-op whenever
     * cross-chunk density batching isn't live.
     *
     * <p>Each chunk's {@code NoiseChunk} already exists here: DH ran its BIOMES step over the same
     * group moments earlier, and {@code createBiomes} is what creates and caches it. That is the
     * same precondition the chunk-system seam relies on.
     */
    private static void prefetchNoise(List<?> wrappers) {
        if (!ChunkGridCache.isBatchEnabled() || ChunkGridCache.liveBatcher() == null) {
            return;
        }
        List<ChunkAccess> chunks = collectChunks(wrappers);
        if (chunks.isEmpty()) {
            return;
        }
        List<GpuPrefetchHandle> handles = new ArrayList<>(chunks.size());
        for (ChunkAccess chunk : chunks) {
            GpuPrefetchHandle h = GpuBatchPrefetch.submitForChunk(chunk);
            if (h != null) {
                handles.add(h);
            }
        }
        noiseGroups.incrementAndGet();
        awaitAll(handles, "noise");
    }

    // ------------------------------------------------------------------
    // Internals.
    // ------------------------------------------------------------------

    /**
     * Waits — bounded — for every submitted prefetch in this group to have deposited, then
     * returns. On timeout (or interrupt) the still-unresolved requests are cancelled and their
     * store slots evicted, so nothing is left dangling and those chunks take the per-chunk path.
     * Never throws.
     */
    private static void awaitAll(List<GpuPrefetchHandle> handles, String stage) {
        if (handles.isEmpty()) {
            return;
        }
        chunksSubmitted.addAndGet(handles.size());
        if (firstGroupLogged.compareAndSet(false, true)) {
            LOGGER.info("[SuperChunk-Compat] [dh] Distant Horizons batch generation is now feeding SuperChunk's "
                    + "cross-chunk GPU batcher (first group: {} chunks at the {} step). DH-generated chunks now "
                    + "share batched dispatches with server-generated ones instead of paying one dispatch each.",
                    handles.size(), stage);
        }
        CompletableFuture<?>[] dones = new CompletableFuture<?>[handles.size()];
        for (int i = 0; i < handles.size(); i++) {
            dones[i] = handles.get(i).done();
        }
        try {
            CompletableFuture.allOf(dones).get(DistantHorizonsCompat.WAIT_MILLIS, TimeUnit.MILLISECONDS);
            return;
        } catch (InterruptedException e) {
            // DH cancels generation events by interrupting its worldgen threads
            // (BatchGenerationEnvironment.throwIfThreadInterrupted). Restore the flag so DH's own
            // next check sees it, and drop our outstanding requests.
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            waitTimeouts.incrementAndGet();
            LOGGER.warn("[SuperChunk-Compat] [dh] {} group prefetch did not complete within {} ms — cancelling {} "
                            + "outstanding request(s); those chunks take the per-chunk path.",
                    stage, DistantHorizonsCompat.WAIT_MILLIS, handles.size());
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-Compat] [dh] {} group wait threw — per-chunk path for this group.", stage, t);
        }
        for (GpuPrefetchHandle h : handles) {
            h.cancelIfUnresolved();
        }
    }

    /**
     * Unwraps DH's chunk wrappers to the {@link ChunkAccess}es behind them. The list is already
     * DH's own filtered "these chunks get this step" set (that is the method we inject into), so
     * no status filtering is repeated here — only the {@code ProtoChunk} sanity check DH itself
     * applies.
     */
    private static List<ChunkAccess> collectChunks(List<?> wrappers) {
        List<ChunkAccess> out = new ArrayList<>(wrappers.size());
        for (Object wrapper : wrappers) {
            ChunkAccess chunk = chunkOf(wrapper);
            if (chunk instanceof ProtoChunk) {
                out.add(chunk);
            }
        }
        return out;
    }

    /** {@code wrapper.getChunk()}, or {@code null} if DH's wrapper can't be read. Never throws. */
    private static ChunkAccess chunkOf(Object wrapper) {
        if (wrapper == null || wrapperUnresolvable) {
            return null;
        }
        try {
            Method m = wrapperGetChunk;
            if (m == null || wrapperClass != wrapper.getClass()) {
                m = wrapper.getClass().getMethod("getChunk");
                m.setAccessible(true);
                wrapperGetChunk = m;
                wrapperClass = wrapper.getClass();
            }
            Object chunk = m.invoke(wrapper);
            return chunk instanceof ChunkAccess ca ? ca : null;
        } catch (Throwable t) {
            if (!wrapperUnresolvable) {
                wrapperUnresolvable = true;
                LOGGER.warn("[SuperChunk-Compat] [dh] could not read {}.getChunk() — DH group prefetch disabled for "
                                + "this run (DH generates exactly as it did without SuperChunk's DH seam).",
                        wrapper.getClass().getName(), t);
            }
            return null;
        }
    }

    /**
     * The {@link ServerLevel} a DH-generated chunk belongs to. DH constructs its generation
     * ProtoChunks with the {@code ServerLevel} as their {@code LevelHeightAccessor}
     * ({@code ChunkFileReader.CreateProtoChunk(ServerLevel, ChunkPos)}), so the chunk carries its
     * own level — no DH type is needed to find it. Read through
     * {@link IChunkAccessLevelHeight}, NOT {@code getHeightAccessorForGeneration()}: on a
     * {@code ProtoChunk} the public method returns the chunk itself. {@code null} if that ever
     * stops holding, and the climate prefetch is then simply skipped.
     */
    private static ServerLevel levelOf(ChunkAccess chunk) {
        try {
            return ((IChunkAccessLevelHeight) chunk).superchunk$getLevelHeightAccessor()
                    instanceof ServerLevel sl ? sl : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Final tally for the boot/shutdown log. No-op when the seam never ran. */
    public static void logSummary(String reason) {
        long chunks = chunksSubmitted.get();
        if (chunks == 0) {
            return;
        }
        LOGGER.info("[SuperChunk-Compat] [dh] ({}) DH batch-generation prefetch: {} chunks submitted across "
                        + "{} biome group(s) + {} noise group(s); {} group wait timeout(s).",
                reason, chunks, biomeGroups.get(), noiseGroups.get(), waitTimeouts.get());
    }
}
