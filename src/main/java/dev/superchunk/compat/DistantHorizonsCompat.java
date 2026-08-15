package dev.superchunk.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interop with <b>Distant Horizons</b> ({@code distanthorizons}) — the LOD mod that renders
 * terrain far beyond the vanilla render distance and, to do it, <i>generates world chunks of its
 * own</i>.
 *
 * <p>DH and SuperChunk both boot fine together out of the box (see {@code compatibility.txt});
 * this class is about the second half — making DH's world generation actually USE SuperChunk.
 * DH has two generation routes and they meet SuperChunk very differently:
 *
 * <h2>1. {@code distantGeneratorMode = INTERNAL_SERVER} (DH's default and most compatible mode)</h2>
 * DH asks the live server for each chunk: it adds its own {@code dh_server_gen_ticket} at level 33
 * on the server thread, runs the distance-manager updates, and calls
 * {@code ChunkHolder.scheduleChunkGenerationTask(FULL, chunkMap)}. SuperChunk's C2ME chunk-system
 * rewrite implements exactly that entry point
 * ({@code NewChunkHolderVanillaInterface.scheduleChunkGenerationTask} routes it to the FlowSched
 * status machine), so those requests are served by the full SuperChunk pipeline — every worldgen
 * optimization and the GPU offload included. Nothing extra is needed for the generation itself.
 *
 * <p>What DH gets wrong in this mode is only its <b>self-report</b>: it looks for a mod literally
 * named {@code c2me} and, not finding one, prints "C2ME missing, low CPU usage and slow world gen
 * speeds expected" to the log AND to the player's chat. SuperChunk <i>is</i> a C2ME merge, so that
 * warning is false and actively misleading. {@link DhC2meAccessor} binds SuperChunk into DH's own
 * mod-accessor registry to silence it.
 *
 * <h2>2. {@code SURFACE} / {@code FEATURES} modes (DH's own batch generator)</h2>
 * Here DH never touches the server chunk system: its {@code BatchGenerationEnvironment} runs the
 * vanilla generation steps itself, on its own thread pool, over a GROUP of chunks per step
 * ({@code StepBiomes.generateGroup}, {@code StepNoise.generateGroup}, …). Those calls do land on
 * SuperChunk's mixins — the merged C2ME/Lithium/Noisium CPU optimizations and the per-chunk GPU
 * density path all engage — but the two CROSS-CHUNK GPU paths did not, because they are wired into
 * the chunk system's status delegate
 * ({@code VanillaWorldGenerationDelegate}), which DH bypasses. So DH-generated chunks paid
 * one GPU dispatch per chunk and, more importantly, never received the batched decide-kernel
 * block ids (compact ids), which only ride a batched submit. {@link DhWorldGenBridge} adds the
 * missing seam: it prefetches a whole DH group at the group boundary through the very same
 * submit/deposit code the chunk system uses.
 *
 * <p><b>Flags</b> (all default ON; each is a bisection kill-switch, and every one of them failing
 * closed just restores today's behaviour):
 * <ul>
 *   <li>{@code -Dsuperchunk.compat.distantHorizons=false} — disable this compat entirely.</li>
 *   <li>{@code -Dsuperchunk.dh.batchPrefetch=false} — keep the DH group seam off (DH chunks then
 *       ride the per-chunk GPU path, i.e. the pre-integration behaviour).</li>
 *   <li>{@code -Dsuperchunk.dh.c2meAccessor=false} — leave DH's "C2ME missing" warning alone.</li>
 *   <li>{@code -Dsuperchunk.dh.waitMillis=<n>} — cap (default 2000 ms) on how long one DH thread
 *       waits for its group's batched dispatches before giving up and generating normally.</li>
 * </ul>
 *
 * <p>Detection is by mod id via {@link LoadingModList} (the early API {@link ByePregenCompat} and
 * {@link StructureLayoutOptimizerCompat} already use), so it resolves at mixin-plugin time —
 * before the DH mixins would apply. Never throws: any trouble is logged and DH is treated as
 * absent, which is the shipped, unchanged behaviour.
 */
public final class DistantHorizonsCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    /** Distant Horizons' mod id (same on Fabric and NeoForge — it ships one merged jar). */
    public static final String MOD_ID = "distanthorizons";

    /** Master switch for the whole DH compat (default {@code true}). */
    private static final boolean COMPAT_ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.compat.distantHorizons", "true"));

    /** True when Distant Horizons is on the mod list. Resolved once, defensively. */
    public static final boolean INSTALLED = detectInstalled();

    /** Whether the DH batch-generator group prefetch seam may arm (default {@code true}). */
    public static final boolean BATCH_PREFETCH =
            Boolean.parseBoolean(System.getProperty("superchunk.dh.batchPrefetch", "true"));

    /** Whether to bind SuperChunk as DH's C2ME accessor (default {@code true}). */
    public static final boolean BIND_C2ME_ACCESSOR =
            Boolean.parseBoolean(System.getProperty("superchunk.dh.c2meAccessor", "true"));

    /**
     * Upper bound (ms) on a DH worldgen thread's wait for its group's batched GPU dispatches.
     * The batcher's own flush window is sub-millisecond and every request completes (or fails)
     * on its own, so this is a belt-and-braces cap, not the expected wait. Clamped to [1, 60000].
     */
    public static final long WAIT_MILLIS = clampWait(
            Long.getLong("superchunk.dh.waitMillis", 2000L));

    /** One-shot guard so the boot line is logged exactly once. */
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    private DistantHorizonsCompat() {
    }

    private static boolean detectInstalled() {
        try {
            return LoadingModList.get().getModFileById(MOD_ID) != null;
        } catch (Throwable t) {
            // If the mod list can't be read this early, fail toward shipped behavior (DH absent).
            LOGGER.debug("[SuperChunk-Compat] Could not query the mod list for '{}' — assuming absent.", MOD_ID, t);
            return false;
        }
    }

    private static long clampWait(long v) {
        return Math.max(1L, Math.min(60_000L, v));
    }

    /** True when DH is installed and this compat is enabled — the gate every DH hook consults. */
    public static boolean active() {
        return INSTALLED && COMPAT_ENABLED;
    }

    /**
     * Whether the DH batch-generator mixins ({@code mixin.compat.MixinDhStep*}) should apply.
     * Consulted from {@link dev.superchunk.SuperChunkMixinPlugin#shouldApplyMixin}, so with DH
     * absent (or the compat disabled) the hooks are never installed at all.
     */
    public static boolean applyBatchGeneratorHooks() {
        return active() && BATCH_PREFETCH;
    }

    /** One boot line stating exactly what the DH compat engaged. No-op when DH is absent. */
    public static void logBootLine() {
        if (!INSTALLED || !LOGGED.compareAndSet(false, true)) {
            return;
        }
        if (!COMPAT_ENABLED) {
            LOGGER.info("[SuperChunk-Compat] Distant Horizons detected, but SuperChunk's DH compat is DISABLED "
                    + "(-Dsuperchunk.compat.distantHorizons=false). DH still works; its own batch generator just "
                    + "uses the per-chunk GPU path and it will keep reporting \"C2ME missing\".");
            return;
        }
        LOGGER.info("[SuperChunk-Compat] Distant Horizons detected — DH interop ON. "
                        + "INTERNAL_SERVER generation already runs through SuperChunk's chunk system; "
                        + "DH's own batch generator now prefetches each generation GROUP through the cross-chunk GPU "
                        + "batcher (batchPrefetch={}, waitMillis={}), and SuperChunk registers itself as DH's C2ME "
                        + "accessor (c2meAccessor={}) so DH stops reporting \"C2ME missing\".",
                BATCH_PREFETCH, WAIT_MILLIS, BIND_C2ME_ACCESSOR);
    }
}
