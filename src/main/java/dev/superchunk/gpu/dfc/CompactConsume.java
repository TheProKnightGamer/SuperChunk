package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.compat.LithiumBlockTracking;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

/**
 * SuperChunk GPU — <b>COMPACT-IDS CONSUME PATH</b> (STAGE 6 of the GPU-AHEAD plan,
 * {@code -Dsuperchunk.gpu.compactIds=on|verify}).
 *
 * <p><b>What the original loop does</b> (vanilla {@code NoiseBasedChunkGenerator.doFill},
 * per block, in cellX &rarr; cellZ &rarr; cellY-desc &rarr; yInCell-desc &rarr; xInCell
 * &rarr; zInCell order): advance the {@code NoiseChunk} interpolators
 * ({@code advanceCellX}/{@code selectCellYZ}/{@code updateForY/X/Z} + {@code swapSlices}),
 * {@code getInterpolatedState()} = {@code MaterialRuleList.calculate} (aquifer filler,
 * then ore-vein filler, {@code null} &rarr; {@code settings.defaultBlock()}), skip
 * {@code AIR}, then per written block: Noisium's direct palette write
 * ({@code palette().idFor} PER BLOCK + {@code BitStorage.set}) + section counters
 * ({@code nonEmptyBlockCount}/{@code tickingFluidCount}/{@code tickingBlockCount}) +
 * Lithium {@code lithium$trackBlockStateChange} (the round-4 blocktracking-bypass fix),
 * both WG heightmap {@code update}s, and {@code markPosForPostprocessing} when
 * {@code aquifer.shouldScheduleFluidUpdate()} on a fluid block.
 *
 * <p><b>What the consume path does instead</b>, when {@link GpuBatchStore} holds this
 * chunk's Stage-5 decide-kernel ids (1 byte/block: id 0..9 | bit7 = sched; 10 =
 * beard-sentinel; 255 = absent):
 * <ul>
 *   <li><b>FAST path</b> (no sentinel byte — the vast majority): the per-block decision
 *       machinery is skipped ENTIRELY (no interpolation is ever started). Per section:
 *       byte histogram &rarr; palette {@code idFor} once per state (&le;11 states), bulk
 *       {@code BitStorage} word writes (4-bit sections: one long per (y,z) row), counters
 *       from the histogram, Lithium flags via the exact-arithmetic bulk tracker. Then
 *       per-column top-down scans over the id array prime WORLD_SURFACE_WG (first
 *       non-air) and OCEAN_FLOOR_WG (first motion-blocking — water/lava do NOT block
 *       motion; the actual {@code Heightmap.Types} predicates are used, never
 *       hand-coded), and bit7 bytes on fluid blocks are marked for postprocessing.</li>
 *   <li><b>HYBRID path</b> (chunk has beard-sentinel bytes): the vanilla loop skeleton
 *       runs — slices advance for every cellX plane — but per CELL: cells containing a
 *       sentinel run the FULL ORIGINAL per-block path ({@code selectCellYZ} +
 *       {@code updateForY/X/Z} + {@code MaterialRuleList.calculate} + real
 *       {@code aquifer.shouldScheduleFluidUpdate()}), all other cells consume the mapped
 *       byte with NO interpolator work (selectCellYZ/updateFor* are per-cell-pure given
 *       the slices, so skipping them for consumed cells cannot perturb sentinel cells).
 *       Writes use the exact Noisium-redirect sequence per block. Heightmaps and
 *       postprocess marks are deferred to after the loop (identical final values;
 *       fault-clean safe).</li>
 * </ul>
 *
 * <p><b>Guard rails.</b> Consume runs only when: ids are present AND complete (any
 * absent/unknown byte &rarr; original loop, counted), the id lattice matches the fill's
 * exact domain ({@code ox/oz} = chunk origin, {@code oy == minCellY*cellHeight},
 * {@code cellCountY} match, 16x16 plane, section-aligned Y range), and no debug-void.
 * Plan/vein coverage is enforced UPSTREAM: a veins-enabled chunk only ever receives ids
 * from a veins-resolved plan ({@code GpuBatchDispatcher.enqueueDecide}), and datapack
 * fluids/dimensions never capture aux. ANY fault mid-consume restores the touched
 * sections to air, sticky-disables the consume path, and falls back to the original
 * loop — heightmaps/marks are written only after all block writes succeeded.
 *
 * <p><b>VERIFY mode</b> ({@code compactIds=verify}): the ORIGINAL loop runs and ships;
 * afterwards this class recomputes every consume-path side effect (palette blocks per
 * consumed cell, both heightmaps per column, all three section counters, Lithium
 * tracked-flag booleans, the postprocessing set) and compares against the shipped
 * chunk. Logged as {@code [compact-consume-verify]} with per-class mismatch counts;
 * ZERO divergence is the Stage-6 gate before any {@code on} A/B.
 *
 * <p>All consume log lines are greppable as {@code [compact-consume]}.
 */
public final class CompactConsume {

    private static final Logger LOG = LoggerFactory.getLogger("SuperChunk-CompactConsume");

    /** Byte alphabet (bits 0..6; bit7 = shouldScheduleFluidUpdate). Mirrors decide.cl. */
    private static final int ID_AIR = 1;
    private static final int ID_BEARD = 10;
    private static final int ID_ABSENT = 255;
    private static final String[] ID_NAME = {
            "stone", "air", "water", "lava", "copper_ore", "raw_copper", "granite",
            "deepslate_iron_ore", "raw_iron", "tuff", "beard-sentinel"};

    /** 8-bytes-at-a-time view over the ids array (SWAR scans; byte order irrelevant — all tests are per-byte). */
    private static final java.lang.invoke.VarHandle LONGS =
            java.lang.invoke.MethodHandles.byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

    // prepare()'s alphabet-scan classification (branchless accumulate; see prepare).
    private static final int PREP_ABSENT = 1;
    private static final int PREP_UNKNOWN = 2;
    private static final int PREP_SENTINEL = 4;
    private static final byte[] PREP_CLASS = new byte[256];

    static {
        for (int raw = 0; raw < 256; raw++) {
            int cls = 0;
            if (raw == ID_ABSENT) {
                cls = PREP_ABSENT;
            } else {
                int id = raw & 0x7F;
                if (id > ID_BEARD || (id == ID_BEARD && raw != ID_BEARD)) {
                    cls = PREP_UNKNOWN;
                } else if (id == ID_BEARD) {
                    cls = PREP_SENTINEL;
                }
            }
            PREP_CLASS[raw] = (byte) cls;
        }
    }

    /** Vanilla's exact skip identity: {@code blockstate != AIR} is a REFERENCE compare. */
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private CompactConsume() {
    }

    // =====================================================================
    // Counters ([compact-consume] / [compact-consume-verify]).
    // =====================================================================

    private static volatile boolean disabled = false;

    private static final LongAdder chunksFast = new LongAdder();
    private static final LongAdder chunksHybrid = new LongAdder();
    private static final LongAdder hybridCellsCpu = new LongAdder();
    private static final LongAdder hybridCellsConsumed = new LongAdder();
    private static final LongAdder sectionsBulk = new LongAdder();
    private static final LongAdder sectionsSetLoop = new LongAdder();   // storage.set fallback (non-4-bit)
    private static final LongAdder sectionsSlowWrite = new LongAdder(); // pre-populated section -> setBlockState loop
    private static final LongAdder consumeNanos = new LongAdder();

