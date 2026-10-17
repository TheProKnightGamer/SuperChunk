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
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
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
 * any scheduler), but the two BATCHED paths did not: both are wired into the generation seam
 * ({@code MixinNoiseBasedChunkGeneratorGpuSeam} / {@code VanillaGenSeam} on this tree), which DH
 * bypasses entirely. The cost of that gap is not just one dispatch per chunk instead of one per
 * ~32 — the batched submit is also the ONLY carrier of the chained decide-kernel block ids (compact
 * ids), so DH chunks re-ran the whole CPU decide loop that server-generated chunks skip.
 *
 * <h2>What it does</h2>
 * A group boundary is a natural batch: every chunk in it is about to run the same step. So as each
 * group is filtered (one call per group, immediately before the generation loop) we submit ALL of
 * its chunks through the exact same submit/deposit code the chunk-system seam uses
 * ({@link GpuBatchPrefetch#submitForChunk} / {@link GpuClimatePrefetch#submitForChunk}), then wait
 * for the deposits before returning. DH's per-chunk {@code createBiomes}/{@code fillFromNoise}
 * calls then consume them from {@code GpuClimateStore}/{@code GpuBatchStore} with no per-chunk
 * dispatch at all — the same consume path, byte for byte, that a server-generated chunk takes.
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
 *
 * <h2>Forge 1.20.1 port note — TWO group seams feed this class</h2>
 * The DH line that targets 1.20.1 is older than the DH 3.2.0-b the NeoForge seam was written
 * against, and the two generations hand the group over in different shapes, so there are two entry
 * points:
 * <ul>
 *   <li>{@link #onGroup(ChunkStatus, List)} — the NeoForge shape. DH 3.x's
 *       {@code AbstractWorldGenStep.getChunkWrappersToGenerate} returns DH CHUNK WRAPPERS, which
 *       this unwraps reflectively ({@code MixinDhWorldGenStep}).</li>
 *   <li>{@link #onChunkGroup(ChunkStatus, List)} — the DH 2.3.x shape. There is no
 *       {@code AbstractWorldGenStep} on that line; each step class inlines its own filter loop into
 *       {@code generateGroup} and collects plain {@link ChunkAccess}es into a local
 *       {@code ArrayList}, so the seam hands that list over directly — no reflection, and no DH
 *       type in any descriptor ({@code MixinDhStepBiomes}/{@code MixinDhStepNoise}).</li>
 * </ul>
 * Both converge on {@link #prefetchGroup}, so there is exactly one implementation of the submit,
 * the wait and the cancel.
 *
 * <p><b>The parity precondition holds on the 2.3.x line too</b> (bytecode-verified on
 * {@code DistantHorizons-2.3.4-b-1.20.1-fabric-forge.jar}): DH's {@code GlobalParameters} takes its
 * {@code randomState} and {@code generator} straight from
 * {@code serverLevel.getChunkSource().randomState()} / {@code .getGenerator()} — the very objects
 * {@link #prefetchBiomes} resolves from {@link #levelOf} — and its steps pass exactly those into
 * {@code createBiomes}/{@code fillFromNoise}. So the grids prefetched here are computed from the
 * same router the consuming call will sample; the deposit can never be a different dimension's or a
 * different seed's.
 */
public final class DhWorldGenBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    /**
     * {@code ChunkWrapper.getChunk()} on DH's chunk wrapper. Resolved once per wrapper class
     * (there is exactly one at runtime; the field is a single-entry cache, and a second class
     * would simply re-resolve). Reflection because SuperChunk does not compile against DH — and
     * because DH's class NAME differs between its jar layouts (2.x
     * {@code loaderCommon.forge.…ChunkWrapper}, 3.x {@code …ChunkWrapper_neoforge}).
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
    // Group seams (called from mixin.compat.MixinDh*).
    // ------------------------------------------------------------------

    /**
     * DH 3.x entry point: DH has just filtered {@code wrappers} down to the chunks its
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
            prefetchGroup(stepStatus, wrappers, true);
        } catch (Throwable t) {
            // The DH path must never break DH generation.
            LOGGER.debug("[SuperChunk-Compat] [dh] group prefetch threw — DH generates as before.", t);
        }
    }

    /**
     * DH 2.3.x entry point: the step's own {@code chunksToDo} list — already filtered by DH, already
     * plain {@link ChunkAccess}es — handed over at the boundary between DH's filter loop and its
     * generation loop.
     *
     * <p>Elements that are not already a {@code ProtoChunk} are ignored rather than unwrapped —
     * the same sanity check {@link #unwrapChunks} applies on the 3.x path, and a deliberate
     * discriminator besides: the seam is an {@code @Redirect} on the
     * {@code ArrayList.iterator()} that starts the generation loop, and if a future DH build were
     * to make its FIRST (wrapper) loop iterate an {@code ArrayList} too, the redirect could fire
     * on the unfiltered wrapper list. Refusing anything that is not already a chunk means that
     * mis-bind prefetches nothing at all instead of prefetching chunks the step will not touch.
     * Never throws.
     */
    public static void onChunkGroup(ChunkStatus stepStatus, List<?> chunks) {
        if (stepStatus == null || chunks == null || chunks.isEmpty()
                || !DistantHorizonsCompat.applyBatchGeneratorHooks()) {
            return;
        }
        try {
            prefetchGroup(stepStatus, chunks, false);
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-Compat] [dh] group prefetch threw — DH generates as before.", t);
        }
    }

    /**
     * The one implementation both seams converge on. Only the two steps SuperChunk can serve from
     * batched GPU grids are handled — BIOMES (climate quart grids, consumed by
     * {@code createBiomes}' climate sampling) and NOISE (fused density corner grids + compact ids,
     * consumed by {@code fillFromNoise}). Every other DH step returns BEFORE the list is resolved,
     * so a structure/surface/features group costs nothing at all.
     *
     * <p>The step's BATCHING GATE is likewise checked before the list is resolved. That ordering is
     * load-bearing for the cost profile the NeoForge original has: there the gate lives at the top
     * of {@code prefetchBiomes}/{@code prefetchNoise}, which are entered with the raw list, so a
     * group whose batcher isn't live never pays for the unwrap. On the 3.x seam "unwrap" is a
     * reflective {@code getChunk()} per chunk, so with GPU batching off (or not yet warm — the
     * fused-kernel warm-up window is real, see PORT-NOTES) that would otherwise be a per-group tax
     * on DH for no benefit. The gates are left in place in the leaf methods too; they are volatile
     * reads and the duplication keeps each method correct on its own.
     *
     * @param wrapped whether {@code raw} holds DH chunk wrappers (3.x seam) or plain chunks (2.3.x)
     */
    private static void prefetchGroup(ChunkStatus stepStatus, List<?> raw, boolean wrapped) {
        if (stepStatus == ChunkStatus.BIOMES) {
            if (!BiomeClimateCache.isBatchClimateActive()) {
                return;
            }
        } else if (stepStatus == ChunkStatus.NOISE) {
            if (!ChunkGridCache.isBatchEnabled() || ChunkGridCache.liveBatcher() == null) {
                return;
            }
        } else {
            return;
        }
        List<ChunkAccess> chunks = wrapped ? unwrapChunks(raw) : directChunks(raw);
        if (chunks.isEmpty()) {
            return;
        }
        if (stepStatus == ChunkStatus.BIOMES) {
            prefetchBiomes(chunks);
        } else {
            prefetchNoise(chunks);
        }
    }

    /**
     * The 2.3.x list, already plain chunks — the same {@code ProtoChunk} sanity check
     * {@link #unwrapChunks} applies, and nothing else.
     */
    private static List<ChunkAccess> directChunks(List<?> chunks) {
        List<ChunkAccess> out = new ArrayList<>(chunks.size());
        for (Object o : chunks) {
            if (o instanceof ProtoChunk) {
                out.add((ChunkAccess) o);
            }
        }
        return out;
    }

    /**
     * BIOMES: batch-prefetch each chunk's climate quart grids before DH runs {@code createBiomes}
     * over the group. No-op whenever climate batching isn't live.
     */
    private static void prefetchBiomes(List<ChunkAccess> chunks) {
        if (!BiomeClimateCache.isBatchClimateActive()) {
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
    private static void prefetchNoise(List<ChunkAccess> chunks) {
        if (!ChunkGridCache.isBatchEnabled() || ChunkGridCache.liveBatcher() == null) {
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
    private static List<ChunkAccess> unwrapChunks(List<?> wrappers) {
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
            return chunk instanceof ChunkAccess ? (ChunkAccess) chunk : null;
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
     * ProtoChunks with the {@code ServerLevel} as their {@code LevelHeightAccessor} — on the 2.3.x
     * line {@code BatchGenerationEnvironment.CreateEmptyChunk(ServerLevel, ChunkPos)} does
     * {@code new ProtoChunk(pos, UpgradeData.EMPTY, serverLevel, biomeRegistry, null)}
     * (bytecode-verified on 2.3.4-b), on 3.x it is {@code ChunkFileReader.CreateProtoChunk} — so
     * the chunk carries its own level and no DH type is needed to find it. Read through
     * {@link IChunkAccessLevelHeight}, NOT {@code getHeightAccessorForGeneration()}: on a
     * {@code ProtoChunk} the public method returns the chunk itself. {@code null} if that ever
     * stops holding, and the climate prefetch is then simply skipped.
     */
    private static ServerLevel levelOf(ChunkAccess chunk) {
        try {
            Object accessor = ((IChunkAccessLevelHeight) chunk).superchunk$getLevelHeightAccessor();
            return accessor instanceof ServerLevel ? (ServerLevel) accessor : null;
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
