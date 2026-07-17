package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer;

import dev.superchunk.com.ishland.c2me.opts.worldgen.general.common.random_instances.RandomUtils;
import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.ScAquiferCellCache;
import dev.superchunk.gpu.aquifer.AquiferGpuVerify;
import dev.superchunk.gpu.aquifer.BlockIdCensus;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.blending.Blender;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import org.apache.commons.lang3.mutable.MutableDouble;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Aquifer.NoiseBasedAquifer.class)
public abstract class MixinAquiferSamplerImpl implements dev.superchunk.gpu.dfc.ScCompactAuxSource {

    @Shadow
    @Final
    protected int minGridX;

    @Shadow
    @Final
    protected int minGridY;

    @Shadow
    @Final
    protected int minGridZ;

    @Shadow
    @Final
    protected int gridSizeZ;

    @Shadow @Final protected int gridSizeX;

    @Shadow @Final protected long[] aquiferLocationCache;

    @Shadow @Final private PositionalRandomFactory positionalRandomFactory;

    @Shadow
    @Final
    protected Aquifer.FluidStatus[] aquiferCache;

    @Shadow
    @Final
    private static int[][] SURFACE_SAMPLING_OFFSETS_IN_CHUNKS;

    @Shadow
    @Final
    private NoiseChunk noiseChunk;

    @Shadow
    @Final
    protected DensityFunction barrierNoise;

    @Shadow
    @Final
    private DensityFunction fluidLevelFloodednessNoise;

    @Shadow
    @Final
    private DensityFunction fluidLevelSpreadNoise;

    @Shadow
    @Final
    protected DensityFunction lavaNoise;

    @Shadow
    @Final
    private static double FLOWING_UPDATE_SIMULARITY;

    @Shadow
    protected boolean shouldScheduleFluidUpdate;

    @Shadow
    @Final
    private Aquifer.FluidPicker globalFluidPicker;

    @Shadow protected abstract int getIndex(int x, int y, int z);

    @Shadow
    protected static double similarity(int i, int a) {
        throw new AbstractMethodError();
    }

    @Shadow protected abstract int computeRandomizedFluidSurfaceLevel(int blockX, int blockY, int blockZ, int surfaceHeightEstimate);

    @Shadow protected abstract Aquifer.FluidStatus computeFluid(int blockX, int blockY, int blockZ);

    @Shadow @Final private DensityFunction erosion;

    @Shadow @Final private DensityFunction depth;

    @Unique
    private int c2me$dist1;
    @Unique
    private int c2me$dist2;
    @Unique
    private int c2me$dist3;
    @Unique
    private long c2me$pos1;
    @Unique
    private long c2me$pos2;
    @Unique
    private long c2me$pos3;

    @Unique
    private double c2me$mutableDoubleThingy;

    // SuperChunk air-column skip (prototype, default OFF). Above a configured ceiling, a
    // density<0 block whose GLOBAL fluid status is AIR has no local aquifer (perched
    // aquifers are bounded below this Y) and no beardifier solid (the beardifier is already
    // folded into `density`), so the expensive local aquifer search would return the same
    // AIR. Skipping it avoids refreshDistPosIdx + up to 3 getAquiferStatus per air block.
    // Empirically bit-parity gated via full-chunk region byte-compare; -D-tunable for the
    // parity sweep. Default minY=Integer.MAX_VALUE => never fires unless explicitly enabled.
    @Unique
    private static final boolean SC_AIR_SKIP = Boolean.getBoolean("superchunk.worldgen.airSkip");
    @Unique
    private static final int SC_AIR_SKIP_MIN_Y = Integer.getInteger("superchunk.worldgen.airSkipMinY", Integer.MAX_VALUE);

    // SuperChunk air-skip PARITY VERIFY (default OFF). When on, the skip is NOT taken;
    // instead every density<=0 air candidate runs the FULL vanilla aquifer path (so the
    // world is generated bit-correct), and we categorize what vanilla actually produced:
    //   fullNull  -> computeSubstance returned null  => caller places default STONE; skip places AIR (DANGER)
    //   fullAir   -> returned the AIR state          => AIR placed (skip-equivalent, SAFE)
    //   fullOther -> returned a real block (water/lava/...) => skip would WRONGLY place air (DANGER)
    //   fullSched -> vanilla set shouldScheduleFluidUpdate=true => informational only (no-op on an AIR result)
    // dangerMaxY = highest Y at which any DANGER occurred. The skip is provably bit-safe for
    // any minY > dangerMaxY. One pregen therefore reveals the exact safe threshold. Because
    // verify ignores SC_AIR_SKIP_MIN_Y and checks the whole air column, it needs only ONE run.
    @Unique
    private static final boolean SC_AIR_VERIFY = Boolean.getBoolean("superchunk.worldgen.airSkip.verify");
    @Unique
    private static final AtomicLong scAirChecked = new AtomicLong();
    @Unique
    private static final AtomicLong scFullNull = new AtomicLong();
    @Unique
    private static final AtomicLong scFullAir = new AtomicLong();
    @Unique
    private static final AtomicLong scFullOther = new AtomicLong();
    @Unique
    private static final AtomicLong scFullSched = new AtomicLong();
    @Unique
    private static final LongAccumulator scDangerMaxY = new LongAccumulator(Long::max, Long.MIN_VALUE);
    @Unique
    private static final AtomicLong scNextLogAt = new AtomicLong(2_000_000L);

    @Unique
    private static final org.slf4j.Logger SC_AIR_LOG = org.slf4j.LoggerFactory.getLogger("SuperChunk-AirSkipVerify");

    @Unique
    private void scAirVerifyReport(long checked) {
        long mx = scDangerMaxY.get();
        SC_AIR_LOG.info("[airskip-verify] airCandidates={} | full: null={} air={} OTHER(danger)={} schedFluid(no-op)={} | dangerMaxY={} -> bit-safe minY would be {}",
                checked, scFullNull.get(), scFullAir.get(), scFullOther.get(), scFullSched.get(),
                (mx == Long.MIN_VALUE ? "none" : String.valueOf(mx)),
                (mx == Long.MIN_VALUE ? "any (skip safe at all Y)" : String.valueOf(mx + 1)));
    }