    // Fallback-to-original-loop reasons (chunk granularity).
    private static final LongAdder fbDisabled = new LongAdder();
    private static final LongAdder fbLithiumUnsafe = new LongAdder();
    private static final LongAdder fbNoEntry = new LongAdder();
    private static final LongAdder fbNoIds = new LongAdder();
    private static final LongAdder fbGeometry = new LongAdder();
    private static final LongAdder fbUnaligned = new LongAdder();
    private static final LongAdder fbLength = new LongAdder();
    private static final LongAdder fbAbsent = new LongAdder();
    private static final LongAdder fbUnknownByte = new LongAdder();
    private static final LongAdder fbDebugVoid = new LongAdder();
    private static final LongAdder faults = new LongAdder();

    // Verify accounting.
    private static final LongAdder vChunks = new LongAdder();
    private static final LongAdder vChunksHybridModel = new LongAdder();
    private static final LongAdder vSkipped = new LongAdder();
    private static final LongAdder vBlocksCompared = new LongAdder();
    private static final LongAdder vBlockMismatch = new LongAdder();
    private static final LongAdder vHeightSurfMismatch = new LongAdder();
    private static final LongAdder vHeightFloorMismatch = new LongAdder();
    private static final LongAdder vNonEmptyMismatch = new LongAdder();
    private static final LongAdder vFluidCountMismatch = new LongAdder();
    private static final LongAdder vTickingCountMismatch = new LongAdder();
    private static final LongAdder vLithiumFlagMismatch = new LongAdder();
    private static final LongAdder vPostCompared = new LongAdder();
    private static final LongAdder vPostMissing = new LongAdder();
    private static final LongAdder vPostExtra = new LongAdder();
    private static final LongAdder vMismatchChunks = new LongAdder();
    private static final AtomicLong vLogBudget = new AtomicLong(32);
    private static final AtomicLong nextReportAt = new AtomicLong(1024);

    // ---- FLIP CENSUS (verify mode doubles as the fp32-decide parity census: block
    // mismatches ARE the flips; the fp64 decide chain measures all-zero here). Per-chunk
    // flip-count distribution (bucket upper bounds, <=), worst chunk, hard-gate classes
    // (any lava involvement / any vein-id disagreement / any flip above the Y<=106
    // tie band of the standard overworld), and the predicted-id x shipped-class matrix
    // (round-4 lesson: enumerate every class, never let one vanish silently). ----
    private static final long[] FLIP_BUCKETS = {0, 2, 5, 10, 25, 50, 200, Long.MAX_VALUE};
    private static final String[] FLIP_BUCKET_LABELS = {"0", "1-2", "3-5", "6-10", "11-25", "26-50", "51-200", ">200"};
    private static final AtomicLongArray flipHisto = new AtomicLongArray(FLIP_BUCKETS.length);
    private static final AtomicLong flipMaxPerChunk = new AtomicLong();
    private static final LongAdder flipsLavaClass = new LongAdder();
    private static final LongAdder flipsVeinClass = new LongAdder();
    private static final LongAdder flipsAboveY106 = new LongAdder();
    private static final String[] SHIPPED_CLASS_NAMES = {"stone", "air", "water", "lava", "vein", "other"};
    private static final AtomicLongArray flipMatrix = new AtomicLongArray(11 * SHIPPED_CLASS_NAMES.length);

    // =====================================================================
    // Per-chunk context: validation shared by consume and verify.
    // =====================================================================

    private enum Reason {
        OK, DISABLED, DEBUG_VOID, NO_ENTRY, NO_IDS, GEOMETRY, UNALIGNED, LENGTH, ABSENT, UNKNOWN_BYTE
    }

    private static final class Ctx {
        Reason reason;
        byte[] ids;
        GpuBatchStore.Key key;
        int ox, oy, oz;          // block origin of the id lattice (== chunk origin, minCellY*cellHeight)
        int cellWidth, cellHeight;
        int cellsXZ;             // 16 / cellWidth
        int cellCountY;
        int fullY;               // cellCountY * cellHeight
        boolean hasSentinel;
        boolean[] cellCpu;       // [((cellY*cellsXZ)+cellXi)*cellsXZ+cellZi] — sentinel cells (CPU-decided)

        static Ctx fail(Reason r) {
            Ctx c = new Ctx();
            c.reason = r;
            return c;
        }
    }

    /**
     * Validates the chunk against its stored ids. NEVER mutates the store (the caller
     * decides whether/when to {@link GpuBatchStore#remove}). Returns a ctx whose
     * {@link Ctx#reason} is {@link Reason#OK} iff the consume path may run.
     */
    private static Ctx prepare(ChunkAccess chunk, NoiseChunk nc, int minCellY, int cellCountY) {
        ChunkPos cp = chunk.getPos();
        if (SharedConstants.debugVoidTerrain(cp)) {
            return Ctx.fail(Reason.DEBUG_VOID);
        }
        if (nc == null) {
            return Ctx.fail(Reason.NO_ENTRY);
        }
        GpuBatchStore.Extent ext = GpuBatchStore.Extent.derive(nc);
        if (ext == null) {
            return Ctx.fail(Reason.GEOMETRY);
        }
        // The id lattice must be EXACTLY the fill's domain: chunk-origin horizontal
        // 16x16 plane, vertical origin/extent equal to doFill's (minCellY, cellCountY).
        if (ext.sx != ext.sz || ext.sx <= 0 || ext.sy <= 0
                || (ext.dimX - 1) * ext.sx != 16 || (ext.dimZ - 1) * ext.sz != 16
                || ext.ox != cp.getMinBlockX() || ext.oz != cp.getMinBlockZ()
                || ext.oy != minCellY * ext.sy || (ext.dimY - 1) != cellCountY) {
            return Ctx.fail(Reason.GEOMETRY);
        }
        int fullY = cellCountY * ext.sy;
        if (fullY <= 0 || (ext.oy & 15) != 0 || (fullY & 15) != 0) {
            // Non-section-aligned noise range (exotic datapack): the bulk section writer
            // does not model partial sections — original loop.
            return Ctx.fail(Reason.UNALIGNED);
        }
        GpuBatchStore.Key key = GpuBatchStore.key(cp, ext.ox, ext.oy, ext.oz,
                ext.sx, ext.sy, ext.sz, ext.dimX, ext.dimY, ext.dimZ);
        GpuBatchStore.Stored stored = GpuBatchStore.get(key);
        if (stored == null) {
            return Ctx.fail(Reason.NO_ENTRY);
        }
        byte[] ids = stored.ids;
        if (ids == null) {
            return Ctx.fail(Reason.NO_IDS);
        }
        if (ids.length != 256 * fullY) {
            return Ctx.fail(Reason.LENGTH);
        }
        // Alphabet scan: completeness (no absent), validity (0..10; sentinel carries no
        // sched bit), sentinel presence. Table-classified branchless accumulate (the
        // branchy per-byte form was the profile's top single frame at 24 workers); on
        // the RARE failure the byte-exact rescan below reproduces the ORIGINAL
        // first-offending-byte precedence, so the returned Reason is identical.
        int acc = 0;
        for (byte idByte : ids) {
            acc |= PREP_CLASS[idByte & 0xFF];
        }
        if ((acc & (PREP_ABSENT | PREP_UNKNOWN)) != 0) {
            for (byte idByte : ids) {
                int raw = idByte & 0xFF;
                if (raw == ID_ABSENT) {
                    return Ctx.fail(Reason.ABSENT);
                }
                int id = raw & 0x7F;
                if (id > ID_BEARD || (id == ID_BEARD && raw != ID_BEARD)) {
                    return Ctx.fail(Reason.UNKNOWN_BYTE);
                }
            }
        }
        boolean sentinel = (acc & PREP_SENTINEL) != 0;
        Ctx c = new Ctx();
        c.reason = Reason.OK;
        c.ids = ids;
        c.key = key;
        c.ox = ext.ox;
        c.oy = ext.oy;
        c.oz = ext.oz;
        c.cellWidth = ext.sx;
        c.cellHeight = ext.sy;
        c.cellsXZ = 16 / ext.sx;
        c.cellCountY = cellCountY;
        c.fullY = fullY;
        c.hasSentinel = sentinel;
        if (sentinel) {
            c.cellCpu = computeCpuCells(c);
        }
        return c;
    }