    // SuperChunk ADAPTIVE air-skip (default OFF). Parity-safe at ANY height: skip the per-block
    // aquifer search for an air block iff j >= the local water ceiling = max fluid surface among
    // the candidate aquifer cells (the same 4 XZ-columns refreshDistPosIdx searches). The ceiling
    // theorem covers the FLUID class (a fluid result requires j < a candidate's fluid surface <= ceiling).
    // HISTORY: the ceiling-alone theorem (fluid class only) was INCOMPLETE — the aquifer pressure/barrier
    // path (AquiferRef.decide: density + d*pressure > 0 => STONE) places rare stone barriers a few blocks
    // ABOVE the fluid ceiling (measured BUGS(stone-deleted)=65/90M with the old +0 threshold). FIXED by the
    // SC_BARRIER_MARGIN (=5) above: the barrier-noise term is provably zeroed for j >= M+5, so j >= ceiling+5
    // is provably air against BOTH the fluid and the stone-barrier class (see SC_BARRIER_MARGIN derivation).
    // DEFAULT ON again (2026-06-30) — proven analytically AND empirically: strengthened verify (counts the
    // null==STONE class + a safety gate) = BUGS(water+stone)=0, gateTripped=false across 3 seeds (8675309,
    // 4099342, 20260629) overworld + nether, ~300M+ air blocks/seed, skip ~93% retained. Disable with
    // -Dsuperchunk.worldgen.adaptiveAirSkip=false.
    @Unique
    private static final boolean SC_ADAPTIVE = Boolean.parseBoolean(System.getProperty("superchunk.worldgen.adaptiveAirSkip", "true"));
    // BARRIER-AWARE MARGIN (2026-06-30): the fluid ceiling alone is NOT sufficient — the aquifer
    // pressure/barrier path (AquiferRef.decide: density + d*pressure > 0 => STONE) can place a stone
    // barrier a few blocks ABOVE the fluid ceiling. Derivation from the exact algebra: above the
    // ceiling both neighbours are air, so pressure = 2*(r + qq) with qq = (M - j - 0.5)/2.5 for a pair
    // whose higher fluid level is M, and the barrier-noise term r is ZEROED unless qq >= -2, i.e. unless
    // j <= M + 4. So for j >= M + 5 the noise term vanishes and pressure = 2*qq < 0 => density + d*pressure
    // < 0 => NO barrier, for EVERY pair, regardless of the noise value. Every pair's M <= columnCeiling
    // (columnCeiling = max fluid level over all candidate cells), so skipping only at j >= columnCeiling+5
    // is provably air against BOTH the fluid AND the barrier class. The prior +0 threshold left the
    // 4-block barrier band [ceiling, ceiling+4] exposed (the measured stone-deleted bug). 5 is exact/tight.
    @Unique
    private static final int SC_BARRIER_MARGIN = 5;
    @Unique
    private static final boolean SC_ADAPTIVE_VERIFY = Boolean.getBoolean("superchunk.worldgen.adaptiveAirSkip.verify");
    @Unique
    private static final AtomicLong scAdaptiveSkips = new AtomicLong();
    @Unique
    private static final AtomicLong scAdaptiveBugs = new AtomicLong();              // ceiling too low => water deleted (MUST be 0)
    @Unique
    private static final AtomicLong scAdaptiveStoneBugs = new AtomicLong();         // vanilla null => caller's default STONE deleted by skip (MUST be 0)
    // Safety gate: once a verify run detects ANY adaptive-skip divergence (water OR stone), this
    // latches and the live skip falls back to the full vanilla path so a detected divergence can
    // never corrupt terrain. Only ever written by the verify path => byte-identical when verify OFF.
    @Unique
    private static volatile boolean scAdaptiveGateTripped = false;
    @Unique
    private static final LongAccumulator scAdaptiveBugMaxGap = new LongAccumulator(Long::max, Long.MIN_VALUE);
    @Unique
    private static final AtomicLong scAdaptiveNextLogAt = new AtomicLong(2_000_000L);
    // per-instance (per-chunk, single-thread) memo of columnCeiling(x,z); primitive map, no boxing
    // (called per air block, so boxing here dominated the per-block cost). MIN_VALUE = absent.
    @Unique
    private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap scColCeilingCache;
    // Single-entry last-(gx,gz) memo: the block filler walks many consecutive blocks sharing
    // the same cell-column (a whole vertical column, and 16 blocks in x/z), so this short-
    // circuits the hashmap+key work for the large majority of scColumnCeiling calls. Result
    // is identical (pure perf). scLastCeilingValid distinguishes "unset" from a 0 ceiling.
    @Unique private int scLastGx, scLastGz, scLastCeiling;
    @Unique private boolean scLastCeilingValid;

    // ============================ SuperChunk aquifer CPU levers ============================
    // Single flag gating THREE parity-safe CPU optimisations (default ON; kill-switch
    // -Dsuperchunk.worldgen.aquiferCellCache=false):
    //   (1) cross-chunk per-dimension FluidStatus cache  -> getAquiferStatus / scColumnCeiling
    //   (2) adaptive ceiling-scan bound                  -> scColumnCeiling
    //   (3) refreshDistPosIdx candidate memo             -> aquiferExtracted$refreshDistPosIdx
    // All three are pure deterministic memos/bounds: with the flag ON they return byte-identical
    // values to the flag-OFF path (proofs at each use site). With the flag OFF the code below is never
    // entered and every touched method is byte-identical to the current behaviour.
    // BLEND CAVEAT: lever (1)'s purity claim only holds when this chunk's Blender is EMPTY. On
    // pre-1.18 upgraded worlds near blend boundaries, computeFluid depends on the per-chunk
    // preliminarySurfaceLevel -> Blender data, so the same cell can legitimately differ between
    // chunks; scCellCache() therefore resolves to null for blending chunks and lever (1) falls back
    // to the per-instance path. Levers (2)/(3) are per-instance and blend-consistent, so they stay on.
    @Unique
    private static final boolean SC_CELL_CACHE = Boolean.parseBoolean(System.getProperty("superchunk.worldgen.aquiferCellCache", "true"));

    // (1) per-dimension cross-chunk cache, resolved lazily from the registry by the dimension's
    // globalFluidPicker identity, then held per-instance for a cheap subsequent access.
    // Resolves to NULL (cache bypassed) when this chunk is blending — see BLEND CAVEAT above.
    @Unique
    private ScAquiferCellCache scCellCache;
    @Unique
    private boolean scCellCacheResolved;

    @Unique
    private ScAquiferCellCache scCellCache() {
        if (!this.scCellCacheResolved) {
            this.scCellCacheResolved = true;
            if (this.noiseChunk.getBlender() == Blender.empty()) {
                this.scCellCache = ScAquiferCellCache.forDimension(this.globalFluidPicker);
            }
        }
        return this.scCellCache;
    }

    // (2) ceiling-scan bound. The margin (=20) is the exact +20 offset of computeFluid's center-cell
    // early return: computeFluid returns the global picker's default fluid level whenever the cell's
    // jittered y satisfies jitterY > preliminarySurfaceLevel(jitterX,jitterZ) + 20 (the algebra is
    // k = jitterY-12, k1 = preliminarySurfaceLevel+8, early-return iff k > k1). Every candidate cell's
    // jittered preliminary surface is <= maxPreliminarySurface over the columns, and its jittered y is
    // >= 12*gridY, so any cell with 12*gridY > maxPreliminarySurface+20 provably takes that early return
    // and contributes exactly the default fluid level. The only way a cell can contribute MORE than the
    // default level is the randomized branch, whose result is <= surfaceHeightEstimate <=
    // preliminarySurfaceLevel(jitterX,jitterZ) <= maxPreliminarySurface, so it is always captured by a
    // scanned (non-skipped) cell. Hence seeding the max with the default level and scanning only cells
    // with 12*gridY <= maxPreliminarySurface+20 yields the identical max to the full scan.
    @Unique
    private static final int SC_CEILING_SCAN_MARGIN = 20;
    // Lazily-probed default (high-y, non-lava) fluid level of this dimension's global picker; this is
    // exactly what every skipped high cell early-returns (the vanilla picker is a fixed sea/lava split
    // at y=min(-54,seaLevel), so above the scan cutoff it always yields the default level).
    @Unique
    private int scDefaultFluidLevel;
    @Unique
    private boolean scDefaultFluidLevelValid;

    @Unique
    private int scDefaultFluidLevel() {
        if (!this.scDefaultFluidLevelValid) {
            this.scDefaultFluidLevel = this.globalFluidPicker.computeFluid(0, 30_000_000, 0).fluidLevel;
            this.scDefaultFluidLevelValid = true;
        }
        return this.scDefaultFluidLevel;
    }