    /** Marks every cell containing a beard-sentinel byte (those run the ORIGINAL per-block path). */
    private static boolean[] computeCpuCells(Ctx c) {
        boolean[] cpu = new boolean[c.cellCountY * c.cellsXZ * c.cellsXZ];
        byte[] ids = c.ids;
        for (int by = 0; by < c.fullY; by++) {
            int cy = by / c.cellHeight;
            int rowBase = by * 256;
            for (int bx = 0; bx < 16; bx++) {
                int cx = bx / c.cellWidth;
                int base = rowBase + bx * 16;
                for (int bz = 0; bz < 16; bz++) {
                    if ((ids[base + bz] & 0x7F) == ID_BEARD) {
                        cpu[((cy * c.cellsXZ) + cx) * c.cellsXZ + (bz / c.cellWidth)] = true;
                    }
                }
            }
        }
        return cpu;
    }

    private static void countFallback(Reason r) {
        switch (r) {
            case DISABLED -> fbDisabled.increment();
            case DEBUG_VOID -> fbDebugVoid.increment();
            case NO_ENTRY -> fbNoEntry.increment();
            case NO_IDS -> fbNoIds.increment();
            case GEOMETRY -> fbGeometry.increment();
            case UNALIGNED -> fbUnaligned.increment();
            case LENGTH -> fbLength.increment();
            case ABSENT -> fbAbsent.increment();
            case UNKNOWN_BYTE -> fbUnknownByte.increment();
            default -> {
            }
        }
    }

    // =====================================================================
    // Byte -> BlockState mapping (built once per chunk; <=11 states).
    // =====================================================================

    private static final class Mapping {
        final BlockState[] states = new BlockState[11];
        final boolean[] nonAir = new boolean[11];   // written at all (vanilla `!= AIR` reference check)
        final boolean[] fluid = new boolean[11];    // !getFluidState().isEmpty()
        final boolean[] ticking = new boolean[11];  // isRandomlyTicking()
        final boolean[] surf = new boolean[11];     // WORLD_SURFACE_WG predicate (on written blocks)
        final boolean[] floor = new boolean[11];    // OCEAN_FLOOR_WG predicate (on written blocks)

        Mapping(BlockState defaultBlock) {
            states[0] = defaultBlock;
            states[1] = AIR;
            states[2] = Blocks.WATER.defaultBlockState();
            states[3] = Blocks.LAVA.defaultBlockState();
            states[4] = Blocks.COPPER_ORE.defaultBlockState();
            states[5] = Blocks.RAW_COPPER_BLOCK.defaultBlockState();
            states[6] = Blocks.GRANITE.defaultBlockState();
            states[7] = Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
            states[8] = Blocks.RAW_IRON_BLOCK.defaultBlockState();
            states[9] = Blocks.TUFF.defaultBlockState();
            states[10] = null;   // beard-sentinel: never mapped (CPU decides those cells)
            Predicate<BlockState> surfPred = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
            Predicate<BlockState> floorPred = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
            for (int b = 0; b <= 9; b++) {
                nonAir[b] = states[b] != AIR;
                fluid[b] = nonAir[b] && !states[b].getFluidState().isEmpty();
                ticking[b] = nonAir[b] && states[b].isRandomlyTicking();
                surf[b] = nonAir[b] && surfPred.test(states[b]);
                floor[b] = nonAir[b] && floorPred.test(states[b]);
            }
        }
    }

    // =====================================================================
    // CONSUME entry (mode `on`). Called from the doFill HEAD mixin.
    // =====================================================================

    /**
     * Consumes this chunk's stored ids instead of running the per-block decision
     * machinery. Returns {@code true} iff the fill was fully performed here (the mixin
     * then cancels the original loop). {@code false} = run the original loop (all
     * fallback reasons counted). Never throws.
     */
    public static boolean consumeFill(NoiseBasedChunkGenerator gen, ChunkAccess chunk,
                                      NoiseChunk nc, int minCellY, int cellCountY) {
        if (!CompactIds.CONSUME) {
            return false;
        }
        if (disabled) {
            fbDisabled.increment();
            return false;
        }
        if (LithiumBlockTracking.directWritesUnsafe()) {
            // A foreign (standalone) Lithium counting mixin is live but UNBINDABLE — the bulk
            // writers would leave its counters stale. Original loop instead (whose per-block
            // writes take the real setBlockState path in this mode; see the Noisium redirect).
            fbLithiumUnsafe.increment();
            return false;
        }
        try {
            Ctx ctx = prepare(chunk, nc, minCellY, cellCountY);
            if (ctx.reason != Reason.OK) {
                countFallback(ctx.reason);
                return false;
            }
            Mapping m = new Mapping(gen.generatorSettings().value().defaultBlock());
            long t0 = System.nanoTime();
            if (!ctx.hasSentinel) {
                // FAST path: point of no return — take the entry (releases any pooled
                // field holder), then write. On fault: restore + sticky-disable +
                // original loop (which now uses the per-chunk grid path).
                GpuBatchStore.remove(ctx.key);
                try {
                    fastFill(chunk, ctx, m);
                    chunksFast.increment();
                } catch (Throwable t) {
                    fault(chunk, ctx, null, t);
                    return false;
                }
            } else {
                // HYBRID path: the store entry is deliberately LEFT in place — the
                // vanilla-skeleton loop's initializeForFirstCellX triggers
                // ChunkGridCache.beginChunk, which consumes it to serve the slice fills.
                try {
                    hybridFill(chunk, nc, ctx, m, minCellY);
                    chunksHybrid.increment();
                } catch (Throwable t) {
                    fault(chunk, ctx, nc, t);
                    return false;
                }
            }
            consumeNanos.add(System.nanoTime() - t0);
            maybeReport();
            return true;
        } catch (Throwable t) {
            // prepare/mapping failed before any write — safe to run the original loop.
            faults.increment();
            disabled = true;
            LOG.error("[compact-consume] consume pre-flight FAULT — consume path sticky-DISABLED; original loop runs.", t);
            return false;
        }
    }

    /**
     * Mid-write fault: restore every section in the fill range to air (write phases are
     * ordered blocks -> heightmaps -> marks, so heightmaps/postprocessing are untouched
     * on a block-phase fault), close any started interpolation, sticky-disable, and let
     * the original loop rebuild the chunk from scratch.
     */
    private static void fault(ChunkAccess chunk, Ctx ctx, NoiseChunk nc, Throwable t) {
        faults.increment();
        disabled = true;
        LOG.error("[compact-consume] consume FAULT mid-fill — restoring sections to air, sticky-DISABLING the "
                + "consume path; the original loop rebuilds this chunk.", t);
        try {
            if (nc != null && ((IChunkNoiseSampler) nc).getIsInInterpolationLoop()) {
                nc.stopInterpolation();   // also fires ChunkGridCache.endChunk cleanup
            }
        } catch (Throwable ignored) {
        }
        try {
            for (int yBase = ctx.oy; yBase < ctx.oy + ctx.fullY; yBase += 16) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(yBase));
                if (section.hasOnlyAir()) {
                    continue;
                }
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (section.getBlockState(x, y, z) != AIR) {
                                section.setBlockState(x, y, z, AIR, false);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t2) {
            LOG.error("[compact-consume] section restore ALSO failed — chunk gen will surface the original fault.", t2);
            throw new IllegalStateException("compact-consume fault recovery failed", t);
        }
    }

    // =====================================================================
    // FAST path: pure array-driven fill (no interpolation at all).
    // =====================================================================

    private static void fastFill(ChunkAccess chunk, Ctx ctx, Mapping m) {
        // Same creation order as vanilla doFill.
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surf = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        byte[] ids = ctx.ids;

        int[] hist = new int[11];
        for (int yBase = ctx.oy; yBase < ctx.oy + ctx.fullY; yBase += 16) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(yBase));
            int base = (yBase - ctx.oy) * 256;
            java.util.Arrays.fill(hist, 0);
            for (int i = 0; i < 4096; i++) {
                hist[ids[base + i] & 0x7F]++;
            }
            int nonAirCount = 0;
            for (int b = 0; b <= 9; b++) {
                if (m.nonAir[b]) {
                    nonAirCount += hist[b];
                }
            }
            if (nonAirCount == 0) {
                continue;   // vanilla writes nothing into an all-air section
            }
            writeSection(section, ids, base, hist, m, nonAirCount);
        }

        heightmapsFromIds(ctx, m, surf, floor);
        marksFromIds(chunk, ctx, m);
    }

    /**
     * Writes one section from the id array. Bulk word path when the section is provably
     * fresh (single-value AIR palette — every position is AIR, so the Lithium previous
     * state is AIR for all); {@code setBlockState} loop otherwise (exact for ANY
     * pre-existing content, mirroring vanilla's overwrite-non-air-only semantics).
     */
    private static void writeSection(LevelChunkSection section, byte[] ids, int base,
                                     int[] hist, Mapping m, int nonAirCount) {
        PalettedContainer<BlockState> states = section.states;
        BitStorage pre = states.data.storage();
        boolean fresh = pre.getBits() == 0 && states.data.palette().valueFor(0) == AIR;
        if (!fresh) {
            // Pre-populated/polluted section (never in practice at noise time): vanilla
            // semantics per block — write only non-air bytes, full previous-state
            // accounting via the real setBlockState (Lithium's inject applies).
            sectionsSlowWrite.increment();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int b = ids[base + ((y * 16) + x) * 16 + z] & 0x7F;
                        if (m.nonAir[b]) {
                            section.setBlockState(x, y, z, m.states[b], false);
                        }
                    }
                }
            }
            return;
        }

        // Resolve palette ids ONCE per present state. idFor may swap `states.data` on a
        // palette resize, so all non-air states resolve FIRST and `states.data` is
        // re-read for the storage below; <=11 states + air always fit the 4-bit linear
        // palette, so ids are stable after this block. Air resolves LAST (valid in the
        // final palette); every unused/air byte maps to it (sentinel bytes cannot reach
        // the fast path — prepare() routes them to the hybrid).
        int[] pid = new int[11];
        for (int b = 0; b <= 9; b++) {
            if (hist[b] > 0 && m.nonAir[b]) {
                pid[b] = states.data.palette().idFor(m.states[b]);
            }
        }
        int airId = states.data.palette().idFor(AIR);
        for (int b = 0; b <= 10; b++) {
            if (!(b <= 9 && hist[b] > 0 && m.nonAir[b])) {
                pid[b] = airId;
            }
        }

        BitStorage storage = states.data.storage();   // final data after any resize
        if (storage instanceof SimpleBitStorage && storage.getBits() == 4) {
            // SECTION_STATES index = y<<8 | z<<4 | x and 16 nibbles/long => one long per
            // (y,z) row, x in the low-to-high nibbles. Full overwrite of all 4096 slots.
            long[] raw = storage.getRaw();
            for (int y = 0; y < 16; y++) {
                int yRow = base + y * 256;   // ids row start for (y, x=0, z=0): index (y*16 + x)*16 + z
                for (int z = 0; z < 16; z++) {
                    long word = 0L;
                    for (int x = 15; x >= 0; x--) {
                        word = (word << 4) | pid[ids[yRow + x * 16 + z] & 0x7F];
                    }
                    raw[y * 16 + z] = word;
                }
            }
            sectionsBulk.increment();
        } else {
            PalettedContainer.Strategy strat = states.strategy;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        storage.set(strat.getIndex(x, y, z), pid[ids[base + ((y * 16) + x) * 16 + z] & 0x7F]);
                    }
                }
            }
            sectionsSetLoop.increment();
        }

        // Section counters from the histogram (exactly the Noisium-redirect increments:
        // one nonEmpty per written block; fluid/ticking by the real state predicates).
        section.nonEmptyBlockCount += (short) nonAirCount;
        int fluidCount = 0;
        int tickingCount = 0;
        for (int b = 0; b <= 9; b++) {
            if (hist[b] > 0 && m.nonAir[b]) {
                if (m.fluid[b]) {
                    fluidCount += hist[b];
                }
                if (m.ticking[b]) {
                    tickingCount += hist[b];
                }
            }
        }
        section.tickingFluidCount += (short) fluidCount;
        section.tickingBlockCount += (short) tickingCount;

        // Lithium block-counting flags — the round-4 blocktracking-bypass fix semantics:
        // one trackBlockStateChange(newState, previous=AIR) per written block, applied
        // in bulk (exact-equivalent) through the bridge, which binds the BUNDLED Lithium
        // or a STANDALONE one interchangeably (see LithiumBlockTracking).
        if (LithiumBlockTracking.active()) {
            for (int b = 0; b <= 9; b++) {
                if (hist[b] > 0 && m.nonAir[b]) {
                    LithiumBlockTracking.trackBulk(section, m.states[b], AIR, hist[b]);
                }
            }
        }
    }

    /**
     * Primes both WG heightmaps from the id array: per column, WORLD_SURFACE_WG = first
     * (top-down) written block passing NOT_AIR, OCEAN_FLOOR_WG = first passing
     * blocksMotion (stone/ores yes, water/lava NO — evaluated through the REAL
     * {@code Heightmap.Types} predicates in {@link Mapping}). A single top {@code update}
     * per (map, column) yields the identical final raw data as vanilla's per-block
     * update stream: with only non-air top-down updates the final height is
     * {@code 1 + max(y passing)}, and untouched columns stay unprimed exactly like
     * vanilla's (air blocks never call update).
     */
    private static void heightmapsFromIds(Ctx ctx, Mapping m, Heightmap surf, Heightmap floor) {
        byte[] ids = ctx.ids;
        // ROW-MAJOR rewrite of the per-column top-down scan (the original's
        // ids[by*256 + col] walk is stride-256 through ~250 all-air rows per column —
        // cache-hostile). Semantics preserved exactly: per column we record the SAME
        // first-hit (top-down) surf/floor ids the original found, then replay the
        // update() calls in the ORIGINAL order (x-major columns; floor before surf
        // within a column). Heightmap slots are per-column independent, so identical
        // calls => identical heightmaps (the consume-verify compares them wholesale).
        // Air rows (id 1, sched bit irrelevant) can trigger neither m.surf nor
        // m.floor, so a whole row whose 8-byte words all satisfy
        // (word & 0x7F..7F) == 0x01..01 is skipped with 32 sequential long tests.
        int[] surfB = new int[256];
        int[] surfY = new int[256];
        int[] floorB = new int[256];
        int[] floorY = new int[256];
        java.util.Arrays.fill(surfB, -1);
        java.util.Arrays.fill(floorB, -1);
        long un0 = -1L, un1 = -1L, un2 = -1L, un3 = -1L;   // columns without a floor hit yet
        for (int by = ctx.fullY - 1; by >= 0 && (un0 | un1 | un2 | un3) != 0; by--) {
            int rowBase = by * 256;
            boolean allAir = true;
            for (int w = 0; w < 256; w += 8) {
                long word = (long) LONGS.get(ids, rowBase + w);
                if ((word & 0x7F7F7F7F7F7F7F7FL) != 0x0101010101010101L) {
                    allAir = false;
                    break;
                }
            }
            if (allAir) {
                continue;
            }
            for (int q = 0; q < 4; q++) {
                long mask = q == 0 ? un0 : q == 1 ? un1 : q == 2 ? un2 : un3;
                while (mask != 0) {
                    int bit = Long.numberOfTrailingZeros(mask);
                    mask &= mask - 1;
                    int col = (q << 6) | bit;
                    int b = ids[rowBase + col] & 0x7F;
                    if (surfB[col] < 0 && m.surf[b]) {
                        surfB[col] = b;
                        surfY[col] = ctx.oy + by;
                    }
                    if (m.floor[b]) {
                        floorB[col] = b;
                        floorY[col] = ctx.oy + by;
                        long clear = ~(1L << bit);
                        switch (q) {
                            case 0 -> un0 &= clear;
                            case 1 -> un1 &= clear;
                            case 2 -> un2 &= clear;
                            default -> un3 &= clear;
                        }
                    }
                }
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int col = x * 16 + z;
                if (floorB[col] >= 0) {
                    floor.update(x, floorY[col], z, m.states[floorB[col]]);
                }
                if (surfB[col] >= 0) {
                    surf.update(x, surfY[col], z, m.states[surfB[col]]);
                }
            }
        }
    }

    /** bit7 -> markPosForPostprocessing, exactly vanilla's condition: written fluid block with sched. */
    private static void marksFromIds(ChunkAccess chunk, Ctx ctx, Mapping m) {
        byte[] ids = ctx.ids;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // SWAR word-skip: a mark needs a written FLUID with the sched bit. On the fixed
        // kernel alphabet the fluid ids are exactly water=2/lava=3, so interesting bytes
        // are exactly {0x82, 0x83} == ((raw & 0xFE) == 0x82). Marks are rare (aquifer
        // fluid surfaces), so an 8-bytes-per-long zero-byte-detect skips ~99% of the
        // array with one branch per word; a hit replays the ORIGINAL per-byte logic
        // over those 8 bytes in linear order — the by/bx/bz loop nest below IS linear
        // ids order, so mark order and conditions are identical. If the mapping's
        // fluid set is ever NOT exactly {2,3} (exotic datapack), fall back verbatim.
        boolean std = m.nonAir[2] && m.fluid[2] && m.nonAir[3] && m.fluid[3];
        for (int b = 0; b < m.fluid.length && std; b++) {
            if (b != 2 && b != 3 && m.nonAir[b] && m.fluid[b]) {
                std = false;
            }
        }
        if (std) {
            for (int i = 0; i < ids.length; i += 8) {
                long w = (long) LONGS.get(ids, i);
                long t = (w ^ 0x8282828282828282L) & 0xFEFEFEFEFEFEFEFEL;
                if (((t - 0x0101010101010101L) & ~t & 0x8080808080808080L) == 0) {
                    continue;   // no byte in {0x82, 0x83} among these 8
                }
                for (int j = i; j < i + 8; j++) {
                    int raw = ids[j] & 0xFF;
                    if ((raw & 0x80) != 0) {
                        int b = raw & 0x7F;
                        if (m.nonAir[b] && m.fluid[b]) {
                            int rem = j & 255;
                            pos.set(ctx.ox + (rem >> 4), ctx.oy + (j >> 8), ctx.oz + (rem & 15));
                            chunk.markPosForPostprocessing(pos);
                        }
                    }
                }
            }
            return;
        }
        for (int by = 0; by < ctx.fullY; by++) {
            int rowBase = by * 256;
            for (int bx = 0; bx < 16; bx++) {
                int base = rowBase + bx * 16;
                for (int bz = 0; bz < 16; bz++) {
                    int raw = ids[base + bz] & 0xFF;
                    if ((raw & 0x80) != 0) {
                        int b = raw & 0x7F;
                        if (m.nonAir[b] && m.fluid[b]) {
                            pos.set(ctx.ox + bx, ctx.oy + by, ctx.oz + bz);
                            chunk.markPosForPostprocessing(pos);
                        }
                    }
                }
            }
        }
    }

    // =====================================================================
    // HYBRID path: vanilla loop skeleton; sentinel cells CPU-decided.
    // =====================================================================

    /**
     * The vanilla doFill loop with a per-CELL branch. Interpolator advancement: the
     * slice sequence (initializeForFirstCellX / advanceCellX / swapSlices) is stateful
     * across the whole chunk and runs for EVERY cellX plane exactly as vanilla; within a
     * plane, {@code selectCellYZ}+{@code updateForY/X/Z} are pure per-cell functions of
     * the slices, so they run ONLY for sentinel (CPU) cells — consumed cells take their
     * bytes from the id array with zero interpolator work, and the CPU cells still see
     * bit-identical interpolator state. Heightmaps and postprocess marks are deferred
     * to after the loop (same final values; keeps mid-loop faults recoverable).
     */
    private static void hybridFill(ChunkAccess chunk, NoiseChunk nc, Ctx ctx, Mapping m, int minCellY) {
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surf = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        IChunkNoiseSampler snc = (IChunkNoiseSampler) nc;
        NoiseChunk.BlockStateFiller rule = snc.getBlockStateRule();
        Aquifer aquifer = nc.aquifer();
        byte[] ids = ctx.ids;
        int k = ctx.cellWidth;
        int l = ctx.cellHeight;
        int cellsXZ = ctx.cellsXZ;
        LongArrayList marks = new LongArrayList();

        nc.initializeForFirstCellX();   // -> ChunkGridCache.beginChunk consumes the store entry
        for (int k1 = 0; k1 < cellsXZ; k1++) {
            nc.advanceCellX(k1);
            for (int l1 = 0; l1 < cellsXZ; l1++) {
                int secIdx = chunk.getSectionsCount() - 1;
                LevelChunkSection section = chunk.getSection(secIdx);
                for (int j2 = ctx.cellCountY - 1; j2 >= 0; j2--) {
                    boolean cpu = ctx.cellCpu[((j2 * cellsXZ) + k1) * cellsXZ + l1];
                    if (cpu) {
                        nc.selectCellYZ(j2, l1);
                        hybridCellsCpu.increment();
                    } else {
                        hybridCellsConsumed.increment();
                    }
                    for (int k2 = l - 1; k2 >= 0; k2--) {
                        int l2 = (minCellY + j2) * l + k2;
                        int i3 = l2 & 15;
                        int j3 = chunk.getSectionIndex(l2);
                        if (secIdx != j3) {
                            secIdx = j3;
                            section = chunk.getSection(j3);
                        }
                        if (cpu) {
                            nc.updateForY(l2, (double) k2 / (double) l);
                        }
                        for (int k3 = 0; k3 < k; k3++) {
                            int l3 = ctx.ox + k1 * k + k3;
                            int i4 = l3 & 15;
                            if (cpu) {
                                nc.updateForX(l3, (double) k3 / (double) k);
                            }
                            for (int j4 = 0; j4 < k; j4++) {
                                int k4 = ctx.oz + l1 * k + j4;
                                int l4 = k4 & 15;
                                BlockState state;
                                boolean sched;
                                if (cpu) {
                                    nc.updateForZ(k4, (double) j4 / (double) k);
                                    state = rule.calculate(nc);
                                    if (state == null) {
                                        state = m.states[0];   // settings.defaultBlock()
                                    }
                                    sched = aquifer.shouldScheduleFluidUpdate();
                                } else {
                                    int raw = ids[(((l2 - ctx.oy) * 16) + (l3 - ctx.ox)) * 16 + (k4 - ctx.oz)] & 0xFF;
                                    state = m.states[raw & 0x7F];
                                    sched = (raw & 0x80) != 0;
                                }
                                if (state != AIR) {
                                    writeBlock(section, i4, i3, l4, state);
                                    if (sched && !state.getFluidState().isEmpty()) {
                                        marks.add(BlockPos.asLong(l3, l2, k4));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            nc.swapSlices();
        }
        nc.stopInterpolation();

        heightmapsFromChunk(chunk, ctx, surf, floor);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < marks.size(); i++) {
            long packed = marks.getLong(i);
            pos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
            chunk.markPosForPostprocessing(pos);
        }
    }

    /**
     * The exact Noisium-redirect write sequence (per-block idFor + direct BitStorage set
     * + counters + the round-4 Lithium blocktracking fix). Used by the hybrid path where
     * per-block writes interleave with CPU-decided cells.
     */
    private static void writeBlock(LevelChunkSection section, int x, int y, int z, BlockState state) {
        section.nonEmptyBlockCount += 1;
        if (!state.getFluidState().isEmpty()) {
            section.tickingFluidCount += 1;
        }
        if (state.isRandomlyTicking()) {
            section.tickingBlockCount += 1;
        }
        BlockState prev = LithiumBlockTracking.active() ? section.getBlockState(x, y, z) : null;
        int blockStateId = section.states.data.palette().idFor(state);
        section.states.data.storage().set(section.states.strategy.getIndex(x, y, z), blockStateId);
        if (LithiumBlockTracking.active()) {
            LithiumBlockTracking.track(section, state, prev);
        }
    }

    /** Hybrid heightmaps: top-down first-match scans over the FINAL written states. */
    private static void heightmapsFromChunk(ChunkAccess chunk, Ctx ctx, Heightmap surf, Heightmap floor) {
        Predicate<BlockState> surfPred = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        Predicate<BlockState> floorPred = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        int top = ctx.oy + ctx.fullY - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockState surfState = null;
                int surfY = 0;
                boolean floorDone = false;
                for (int y = top; y >= ctx.oy && !floorDone; ) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    if (section.hasOnlyAir()) {
                        y = (y & ~15) - 1;   // skip the whole all-air section
                        continue;
                    }
                    int yStop = y & ~15;
                    for (; y >= yStop; y--) {
                        BlockState state = section.getBlockState(x, y & 15, z);
                        if (state != AIR) {
                            if (surfState == null && surfPred.test(state)) {
                                surfState = state;
                                surfY = y;
                            }
                            if (floorPred.test(state)) {
                                floor.update(x, y, z, state);
                                floorDone = true;
                                y--;
                                break;
                            }
                        }
                    }
                }
                if (surfState != null) {
                    surf.update(x, surfY, z, surfState);
                }
            }
        }
    }

    // =====================================================================
    // VERIFY mode (`verify`): original loop ships; consume path is the shadow.
    // =====================================================================

    private static final class VerifyCtx {
        Ctx ctx;
        int[] postSnapshot;   // per-section postprocessing list sizes BEFORE doFill
    }

    private static final ThreadLocal<VerifyCtx> VERIFY_TL = new ThreadLocal<>();

    /**
     * doFill HEAD hook (verify mode): peeks this chunk's ids (the original loop will
     * consume the store entry normally — the byte[] reference stays valid) and
     * snapshots the postprocessing list sizes. Never throws.
     */
    public static void verifyArm(ChunkAccess chunk, NoiseChunk nc, int minCellY, int cellCountY) {
        if (!CompactIds.VERIFY) {
            return;
        }
        try {
            VERIFY_TL.remove();
            Ctx ctx = prepare(chunk, nc, minCellY, cellCountY);
            if (ctx.reason != Reason.OK) {
                vSkipped.increment();
                countFallback(ctx.reason);
                return;
            }
            VerifyCtx vc = new VerifyCtx();
            vc.ctx = ctx;
            ShortList[] post = chunk.getPostProcessing();
            vc.postSnapshot = new int[post.length];
            for (int i = 0; i < post.length; i++) {
                vc.postSnapshot[i] = post[i] == null ? 0 : post[i].size();
            }
            VERIFY_TL.set(vc);
        } catch (Throwable t) {
            vSkipped.increment();
            LOG.warn("[compact-consume-verify] arm threw — chunk not verified.", t);
        }
    }

    /**
     * doFill RETURN hook (verify mode): compares every consume-path side effect against
     * the shipped chunk. The shadow models the hybrid exactly: sentinel cells use the
     * SHIPPED states (the hybrid runs the original machinery there), all other cells
     * use the byte-mapped states. Zero divergence required.
     */
    public static void verifyCompare(NoiseBasedChunkGenerator gen, ChunkAccess chunk) {
        if (!CompactIds.VERIFY) {
            return;
        }
        VerifyCtx vc = VERIFY_TL.get();
        if (vc == null) {
            return;
        }
        VERIFY_TL.remove();
        try {
            Ctx ctx = vc.ctx;
            Mapping m = new Mapping(gen.generatorSettings().value().defaultBlock());
            long mismatchesBefore = mismatchTotal();
            int chunkFlips = 0;
            if (ctx.hasSentinel) {
                vChunksHybridModel.increment();
            }

            // ---- 1. every palette block in consumed cells + 2. counters + 3. Lithium flags ----
            byte[] ids = ctx.ids;
            int cellsXZ = ctx.cellsXZ;
            for (int yBase = ctx.oy; yBase < ctx.oy + ctx.fullY; yBase += 16) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(yBase));
                int predNonEmpty = 0;
                int predFluid = 0;
                int predTicking = 0;
                // Predicted Lithium counts, replaying the exact init + per-write arithmetic:
                // fresh air section init = 4096 where the flag accepts AIR; each written
                // block adds test(new) - test(AIR).
                boolean lithiumFlags = LithiumBlockTracking.active() && LithiumBlockTracking.flagsAvailable();
                int[] flagCounts = new int[Math.max(LithiumBlockTracking.flagIndexUpperBound(), 1)];
                if (lithiumFlags) {
                    for (int f = 0; f < LithiumBlockTracking.flagCount(); f++) {
                        if (LithiumBlockTracking.flagPredicate(f).test(AIR)) {
                            flagCounts[LithiumBlockTracking.flagIndex(f)] = 16 * 16 * 16;
                        }
                    }
                }
                for (int y = 0; y < 16; y++) {
                    int by = yBase - ctx.oy + y;
                    int cy = by / ctx.cellHeight;
                    for (int x = 0; x < 16; x++) {
                        int cx = x / ctx.cellWidth;
                        for (int z = 0; z < 16; z++) {
                            boolean cpuCell = ctx.hasSentinel
                                    && ctx.cellCpu[((cy * cellsXZ) + cx) * cellsXZ + (z / ctx.cellWidth)];
                            BlockState actual = section.getBlockState(x, y, z);
                            BlockState predicted;
                            if (cpuCell) {
                                predicted = actual;   // hybrid runs the original machinery here
                            } else {
                                int b = ids[((by * 16) + x) * 16 + z] & 0x7F;
                                predicted = m.states[b];
                                vBlocksCompared.increment();
                                if ((predicted == AIR ? actual != AIR : actual != predicted)) {
                                    vBlockMismatch.increment();
                                    chunkFlips++;
                                    int sc = shippedClass(m, actual);
                                    flipMatrix.incrementAndGet(b * SHIPPED_CLASS_NAMES.length + sc);
                                    if (b == 3 || sc == 3) {
                                        flipsLavaClass.increment();
                                    }
                                    if ((b >= 4 && b <= 9) || sc == 4) {
                                        flipsVeinClass.increment();
                                    }
                                    if (yBase + y > 106) {
                                        flipsAboveY106.increment();
                                    }
                                    logMismatch("block", ctx, x + ctx.ox, yBase + y, z + ctx.oz,
                                            ID_NAME[b] + " (byte " + b + ") -> shipped " + actual);
                                }
                            }
                            if (predicted != AIR) {
                                predNonEmpty++;
                                if (!predicted.getFluidState().isEmpty()) {
                                    predFluid++;
                                }
                                if (predicted.isRandomlyTicking()) {
                                    predTicking++;
                                }
                                if (lithiumFlags) {
                                    for (int f = 0; f < LithiumBlockTracking.flagCount(); f++) {
                                        Predicate<BlockState> flag = LithiumBlockTracking.flagPredicate(f);
                                        int d = (flag.test(predicted) ? 1 : 0) - (flag.test(AIR) ? 1 : 0);
                                        if (d != 0) {
                                            flagCounts[LithiumBlockTracking.flagIndex(f)] += d;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (section.nonEmptyBlockCount != predNonEmpty) {
                    vNonEmptyMismatch.increment();
                    logMismatch("nonEmptyBlockCount", ctx, ctx.ox, yBase, ctx.oz,
                            "predicted " + predNonEmpty + " != shipped " + section.nonEmptyBlockCount);
                }
                if (section.tickingFluidCount != predFluid) {
                    vFluidCountMismatch.increment();
                    logMismatch("tickingFluidCount", ctx, ctx.ox, yBase, ctx.oz,
                            "predicted " + predFluid + " != shipped " + section.tickingFluidCount);
                }
                if (section.tickingBlockCount != predTicking) {
                    vTickingCountMismatch.increment();
                    logMismatch("tickingBlockCount", ctx, ctx.ox, yBase, ctx.oz,
                            "predicted " + predTicking + " != shipped " + section.tickingBlockCount);
                }
                if (lithiumFlags && LithiumBlockTracking.mayContainAvailable()) {
                    for (int f = 0; f < LithiumBlockTracking.flagCount(); f++) {
                        int idx = LithiumBlockTracking.flagIndex(f);
                        boolean predictedMay = flagCounts[idx] != 0;
                        if (LithiumBlockTracking.mayContainAny(section, f) != predictedMay) {
                            vLithiumFlagMismatch.increment();
                            logMismatch("lithiumFlag[" + idx + "]", ctx, ctx.ox, yBase, ctx.oz,
                                    "predicted mayContain=" + predictedMay);
                        }
                    }
                }
            }

            // ---- 4. both heightmaps per column (predicted from the shadow states) ----
            verifyHeightmaps(chunk, ctx, m);

            // ---- 5. the postprocessing set ----
            verifyPostprocessing(chunk, ctx, m, vc.postSnapshot);

            vChunks.increment();
            recordChunkFlips(chunkFlips);
            if (mismatchTotal() != mismatchesBefore) {
                vMismatchChunks.increment();
            }
            maybeReport();
        } catch (Throwable t) {
            vSkipped.increment();
            LOG.warn("[compact-consume-verify] compare threw — chunk not verified.", t);
        }
    }

    private static void verifyHeightmaps(ChunkAccess chunk, Ctx ctx, Mapping m) {
        Heightmap surf = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Predicate<BlockState> surfPred = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();
        Predicate<BlockState> floorPred = Heightmap.Types.OCEAN_FLOOR_WG.isOpaque();
        int minBuild = chunk.getMinBuildHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int predSurf = minBuild;
                int predFloor = minBuild;
                for (int by = ctx.fullY - 1; by >= 0 && (predSurf == minBuild || predFloor == minBuild); by--) {
                    int y = ctx.oy + by;
                    BlockState state = shadowState(chunk, ctx, m, x, by, z);
                    if (state == AIR) {
                        continue;
                    }
                    if (predSurf == minBuild && surfPred.test(state)) {
                        predSurf = y + 1;
                    }
                    if (predFloor == minBuild && floorPred.test(state)) {
                        predFloor = y + 1;
                    }
                }
                if (surf.getFirstAvailable(x, z) != predSurf) {
                    vHeightSurfMismatch.increment();
                    logMismatch("heightmap WORLD_SURFACE_WG", ctx, ctx.ox + x, predSurf, ctx.oz + z,
                            "predicted " + predSurf + " != shipped " + surf.getFirstAvailable(x, z));
                }
                if (floor.getFirstAvailable(x, z) != predFloor) {
                    vHeightFloorMismatch.increment();
                    logMismatch("heightmap OCEAN_FLOOR_WG", ctx, ctx.ox + x, predFloor, ctx.oz + z,
                            "predicted " + predFloor + " != shipped " + floor.getFirstAvailable(x, z));
                }
            }
        }
    }

    /** The shadow's state at (x local, by lattice-relative, z local): mapped byte, or shipped for CPU cells. */
    private static BlockState shadowState(ChunkAccess chunk, Ctx ctx, Mapping m, int x, int by, int z) {
        boolean cpuCell = ctx.hasSentinel
                && ctx.cellCpu[(((by / ctx.cellHeight) * ctx.cellsXZ) + (x / ctx.cellWidth)) * ctx.cellsXZ
                + (z / ctx.cellWidth)];
        int y = ctx.oy + by;
        if (cpuCell) {
            return chunk.getSection(chunk.getSectionIndex(y)).getBlockState(x, y & 15, z);
        }
        int b = ctx.ids[((by * 16) + x) * 16 + z] & 0x7F;
        BlockState s = m.states[b];
        return s == null ? AIR : s;
    }

    private static void verifyPostprocessing(ChunkAccess chunk, Ctx ctx, Mapping m, int[] snapshot) {
        // Actual positions doFill marked (diff vs the HEAD snapshot), split by cell class.
        LongOpenHashSet actualConsumed = new LongOpenHashSet();
        ShortList[] post = chunk.getPostProcessing();
        for (int sec = 0; sec < post.length; sec++) {
            ShortList list = post[sec];
            if (list == null) {
                continue;
            }
            int from = sec < snapshot.length ? snapshot[sec] : 0;
            int secY = chunk.getSectionYFromSectionIndex(sec);
            for (int i = from; i < list.size(); i++) {
                short packed = list.getShort(i);
                int x = packed & 15;
                int y = (secY << 4) + (packed >>> 4 & 15);
                int z = packed >>> 8 & 15;
                int by = y - ctx.oy;
                if (by < 0 || by >= ctx.fullY) {
                    continue;
                }
                boolean cpuCell = ctx.hasSentinel
                        && ctx.cellCpu[(((by / ctx.cellHeight) * ctx.cellsXZ) + (x / ctx.cellWidth)) * ctx.cellsXZ
                        + (z / ctx.cellWidth)];
                if (!cpuCell) {
                    actualConsumed.add(BlockPos.asLong(ctx.ox + x, y, ctx.oz + z));
                }
            }
        }
        // Predicted marks over consumed cells: written fluid block with bit7.
        byte[] ids = ctx.ids;
        for (int by = 0; by < ctx.fullY; by++) {
            int cy = by / ctx.cellHeight;
            for (int x = 0; x < 16; x++) {
                int cx = x / ctx.cellWidth;
                for (int z = 0; z < 16; z++) {
                    if (ctx.hasSentinel
                            && ctx.cellCpu[((cy * ctx.cellsXZ) + cx) * ctx.cellsXZ + (z / ctx.cellWidth)]) {
                        continue;
                    }
                    int raw = ids[((by * 16) + x) * 16 + z] & 0xFF;
                    boolean predicted = (raw & 0x80) != 0 && m.nonAir[raw & 0x7F] && m.fluid[raw & 0x7F];
                    if (predicted) {
                        vPostCompared.increment();
                        if (!actualConsumed.remove(BlockPos.asLong(ctx.ox + x, ctx.oy + by, ctx.oz + z))) {
                            vPostMissing.increment();
                            logMismatch("postprocess (predicted, vanilla did not mark)", ctx,
                                    ctx.ox + x, ctx.oy + by, ctx.oz + z, "byte 0x" + Integer.toHexString(raw));
                        }
                    }
                }
            }
        }
        // Leftovers = vanilla marked, shadow would not.
        for (long packed : actualConsumed) {
            vPostExtra.increment();
            logMismatch("postprocess (vanilla marked, shadow would not)", ctx,
                    BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed), "");
        }
    }

    private static long mismatchTotal() {
        return vBlockMismatch.sum() + vHeightSurfMismatch.sum() + vHeightFloorMismatch.sum()
                + vNonEmptyMismatch.sum() + vFluidCountMismatch.sum() + vTickingCountMismatch.sum()
                + vLithiumFlagMismatch.sum() + vPostMissing.sum() + vPostExtra.sum();
    }

    /** Shipped-state class for the flip matrix: matches against the SAME canonical states
     *  the prediction mapping uses (reference compares), so classification can never drift
     *  from the comparison itself. */
    private static int shippedClass(Mapping m, BlockState s) {
        for (int b = 0; b <= 9; b++) {
            if (s == m.states[b]) {
                return b <= 3 ? b : 4;   // 0 stone, 1 air, 2 water, 3 lava, 4..9 -> vein
            }
        }
        return 5;                        // other (surface/carver-era state should never appear here)
    }

    private static void recordChunkFlips(int flips) {
        for (int i = 0; i < FLIP_BUCKETS.length; i++) {
            if (flips <= FLIP_BUCKETS[i]) {
                flipHisto.incrementAndGet(i);
                break;
            }
        }
        long prev;
        while (flips > (prev = flipMaxPerChunk.get())
                && !flipMaxPerChunk.compareAndSet(prev, flips)) {
            // retry
        }
    }

    private static String flipMatrixSummary() {
        StringBuilder sb = new StringBuilder();
        for (int b = 0; b <= 10; b++) {
            for (int c = 0; c < SHIPPED_CLASS_NAMES.length; c++) {
                long n = flipMatrix.get(b * SHIPPED_CLASS_NAMES.length + c);
                if (n != 0) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(ID_NAME[b]).append("->").append(SHIPPED_CLASS_NAMES[c]).append('=').append(n);
                }
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static void logMismatch(String cls, Ctx ctx, int x, int y, int z, String detail) {
        long budget = vLogBudget.get();
        if (budget > 0 && vLogBudget.compareAndSet(budget, budget - 1)) {
            LOG.warn("[compact-consume-verify] MISMATCH class={} at ({}, {}, {}) hybridModel={} : {}",
                    cls, x, y, z, ctx.hasSentinel, detail);
        }
    }

    // =====================================================================
    // Reporting.
    // =====================================================================

    private static void maybeReport() {
        long total = chunksFast.sum() + chunksHybrid.sum() + vChunks.sum();
        long at = nextReportAt.get();
        if (total >= at && nextReportAt.compareAndSet(at, at * 2)) {
            report("progress");
        }
    }

    /** Final/periodic report (wired next to CompactIds.report in SuperChunk). */
    public static void report(String reason) {
        if (CompactIds.CONSUME) {
            long consumed = chunksFast.sum() + chunksHybrid.sum();
            double msPerChunk = consumed == 0 ? 0.0 : consumeNanos.sum() / 1.0e6 / consumed;
            LOG.info("[compact-consume] ({}) chunks consumed={} (fast={} hybrid={}) mean {} ms/chunk | "
                            + "hybrid cells cpu={} consumed={} | sections bulk={} setLoop={} slowWrite={} | "
                            + "fallbacks: disabled={} lithiumUnsafe={} noEntry={} noIds={} geometry={} unaligned={} length={} "
                            + "absent={} unknownByte={} debugVoid={} | faults={} (consume {})",
                    reason, consumed, chunksFast.sum(), chunksHybrid.sum(), String.format("%.3f", msPerChunk),
                    hybridCellsCpu.sum(), hybridCellsConsumed.sum(),
                    sectionsBulk.sum(), sectionsSetLoop.sum(), sectionsSlowWrite.sum(),
                    fbDisabled.sum(), fbLithiumUnsafe.sum(), fbNoEntry.sum(), fbNoIds.sum(), fbGeometry.sum(), fbUnaligned.sum(),
                    fbLength.sum(), fbAbsent.sum(), fbUnknownByte.sum(), fbDebugVoid.sum(),
                    faults.sum(), disabled ? "DISABLED" : "enabled");
        }
        if (CompactIds.VERIFY) {
            long mm = mismatchTotal();
            LOG.info("[compact-consume-verify] ({}) chunks verified={} (hybrid-model={}) skipped={} | "
                            + "blocks compared={} | MISMATCHES total={} (chunks with any={}) by class: "
                            + "block={} heightmapSurface={} heightmapFloor={} nonEmptyCount={} fluidCount={} "
                            + "tickingCount={} lithiumFlags={} postprocessMissing={} postprocessExtra={} "
                            + "(postprocess compared={}) | {}",
                    reason, vChunks.sum(), vChunksHybridModel.sum(), vSkipped.sum(),
                    vBlocksCompared.sum(), mm, vMismatchChunks.sum(),
                    vBlockMismatch.sum(), vHeightSurfMismatch.sum(), vHeightFloorMismatch.sum(),
                    vNonEmptyMismatch.sum(), vFluidCountMismatch.sum(), vTickingCountMismatch.sum(),
                    vLithiumFlagMismatch.sum(), vPostMissing.sum(), vPostExtra.sum(), vPostCompared.sum(),
                    mm == 0 ? "ZERO DIVERGENCE — Stage-6 side-effect gate PASSING"
                            : "DIVERGENCE PRESENT — do NOT run the `on` A/B");
            StringBuilder hb = new StringBuilder();
            for (int i = 0; i < FLIP_BUCKETS.length; i++) {
                if (i > 0) {
                    hb.append(' ');
                }
                hb.append(FLIP_BUCKET_LABELS[i]).append('=').append(flipHisto.get(i));
            }
            LOG.info("[compact-consume-verify] ({}) FLIP CENSUS (decideFp={}): chunks by flips/chunk: {} | "
                            + "worst chunk={} | HARD gates (want 0): lavaClass={} veinClass={} aboveY106={} | "
                            + "transitions: {}",
                    reason, CompactIds.decideFp(), hb, flipMaxPerChunk.get(),
                    flipsLavaClass.sum(), flipsVeinClass.sum(), flipsAboveY106.sum(), flipMatrixSummary());
        }
    }
}