    // (3) single-entry last-(gx,gy,gz) memo of the 12 lattice candidate longs for refreshDistPosIdx.
    @Unique
    private long[] scRefreshCand;
    @Unique
    private int[] scRefreshCandX;
    @Unique
    private int[] scRefreshCandY;
    @Unique
    private int[] scRefreshCandZ;
    @Unique
    private int scRefreshGx, scRefreshGy, scRefreshGz;
    @Unique
    private boolean scRefreshValid;

    // SuperChunk AQUIFER GPU DECISION VERIFY (default OFF; -Dsuperchunk.gpu.aquiferVerify=true).
    // VERIFY-ONLY go/no-go gate. When on, every density<=0 block runs the FULL vanilla path
    // (world stays bit-correct) and its inputs are batched per chunk; the GPU aquifer_decide
    // kernel recomputes the decision and AquiferGpuVerify compares the GPU block id to the CPU's
    // actual result (mode-A: GPU fed the CPU density -> any mismatch is a PORT bug; mode-B: the
    // density-margin-to-flip bound = the spline-wall susceptibility). See AquiferGpuVerify.
    @Unique
    private static final boolean SC_AQUIFER_VERIFY = Boolean.getBoolean("superchunk.gpu.aquiferVerify");
    @Unique
    private AquiferGpuVerify.Accumulator scAqAcc;

    // SuperChunk STAGE-3 BLOCK-ID CENSUS (default OFF; -Dsuperchunk.gpu.blockIdVerify=true).
    // VERIFY-ONLY: every noise-stage block runs the FULL vanilla path (world ships bit-correct);
    // armed blocks (MaterialRuleList.calculate seam only — carver-stage reuse is structurally
    // excluded) are recorded with the shipped decision + shouldScheduleFluidUpdate bit and
    // compared per chunk against the GPU aquifer_census kernel. See BlockIdCensus.
    @Unique
    private static final boolean SC_BLOCKID_CENSUS = Boolean.getBoolean("superchunk.gpu.blockIdVerify");
    @Unique
    private BlockIdCensus.Accumulator scCensusAcc;

    // max fluid surface among the candidate aquifer cells for column (x,z); blocks at/above it are air.
    @Unique
    private int scColumnCeiling(int x, int z) {
        // ceiling depends ONLY on the candidate cell-columns (gx,gz), not exact (x,z): every column
        // sharing (gx,gz) has the identical max-fluid-surface. Key by (gx,gz) -> ~64x fewer scans.
        int gx = (x - 5) >> 4;
        int gz = (z - 5) >> 4;
        // Single-entry fast path: consecutive blocks in a column share (gx,gz).
        if (this.scLastCeilingValid && gx == this.scLastGx && gz == this.scLastGz) {
            return this.scLastCeiling;
        }
        if (this.scColCeilingCache == null) {
            this.scColCeilingCache = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            this.scColCeilingCache.defaultReturnValue(Integer.MIN_VALUE);
        }
        long key = (((long) gx) << 32) ^ (gz & 0xFFFFFFFFL);
        int cached = this.scColCeilingCache.get(key);
        if (cached != Integer.MIN_VALUE) {
            scRememberCeiling(gx, gz, cached);
            return cached;
        }
        int sizeY = this.aquiferLocationCache.length / (this.gridSizeX * this.gridSizeZ);
        int maxLevel;
        if (SC_CELL_CACHE) {
            // Lever 2: bound the vertical scan by preliminarySurfaceLevel (see SC_CEILING_SCAN_MARGIN).
            maxLevel = scBoundedCeilingScan(gx, gz, sizeY);
        } else {
            maxLevel = Integer.MIN_VALUE;
            for (int ox = 0; ox <= 1; ox++) {
                for (int oz = 0; oz <= 1; oz++) {
                    for (int m = this.minGridY; m < this.minGridY + sizeY; m++) {
                        long apoint = this.aquiferLocationCache[this.getIndex(gx + ox, m, gz + oz)];
                        int fl = this.getAquiferStatus(apoint).fluidLevel;     // cached per cell
                        if (fl > maxLevel) maxLevel = fl;
                    }
                }
            }
        }
        this.scColCeilingCache.put(key, maxLevel);
        scRememberCeiling(gx, gz, maxLevel);
        return maxLevel;
    }

    // Lever 2: the ceiling (max fluid surface over the 4 candidate cell-columns) without scanning the
    // guaranteed-air cells far above terrain. maxPreliminarySurface is sampled over every quart column
    // any candidate cell's jittered (x,z) can fall into; the reachable jitter span for columns
    // {gx,gx+1} is [16*gx, 16*gx+25], whose quart columns are 16*gx .. 16*gx+24 step 4 (the step-4 span
    // is a safe SUPERSET — an over-estimate of maxPrelim only ever scans MORE cells, never fewer). See
    // SC_CEILING_SCAN_MARGIN for why cells above maxPrelim+20 contribute only the default fluid level.
    @Unique
    private int scBoundedCeilingScan(int gx, int gz, int sizeY) {
        int maxPrelim = Integer.MIN_VALUE;
        for (int dx = 0; dx <= 24; dx += 4) {
            int px = (gx << 4) + dx;
            for (int dz = 0; dz <= 24; dz += 4) {
                int p = this.noiseChunk.preliminarySurfaceLevel(px, (gz << 4) + dz);
                if (p > maxPrelim) maxPrelim = p;
            }
        }
        int mEnd = this.minGridY + sizeY;
        // No terrain in a candidate column (preliminary == Integer.MAX_VALUE), or an unexpected empty
        // sample set: fall back to the full scan (correctness over speed; cutoff arithmetic would overflow).
        if (maxPrelim == Integer.MAX_VALUE || maxPrelim == Integer.MIN_VALUE) {
            int maxLevel = Integer.MIN_VALUE;
            for (int ox = 0; ox <= 1; ox++) {
                for (int oz = 0; oz <= 1; oz++) {
                    for (int m = this.minGridY; m < mEnd; m++) {
                        int fl = this.getAquiferStatus(this.aquiferLocationCache[this.getIndex(gx + ox, m, gz + oz)]).fluidLevel;
                        if (fl > maxLevel) maxLevel = fl;
                    }
                }
            }
            return maxLevel;
        }
        // Seed with the default fluid level (what all skipped high cells early-return), then scan only
        // cells that might NOT early-return (12*gridY <= maxPrelim+20). Since gridY ascends, break once past.
        int cutoff = maxPrelim + SC_CEILING_SCAN_MARGIN;
        int maxLevel = this.scDefaultFluidLevel();
        for (int ox = 0; ox <= 1; ox++) {
            for (int oz = 0; oz <= 1; oz++) {
                for (int m = this.minGridY; m < mEnd; m++) {
                    if (12 * m > cutoff) break;   // higher cells provably early-return the default level
                    int fl = this.getAquiferStatus(this.aquiferLocationCache[this.getIndex(gx + ox, m, gz + oz)]).fluidLevel;
                    if (fl > maxLevel) maxLevel = fl;
                }
            }
        }
        return maxLevel;
    }

    @Unique
    private void scRememberCeiling(int gx, int gz, int ceiling) {
        this.scLastGx = gx;
        this.scLastGz = gz;
        this.scLastCeiling = ceiling;
        this.scLastCeilingValid = true;
    }

    // verify the adaptive skip: for every air block that WOULD be skipped, run full vanilla and
    // assert it is air. A fluid there means the ceiling was too low (bug). bugs MUST be 0.
    @Unique
    private BlockState scAdaptiveVerifyComputeFull(DensityFunction.FunctionContext pos, double density, int i, int j, int k, BlockState globalAt) {
        boolean wouldSkip = globalAt.isAir() && j >= scColumnCeiling(i, k) + SC_BARRIER_MARGIN;
        BlockState fullResult;
        if (globalAt.is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = false;
            fullResult = Blocks.LAVA.defaultBlockState();
        } else {
            aquiferExtracted$refreshDistPosIdx(i, j, k);
            fullResult = aquiferExtracted$applyPost(pos, density, j, i, k);
        }
        if (wouldSkip) {
            scAdaptiveSkips.incrementAndGet();
            boolean bug = false;
            if (fullResult == null) {                          // vanilla null => caller places default STONE; skip => AIR => BUG
                scAdaptiveStoneBugs.incrementAndGet();
                bug = true;
            } else if (!fullResult.isAir()) {                  // vanilla placed a fluid where we'd skip => BUG
                scAdaptiveBugs.incrementAndGet();
                bug = true;
            }
            if (bug) {
                scAdaptiveBugMaxGap.accumulate(j - (long) scColumnCeiling(i, k));
                if (!scAdaptiveGateTripped) {                  // latch the safety gate => live skip falls back to the full path
                    scAdaptiveGateTripped = true;
                    SC_AIR_LOG.error("[adaptive-verify] SAFETY GATE TRIPPED at (x={}, y={}, z={}): vanilla would place {} where the adaptive air-skip yields AIR; disabling adaptive air-skip (falling back to full path).",
                            i, j, k, (fullResult == null ? "STONE (default block)" : fullResult));
                }
            }
        }
        long checked = scAirChecked.incrementAndGet();
        long logAt = scAdaptiveNextLogAt.get();
        if (checked >= logAt && scAdaptiveNextLogAt.compareAndSet(logAt, logAt + 2_000_000L)) {
            long bugGap = scAdaptiveBugMaxGap.get();
            SC_AIR_LOG.info("[adaptive-verify] airChecked={} adaptiveSkips={} BUGS(water-deleted)={} BUGS(stone-deleted)={} maxBugGap={} gateTripped={}",
                    checked, scAdaptiveSkips.get(), scAdaptiveBugs.get(), scAdaptiveStoneBugs.get(),
                    (bugGap == Long.MIN_VALUE ? "n/a" : String.valueOf(bugGap)), scAdaptiveGateTripped);
        }
        return fullResult;
    }

    // Aquifer GPU decision verify: run the EXACT vanilla density<=0 path (so output is
    // bit-correct), encode the CPU result, and batch the block for the per-chunk GPU compare.
    @Unique
    private BlockState scAquiferVerifyComputeFull(DensityFunction.FunctionContext pos, double density,
                                                  int i, int j, int k, BlockState globalAt) {
        BlockState fullResult;
        int cpuDist1 = -1;
        int cpuFl1 = Integer.MIN_VALUE;
        int cpuIdx1 = -1;
        long cpuPos1 = 0;
        if (globalAt.is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = false;
            fullResult = Blocks.LAVA.defaultBlockState();
        } else {
            aquiferExtracted$refreshDistPosIdx(i, j, k);
            // capture the CPU grid-search nearest (diagnostic): dist1 + the nearest cell's fluidLevel/idx/pos
            cpuDist1 = this.c2me$dist1;
            cpuPos1 = this.c2me$pos1;
            cpuFl1 = this.getAquiferStatus(cpuPos1).fluidLevel;
            cpuIdx1 = this.getIndex(BlockPos.getX(cpuPos1) >> 4, Math.floorDiv(BlockPos.getY(cpuPos1), 12),
                    BlockPos.getZ(cpuPos1) >> 4);
            fullResult = aquiferExtracted$applyPost(pos, density, j, i, k);
        }
        if (AquiferGpuVerify.isReady()) {
            int cpuId = AquiferGpuVerify.encodeResult(
                    fullResult == null,
                    fullResult != null && fullResult.isAir(),
                    fullResult != null && fullResult.is(Blocks.WATER),
                    fullResult != null && fullResult.is(Blocks.LAVA));
            // barrierNoise at (i,j,k) — the EXACT value calculatePressure's MutableDouble memoizes.
            // Use the real FunctionContext `pos` (NOT a fresh SinglePointContext): the router's
            // barrierNoise is a wrapped/cached DF whose value can depend on the context type.
            double barrier = this.barrierNoise.compute(pos);
            if (this.scAqAcc == null) {
                this.scAqAcc = scBuildAquiferAccumulator();
            }
            this.scAqAcc.add(i, j, k, density, barrier, cpuId, cpuDist1, cpuFl1, cpuIdx1, cpuPos1);
        }
        return fullResult;
    }

    // Builds the per-instance accumulator: the (CPU) per-cell FluidStatus for every aquifer cell
    // (computeFluid is NOT GPU-ported), the grid geometry, and the global fluid picker params
    // (probed). The GPU decision kernel reads these + the CPU-preloaded aquiferLocationCache.
    @Unique
    private AquiferGpuVerify.Accumulator scBuildAquiferAccumulator() {
        int cells = this.aquiferLocationCache.length;
        int[] fl = new int[cells];
        int[] ft = new int[cells];
        for (int idx = 0; idx < cells; idx++) {
            Aquifer.FluidStatus fs = this.getAquiferStatus(this.aquiferLocationCache[idx]);
            fl[idx] = fs.fluidLevel;
            ft[idx] = scEncodeFluidType(fs.fluidType);
        }
        // Probe the global fluid picker: y far below -> lava status (level=lavaLevel),
        // y far above -> default status (level=seaLevel, type=defaultFluid).
        Aquifer.FluidStatus loS = this.globalFluidPicker.computeFluid(0, -30_000_000, 0);
        Aquifer.FluidStatus hiS = this.globalFluidPicker.computeFluid(0, 30_000_000, 0);
        int lavaLevel = loS.fluidLevel;
        int seaLevel = hiS.fluidLevel;
        int defaultFluidId = scEncodeFluidType(hiS.fluidType);
        return new AquiferGpuVerify.Accumulator(this.aquiferLocationCache, fl, ft,
                this.minGridX, this.minGridY, this.minGridZ, this.gridSizeX, this.gridSizeZ,
                seaLevel, lavaLevel, defaultFluidId);
    }

    @Unique
    private static int scEncodeFluidType(BlockState s) {
        if (s == null || s.isAir()) return 1;
        if (s.is(Blocks.LAVA)) return 3;
        return 2; // water / default fluid
    }

    // ==================== STAGE-5 COMPACT-IDS aux (worker-side, prefetch seam) ====================

    /**
     * COMPACT-IDS ({@code -Dsuperchunk.gpu.compactIds=probe}): packages this aquifer's
     * side of the per-chunk decide-kernel aux — the EXACT mode-A/census upload contract
     * ({@code scBuildAquiferAccumulator} pattern): per-cell FluidStatus levels/types for
     * ALL aquifer cells (computeFluid stays CPU-authoritative; the cross-chunk
     * ScAquiferCellCache amortizes it), the CPU-preloaded location cache (immutable
     * after {@code onInit} — referenced, not copied), grid geometry, probed
     * global-picker params, the authoritative FLOWING_UPDATE_SIMULARITY, and the
     * compiled {@code barrierNoise} (host-side route binding only — the kernel computes
     * barrier itself from the emitted AST). Runs on the WORKER at the prefetch seam,
     * strictly before this chunk's fill consumes the same instance (race-free; it just
     * warms the same caches the fill would). Returns {@code null} on any failure.
     */
    @Override
    public dev.superchunk.gpu.dfc.CompactIds.AquiferAux superchunk$compactAquiferAux() {
        try {
            int cells = this.aquiferLocationCache.length;
            int[] fl = new int[cells];
            int[] ft = new int[cells];
            for (int idx = 0; idx < cells; idx++) {
                Aquifer.FluidStatus fs = this.getAquiferStatus(this.aquiferLocationCache[idx]);
                fl[idx] = fs.fluidLevel;
                int enc = scEncodeFluidTypeStrict(fs.fluidType);
                if (enc < 0) {
                    // Custom/datapack fluid state: the 1-byte alphabet cannot represent it
                    // (the CONSUME path maps byte 2 back to exactly minecraft:water). Reject
                    // the whole chunk's aux — it stays corner-only, terrain untouched.
                    dev.superchunk.gpu.dfc.CompactIds.noteAuxFluidTypeRejected();
                    return null;
                }
                ft[idx] = enc;
            }
            Aquifer.FluidStatus loS = this.globalFluidPicker.computeFluid(0, -30_000_000, 0);
            Aquifer.FluidStatus hiS = this.globalFluidPicker.computeFluid(0, 30_000_000, 0);
            int defaultEnc = scEncodeFluidTypeStrict(hiS.fluidType);
            if (defaultEnc < 0) {
                dev.superchunk.gpu.dfc.CompactIds.noteAuxFluidTypeRejected();
                return null;
            }
            return new dev.superchunk.gpu.dfc.CompactIds.AquiferAux(
                    this.aquiferLocationCache, fl, ft,
                    this.minGridX, this.minGridY, this.minGridZ, this.gridSizeX, this.gridSizeZ,
                    hiS.fluidLevel, loS.fluidLevel, defaultEnc,
                    FLOWING_UPDATE_SIMULARITY, this.barrierNoise);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * STRICT variant of {@link #scEncodeFluidType} for the compact-ids aux: only the
     * exact vanilla default states map ({@code air->1, water->2, lava->3}); anything
     * else (datapack custom fluid, non-default water/lava state) returns {@code -1} so
     * the caller rejects the aux. The relaxed encode above collapses "any other fluid"
     * to 2 — fine for the diagnostic verify harness whose water compare is class-level,
     * but the CONSUME path maps byte 2 back to exactly {@code Blocks.WATER}'s default
     * state, so a lossy encode would ship the wrong block. Reference compares are exact:
     * vanilla {@code Aquifer} returns these singleton default-state instances.
     */
    @Unique
    private static int scEncodeFluidTypeStrict(BlockState s) {
        if (s == null || s.isAir()) return 1;
        if (s == Blocks.WATER.defaultBlockState()) return 2;
        if (s == Blocks.LAVA.defaultBlockState()) return 3;
        return -1;
    }

    // ==================== STAGE-3 BLOCK-ID CENSUS (verify-only) ====================

    // Census: run the EXACT vanilla density<=0 path (output ships bit-correct), record the
    // shipped aquifer decision + shouldScheduleFluidUpdate + the fed mode-C density and batch
    // the block for the per-chunk aquifer_census GPU compare. Mirrors scAquiferVerifyComputeFull.
    @Unique
    private BlockState scCensusComputeFull(DensityFunction.FunctionContext pos, double density,
                                           int i, int j, int k, BlockState globalAt) {
        BlockState fullResult;
        if (globalAt.is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = false;
            fullResult = Blocks.LAVA.defaultBlockState();
        } else {
            aquiferExtracted$refreshDistPosIdx(i, j, k);
            fullResult = aquiferExtracted$applyPost(pos, density, j, i, k);
        }
        if (BlockIdCensus.consumeArm(i, j, k)) {
            int aqClass = AquiferGpuVerify.encodeResult(
                    fullResult == null,
                    fullResult != null && fullResult.isAir(),
                    fullResult != null && fullResult.is(Blocks.WATER),
                    fullResult != null && fullResult.is(Blocks.LAVA));
            // barrierNoise at (i,j,k) — the EXACT value calculatePressure's memoized
            // MutableDouble would hold (same real FunctionContext, see aquiferVerify note).
            double barrier = this.barrierNoise.compute(pos);
            scCensusAcc().add(i, j, k, density, barrier, aqClass, this.shouldScheduleFluidUpdate);
        }
        return fullResult;
    }

    // Builds (lazily) the per-instance census accumulator: the CPU-built per-cell FluidStatus
    // for all aquifer cells (the plan's mode-A upload contract, scBuildAquiferAccumulator
    // pattern), the grid geometry, the probed global-picker params, vanilla's
    // FLOWING_UPDATE_SIMULARITY (authoritative shadowed constant, passed to the kernel), and
    // the OnDeviceInterp field state (the mode-C density-equality proof-leg coverage flag).
    @Unique
    private BlockIdCensus.Accumulator scCensusAcc() {
        BlockIdCensus.Accumulator acc = this.scCensusAcc;
        if (acc == null) {
            int cells = this.aquiferLocationCache.length;
            int[] fl = new int[cells];
            int[] ft = new int[cells];
            for (int idx = 0; idx < cells; idx++) {
                Aquifer.FluidStatus fs = this.getAquiferStatus(this.aquiferLocationCache[idx]);
                fl[idx] = fs.fluidLevel;
                ft[idx] = scEncodeFluidType(fs.fluidType);
            }
            Aquifer.FluidStatus loS = this.globalFluidPicker.computeFluid(0, -30_000_000, 0);
            Aquifer.FluidStatus hiS = this.globalFluidPicker.computeFluid(0, 30_000_000, 0);
            acc = new BlockIdCensus.Accumulator(this.aquiferLocationCache, fl, ft,
                    this.minGridX, this.minGridY, this.minGridZ, this.gridSizeX, this.gridSizeZ,
                    hiS.fluidLevel, loS.fluidLevel, scEncodeFluidType(hiS.fluidType),
                    FLOWING_UPDATE_SIMULARITY,
                    dev.superchunk.gpu.dfc.OnDeviceInterp.fieldStateFor(this.noiseChunk));
            this.scCensusAcc = acc;
        }
        return acc;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        // preload position cache
        if (this.aquiferLocationCache.length % (this.gridSizeX * this.gridSizeZ) != 0) {
            throw new AssertionError("Array length");
        }

        int sizeY = this.aquiferLocationCache.length / (this.gridSizeX * this.gridSizeZ);

        // BlockPos.asLong packs Y into 12 signed bits (-2048..2047) — a fixed encoding
        // limit, independent of world height. Whether any cell can overshoot it is derived
        // here from the LIVE grid geometry: minGridY/sizeY follow the dimension's
        // min_y/height at aquifer construction, so datapack heights AND runtime height-mod
        // edits are detected automatically, for both the world top and its depth:
        //   lowest jittered y  = minGridY * 12                    (nextInt(9) only adds)
        //   highest jittered y = (minGridY + sizeY - 1) * 12 + 8
        // On vanilla-legal max worlds (min_y >= -2032, build top <= 2031, e.g. jjthunder
        // to the max) only the one-cell top/bottom PADDING rows can overshoot (top row up
        // to y 2048, bottom row down to y -2052): unclamped they wrap on pack and decode
        // to a negative cache index in every full-grid consumer (ceiling scan, verify
        // paths). Clamping them into packable range is invisible to vanilla parity: a
        // wrapped position sits ~4000 blocks away and never wins the closest-cell
        // contest, and every cell beyond the build limits computes the same uniform
        // status as its row neighbors (above-surface default at the top, floor lava at
        // the bottom), so block output is unchanged either way. Normal-height worlds take
        // the no-clamp fast path with bit-identical packed values.
        final int packYLo = -2048, packYHi = 2047;
        final boolean clampY = this.minGridY * 12 < packYLo
                || (this.minGridY + sizeY - 1) * 12 + 8 > packYHi;
        // If even the rows one cell INSIDE the padding overflow, vanilla-selectable cells
        // are moving: the world exceeds what BlockPos can pack (impossible via datapack —
        // only a core height-limit mod). Vanilla itself wrap-corrupts there; we clamp
        // instead, but aquifer placement near the extremes is then non-vanilla — say so.
        if ((this.minGridY + 1) * 12 < packYLo || (this.minGridY + sizeY - 2) * 12 + 8 > packYHi) {
            com.mojang.logging.LogUtils.getLogger().warn(
                    "[SuperChunk] world Y range (aquifer grid rows {}..{}) exceeds BlockPos packing "
                            + "(-2048..2047); placement jitter clamped — beyond-vanilla world limits, "
                            + "aquifers at the Y extremes will differ from (wrap-corrupted) vanilla.",
                    this.minGridY, this.minGridY + sizeY - 1);
        }

        final RandomSource random = RandomUtils.getRandom(this.positionalRandomFactory);
        // index: y, z, x
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < this.gridSizeZ; z++) {
                for (int x = 0; x < this.gridSizeX; x++) {
                    final int x1 = x + this.minGridX;
                    final int y1 = y + this.minGridY;
                    final int z1 = z + this.minGridZ;
                    RandomUtils.derive(this.positionalRandomFactory, random, x1, y1, z1);
                    int x2 = x1 * 16 + random.nextInt(10);
                    int y2 = y1 * 12 + random.nextInt(9);
                    int z2 = z1 * 16 + random.nextInt(10);
                    if (clampY) {
                        // world-height-derived guard (see envelope detection above the loop)
                        y2 = Math.max(packYLo, Math.min(packYHi, y2));
                    }
                    int index = this.getIndex(x1, y1, z1);
                    this.aquiferLocationCache[index] = BlockPos.asLong(x2, y2, z2);
                }
            }
        }
        for (long blockPosition : this.aquiferLocationCache) {
            if (blockPosition == Long.MAX_VALUE) {
                throw new AssertionError("Array initialization");
            }
        }
    }

    /**
     * @author ishland
     * @reason make C2 happier by splitting method into many
     */
    @Overwrite
    public BlockState computeSubstance(DensityFunction.FunctionContext pos, double density) {
        int i = pos.blockX();
        int j = pos.blockY();
        int k = pos.blockZ();
        if (density > 0.0) {
            this.shouldScheduleFluidUpdate = false;
            if (SC_BLOCKID_CENSUS && BlockIdCensus.isReady() && BlockIdCensus.consumeArm(i, j, k)) {
                // Census: vanilla's early solid return (null -> default stone, sched=false).
                // barrier is irrelevant for solids (the kernel's density>0 branch never reads it).
                scCensusAcc().add(i, j, k, density, 0.0, 0, false);
            }
            return null;
        } else {
            Aquifer.FluidStatus fluidLevel = this.globalFluidPicker.computeFluid(i, j, k);
            BlockState globalAt = fluidLevel.at(j);
            if (SC_BLOCKID_CENSUS && BlockIdCensus.isReady()) {
                // Census: never skip; run the FULL vanilla path (bit-correct), record the
                // shipped aquifer decision + fluid-tick bit, batch for the per-chunk GPU compare.
                return scCensusComputeFull(pos, density, i, j, k, globalAt);
            }
            if (SC_AQUIFER_VERIFY) {
                // Aquifer GPU block-decision VERIFY: never skip; run the full vanilla
                // path (bit-correct), record inputs, and compare the GPU decision per chunk.
                return scAquiferVerifyComputeFull(pos, density, i, j, k, globalAt);
            }
            if (SC_AIR_VERIFY) {
                // never skip; run full vanilla (bit-correct) and measure skip-safety
                return scAirVerifyComputeFull(pos, density, i, j, k, globalAt);
            }
            if (SC_ADAPTIVE_VERIFY) {
                return scAdaptiveVerifyComputeFull(pos, density, i, j, k, globalAt);
            }
            // SuperChunk ADAPTIVE air-skip: skip per-block aquifer work for any air block at/above
            // the local water ceiling PLUS the barrier margin (provably air against both the fluid and
            // the aquifer stone-barrier class; parity-safe at every height, no minY needed).
            if (SC_ADAPTIVE && !scAdaptiveGateTripped && globalAt.isAir() && j >= scColumnCeiling(i, k) + SC_BARRIER_MARGIN) {
                this.shouldScheduleFluidUpdate = false;
                return globalAt;
            }
            // SuperChunk air-column skip: high air block, no local aquifer reachable here, so
            // the local search would return this same AIR. shouldScheduleFluidUpdate is moot
            // for air (doFill only schedules updates / sets blocks for non-air results).
            if (SC_AIR_SKIP && j >= SC_AIR_SKIP_MIN_Y && globalAt.isAir()) {
                this.shouldScheduleFluidUpdate = false;
                return globalAt;
            }
            if (globalAt.is(Blocks.LAVA)) {
                this.shouldScheduleFluidUpdate = false;
                return Blocks.LAVA.defaultBlockState();
            } else {
                aquiferExtracted$refreshDistPosIdx(i, j, k);
                return aquiferExtracted$applyPost(pos, density, j, i, k);
            }
        }
    }

    // SuperChunk air-skip verify: run the EXACT vanilla density<=0 path (so output is
    // bit-correct), then categorize what vanilla produced for air-skip candidates.
    @Unique
    private BlockState scAirVerifyComputeFull(DensityFunction.FunctionContext pos, double density, int i, int j, int k, BlockState globalAt) {
        BlockState fullResult;
        if (globalAt.is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = false;
            fullResult = Blocks.LAVA.defaultBlockState();
        } else {
            aquiferExtracted$refreshDistPosIdx(i, j, k);
            fullResult = aquiferExtracted$applyPost(pos, density, j, i, k);
        }
        // categorize ONLY the air-skip candidate set (globalAt is air; lava globalAt is not a candidate)
        if (globalAt.isAir()) {
            boolean danger = false;
            if (fullResult == null) {
                scFullNull.incrementAndGet();          // null => caller places default STONE; skip => AIR => DANGER (stone deleted)
                danger = true;
            } else if (fullResult.isAir()) {
                scFullAir.incrementAndGet();            // AIR placed => skip-equivalent
            } else {
                scFullOther.incrementAndGet();          // real (fluid) block placed => skip would be WRONG
                danger = true;
            }
            // shouldScheduleFluidUpdate on an AIR result is a NO-OP in vanilla doFill: a tick is
            // only scheduled when the placed block has a non-empty fluid state (i.e. a fluid block,
            // which is already counted as OTHER above). So it is informational, NOT a danger.
            if (this.shouldScheduleFluidUpdate) {
                scFullSched.incrementAndGet();
            }
            if (danger) scDangerMaxY.accumulate(j);     // dangerMaxY tracks any non-AIR divergence (default STONE or a fluid)
            long checked = scAirChecked.incrementAndGet();
            long logAt = scNextLogAt.get();
            if (checked >= logAt && scNextLogAt.compareAndSet(logAt, logAt + 2_000_000L)) {
                scAirVerifyReport(checked);
            }
        }
        return fullResult;
    }

    @Unique
    private @Nullable BlockState aquiferExtracted$applyPost(DensityFunction.FunctionContext pos, double density, int j, int i, int k) {
        Aquifer.FluidStatus fluidLevel2 = this.getAquiferStatus(this.c2me$pos1);
        double d = similarity(this.c2me$dist1, this.c2me$dist2);
        BlockState blockState = fluidLevel2.at(j);
        if (d <= 0.0) {
            this.shouldScheduleFluidUpdate = d >= FLOWING_UPDATE_SIMULARITY;
            return blockState;
        } else if (blockState.is(Blocks.WATER) && this.globalFluidPicker.computeFluid(i, j - 1, k).at(j - 1).is(Blocks.LAVA)) {
            this.shouldScheduleFluidUpdate = true;
            return blockState;
        } else {
//            MutableDouble mutableDouble = new MutableDouble(Double.NaN); // 234MB/s alloc rate at 480 cps
            this.c2me$mutableDoubleThingy = Double.NaN;
            Aquifer.FluidStatus fluidLevel3 = this.getAquiferStatus(this.c2me$pos2);
            double e = d * this.c2me$calculateDensityModified(pos, fluidLevel2, fluidLevel3);
            if (density + e > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return null;
            } else {
                return aquiferExtracted$getFinalBlockState(pos, density, d, fluidLevel2, fluidLevel3, blockState);
            }
        }
    }

    @Unique
    private BlockState aquiferExtracted$getFinalBlockState(DensityFunction.FunctionContext pos, double density, double d, Aquifer.FluidStatus fluidLevel2, Aquifer.FluidStatus fluidLevel3, BlockState blockState) {
        Aquifer.FluidStatus fluidLevel4 = this.getAquiferStatus(this.c2me$pos3);
        double f = similarity(this.c2me$dist1, this.c2me$dist3);
        if (aquiferExtracted$extractedCheckFG(pos, density, d, fluidLevel2, f, fluidLevel4)) return null;

        double g = similarity(this.c2me$dist2, this.c2me$dist3);
        if (aquiferExtracted$extractedCheckFG(pos, density, d, fluidLevel3, g, fluidLevel4)) return null;

        this.shouldScheduleFluidUpdate = true;
        return blockState;
    }

    @Unique
    private boolean aquiferExtracted$extractedCheckFG(DensityFunction.FunctionContext pos, double density, double d, Aquifer.FluidStatus fluidLevel2, double f, Aquifer.FluidStatus fluidLevel4) {
        if (f > 0.0) {
            double g = d * f * this.c2me$calculateDensityModified(pos, fluidLevel2, fluidLevel4);
            if (density + g > 0.0) {
                this.shouldScheduleFluidUpdate = false;
                return true;
            }
        }
        return false;
    }

    @Unique
    private void aquiferExtracted$refreshDistPosIdx(int x, int y, int z) {
        int gx = (x - 5) >> 4;
        int gy = Math.floorDiv(y + 1, 12);
        int gz = (z - 5) >> 4;

        if (SC_CELL_CACHE) {
            // Lever 3: the 12 lattice candidate longs (2x3x2) depend ONLY on (gx,gy,gz), so memoize
            // them per cell (single-entry last-cell memo). Consecutive same-cell blocks then skip the
            // 12 getIndex + 12 aquiferLocationCache loads and only redo the per-block distance math +
            // top-3 selection. The candidate array is filled and iterated in the EXACT loop order
            // (offY:-1..1, offZ:0..1, offX:0..1) so the tie-sensitive top-3 selection is byte-identical.
            long[] cand = this.scRefreshCand;
            if (cand == null) {
                cand = new long[12];
                this.scRefreshCand = cand;
                // round-5: candidate coords memoized UNPACKED too — saves 12x3 shift/sext
                // unpacks per block (pure precompute of the same ints; math unchanged).
                this.scRefreshCandX = new int[12];
                this.scRefreshCandY = new int[12];
                this.scRefreshCandZ = new int[12];
            }
            int[] candX = this.scRefreshCandX;
            int[] candY = this.scRefreshCandY;
            int[] candZ = this.scRefreshCandZ;
            if (!(this.scRefreshValid && gx == this.scRefreshGx && gy == this.scRefreshGy && gz == this.scRefreshGz)) {
                int ci = 0;
                for (int offY = -1; offY <= 1; ++offY) {
                    for (int offZ = 0; offZ <= 1; ++offZ) {
                        for (int offX = 0; offX <= 1; ++offX) {
                            long position = this.aquiferLocationCache[this.getIndex(gx + offX, gy + offY, gz + offZ)];
                            cand[ci] = position;
                            candX[ci] = BlockPos.getX(position);
                            candY[ci] = BlockPos.getY(position);
                            candZ[ci] = BlockPos.getZ(position);
                            ci++;
                        }
                    }
                }
                this.scRefreshGx = gx;
                this.scRefreshGy = gy;
                this.scRefreshGz = gz;
                this.scRefreshValid = true;
            }
            int d1 = Integer.MAX_VALUE;
            int d2 = Integer.MAX_VALUE;
            int d3 = Integer.MAX_VALUE;
            long p1 = 0;
            long p2 = 0;
            long p3 = 0;
            for (int ci = 0; ci < 12; ci++) {
                long position = cand[ci];
                int dx = candX[ci] - x;
                int dy = candY[ci] - y;
                int dz = candZ[ci] - z;
                int dist = dx * dx + dy * dy + dz * dz;
                if (d3 >= dist) {
                    p3 = position;
                    d3 = dist;
                }
                if (d2 >= dist) {
                    p3 = p2;
                    d3 = d2;
                    p2 = position;
                    d2 = dist;
                }
                if (d1 >= dist) {
                    p2 = p1;
                    d2 = d1;
                    p1 = position;
                    d1 = dist;
                }
            }
            this.c2me$dist1 = d1;
            this.c2me$dist2 = d2;
            this.c2me$dist3 = d3;
            this.c2me$pos1 = p1;
            this.c2me$pos2 = p2;
            this.c2me$pos3 = p3;
            return;
        }

        int dist1 = Integer.MAX_VALUE;
        int dist2 = Integer.MAX_VALUE;
        int dist3 = Integer.MAX_VALUE;
        long pos1 = 0;
        long pos2 = 0;
        long pos3 = 0;

        for (int offY = -1; offY <= 1; ++offY) {
            for (int offZ = 0; offZ <= 1; ++offZ) {
                for (int offX = 0; offX <= 1; ++offX) {
                    int posIdx = this.getIndex(gx + offX, gy + offY, gz + offZ);

                    long position = this.aquiferLocationCache[posIdx];

                    int dx = BlockPos.getX(position) - x;
                    int dy = BlockPos.getY(position) - y;
                    int dz = BlockPos.getZ(position) - z;
                    int dist = dx * dx + dy * dy + dz * dz;

                    // unexplainable branch prediction magic
                    if (dist3 >= dist) {
                        pos3 = position;
                        dist3 = dist;
                    }
                    if (dist2 >= dist) {
                        pos3 = pos2;
                        dist3 = dist2;
                        pos2 = position;
                        dist2 = dist;
                    }
                    if (dist1 >= dist) {
                        pos2 = pos1;
                        dist2 = dist1;
                        pos1 = position;
                        dist1 = dist;
                    }
                }
            }
        }

        this.c2me$dist1 = dist1;
        this.c2me$dist2 = dist2;
        this.c2me$dist3 = dist3;
        this.c2me$pos1 = pos1;
        this.c2me$pos2 = pos2;
        this.c2me$pos3 = pos3;
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    private Aquifer.FluidStatus getAquiferStatus(long pos) {
        int i = BlockPos.getX(pos);
        int j = BlockPos.getY(pos);
        int k = BlockPos.getZ(pos);
        int l = i >> 4; // C2ME - inline: floorDiv(i, 16)
        int m = Math.floorDiv(j, 12); // C2ME - inline
        int n = k >> 4; // C2ME - inline: floorDiv(k, 16)
        int o = this.getIndex(l, m, n);
        Aquifer.FluidStatus fluidLevel = this.aquiferCache[o];
        if (fluidLevel != null) {
            return fluidLevel;
        } else {
            Aquifer.FluidStatus fluidLevel2;
            ScAquiferCellCache cache;
            if (SC_CELL_CACHE && (cache = this.scCellCache()) != null) {
                // Lever 1: consult the per-dimension cross-chunk cache before recomputing. computeFluid
                // is a pure deterministic function of the cell (l,m,n)+seed when the chunk's Blender is
                // empty (guaranteed here: scCellCache() is null for blending chunks), so a hit is
                // byte-identical to a recompute; the returned immutable FluidStatus is safe to share.
                long cellKey = ScAquiferCellCache.packCell(l, m, n);
                Aquifer.FluidStatus hit = cache.get(cellKey);
                if (hit != null) {
                    this.aquiferCache[o] = hit;
                    return hit;
                }
                fluidLevel2 = this.computeFluid(i, j, k);
                cache.put(cellKey, fluidLevel2);
            } else {
                fluidLevel2 = this.computeFluid(i, j, k);
            }
            this.aquiferCache[o] = fluidLevel2;
            return fluidLevel2;
        }
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    private int computeSurfaceLevel(int blockX, int blockY, int blockZ, Aquifer.FluidStatus defaultFluidLevel, int surfaceHeightEstimate, boolean bl) {
        DensityFunction.SinglePointContext unblendedNoisePos = new DensityFunction.SinglePointContext(blockX, blockY, blockZ);
        double d;
        double e;
        if (OverworldBiomeBuilder.isDeepDarkRegion(this.erosion, this.depth, unblendedNoisePos)) {
            d = -1.0;
            e = -1.0;
        } else {
            int i = surfaceHeightEstimate + 8 - blockY;
            double f = bl ? Mth.clampedLerp(1.0, 0.0, ((double) i) / 64.0) : 0.0; // inline
            double g = Mth.clamp(this.fluidLevelFloodednessNoise.compute(unblendedNoisePos), -1.0, 1.0);
            d = g + 0.8 + (f - 1.0) * 1.2; // inline
            e = g + 0.3 + (f - 1.0) * 1.1; // inline
        }

        int i;
        if (e > 0.0) {
            i = defaultFluidLevel.fluidLevel;
        } else if (d > 0.0) {
            i = this.computeRandomizedFluidSurfaceLevel(blockX, blockY, blockZ, surfaceHeightEstimate);
        } else {
            i = DimensionType.WAY_BELOW_MIN_Y;
        }

        return i;
    }

    /**
     * @author ishland
     * @reason optimize, split method into many
     */
    @Overwrite
    private double calculatePressure(
            DensityFunction.FunctionContext pos, MutableDouble mutableDouble, Aquifer.FluidStatus fluidLevel, Aquifer.FluidStatus fluidLevel2
    ) {
        int i = pos.blockY();
        BlockState blockState = fluidLevel.at(i);
        BlockState blockState2 = fluidLevel2.at(i);
        if ((!blockState.is(Blocks.LAVA) || !blockState2.is(Blocks.WATER)) && (!blockState.is(Blocks.WATER) || !blockState2.is(Blocks.LAVA))) {
            int j = Math.abs(fluidLevel.fluidLevel - fluidLevel2.fluidLevel);
            if (j == 0) {
                return 0.0;
            } else {
                double d = 0.5 * (double)(fluidLevel.fluidLevel + fluidLevel2.fluidLevel);
                final double q = aquiferExtracted$getQ(i, d, j);

                return aquiferExtracted$postCalculateDensity(pos, mutableDouble, q);
            }
        } else {
            return 2.0;
        }
    }

    @Unique
    private double c2me$calculateDensityModified(
            DensityFunction.FunctionContext pos, Aquifer.FluidStatus fluidLevel, Aquifer.FluidStatus fluidLevel2
    ) {
        int i = pos.blockY();
        BlockState blockState = fluidLevel.at(i);
        BlockState blockState2 = fluidLevel2.at(i);
        if ((!blockState.is(Blocks.LAVA) || !blockState2.is(Blocks.WATER)) && (!blockState.is(Blocks.WATER) || !blockState2.is(Blocks.LAVA))) {
            int j = Math.abs(fluidLevel.fluidLevel - fluidLevel2.fluidLevel);
            if (j == 0) {
                return 0.0;
            } else {
                double d = 0.5 * (double)(fluidLevel.fluidLevel + fluidLevel2.fluidLevel);
                final double q = aquiferExtracted$getQ(i, d, j);

                return aquiferExtracted$postCalculateDensityModified(pos, q);
            }
        } else {
            return 2.0;
        }
    }

    @Unique
    private double aquiferExtracted$postCalculateDensity(DensityFunction.FunctionContext pos, MutableDouble mutableDouble, double q) {
        double r;
        if (!(q < -2.0) && !(q > 2.0)) {
            double s = mutableDouble.getValue();
            if (Double.isNaN(s)) {
                double t = this.barrierNoise.compute(pos);
                mutableDouble.setValue(t);
                r = t;
            } else {
                r = s;
            }
        } else {
            r = 0.0;
        }

        return 2.0 * (r + q);
    }

    @Unique
    private double aquiferExtracted$postCalculateDensityModified(DensityFunction.FunctionContext pos, double q) {
        double r;
        if (!(q < -2.0) && !(q > 2.0)) {
            double s = this.c2me$mutableDoubleThingy;
            if (Double.isNaN(s)) {
                double t = this.barrierNoise.compute(pos);
                this.c2me$mutableDoubleThingy = t;
                r = t;
            } else {
                r = s;
            }
        } else {
            r = 0.0;
        }

        return 2.0 * (r + q);
    }

    @Unique
    private static double aquiferExtracted$getQ(double i, double d, double j) {
        double e = i + 0.5 - d;
        double f = j / 2.0;
        double o = f - Math.abs(e);
        double q;
        if (e > 0.0) {
            if (o > 0.0) {
                q = o / 1.5;
            } else {
                q = o / 2.5;
            }
        } else {
            double p = 3.0 + o;
            if (p > 0.0) {
                q = p / 3.0;
            } else {
                q = p / 10.0;
            }
        }
        return q;
    }

    /**
     * @author ishland
     * @reason optimize
     */
    @Overwrite
    private BlockState computeFluidType(int blockX, int blockY, int blockZ, Aquifer.FluidStatus defaultFluidLevel, int fluidLevel) {
        BlockState blockState = defaultFluidLevel.fluidType;
        if (fluidLevel <= -10 && fluidLevel != DimensionType.WAY_BELOW_MIN_Y && defaultFluidLevel.fluidType != Blocks.LAVA.defaultBlockState()) {
            int k = blockX >> 6; // floorDiv(blockX, 64)
            int l = Math.floorDiv(blockY, 40);
            int m = blockZ >> 6; // floorDiv(blockZ, 64)
            double d = this.lavaNoise.compute(new DensityFunction.SinglePointContext(k, l, m));
            if (Math.abs(d) > 0.3) {
                blockState = Blocks.LAVA.defaultBlockState();
            }
        }

        return blockState;
    }

}
