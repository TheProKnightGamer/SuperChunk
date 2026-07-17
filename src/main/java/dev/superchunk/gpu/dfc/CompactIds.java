package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IXoroshiro128PlusPlusRandomDeriver;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.CacheLikeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.SubCompiledDensityFunction;
import dev.superchunk.gpu.OpenCLBackend;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * SuperChunk GPU — <b>COMPACT-READBACK block ids</b>, STAGE 5 of the GPU-AHEAD plan
 * ({@code -Dsuperchunk.gpu.compactIds}, values {@code off|probe}, default {@code off}).
 *
 * <p><b>What PROBE does.</b> The PRODUCTION per-block decide kernel
 * ({@code sc_decide_multichunk}) is chained inside the EXISTING batched dispatch
 * ({@link GpuBatchDispatcher#batchFill} — corner NDRange &rarr; aux upload &rarr; decide
 * kernel &rarr; readbacks, all on the ONE in-order drainer queue), computing for every
 * block of every batched chunk: in-register interpolation of the fused corner grids
 * (X&rarr;Y&rarr;Z {@code Mth.lerp3} for the finalDensity path, Y&rarr;X&rarr;Z value
 * order for the vein DFs), the REAL finalDensity tail (EMITTED from the actual router
 * AST between the {@code interpolated()} markers and the root —
 * {@link OpenCLAstEmitter#emitDecide}; nothing dimension-specific is hand-coded), the
 * Stage-3-proven {@code aq_decide} aquifer decision + {@code shouldScheduleFluidUpdate}
 * bit for {@code density<=0}, and the REAL vanilla {@code OreVeinifier} decision (3
 * vein grids + the emitted vein tail ASTs + the SHARED census-proven
 * {@code aq_vein_decide} with bit-exact Xoroshiro draws) for every aquifer-null block
 * in the vein band (density&gt;0 solids AND barrier-solidified stone, exactly the
 * MaterialRuleList fall-through). Output is
 * ONE byte/block (id | bit7 = sched), read back pinned alongside the corner readback
 * and stored in {@link GpuBatchStore.Stored#ids} next to the grids. In PROBE mode the
 * consumer ({@link ChunkGridCache} serve path) ignores the ids entirely — they are
 * DISCARDED; the counters below prove they arrived. This measures the PLUMBING TAX
 * only (Stage-5 gate: cps &ge; 97% of baseline).
 *
 * <p><b>Aux contract</b> (captured WORKER-side at the prefetch seam,
 * {@link GpuBatchPrefetch#wrapNoise}, where the {@code NoiseChunk} exists): the
 * mode-A/census-proven per-chunk aquifer snapshot ({@link AquiferAux}: 315-cell
 * FluidStatus levels/types + CPU-preloaded location cache + grid geometry + probed
 * global-picker params + FLOWING_UPDATE_SIMULARITY), the beard AABB (the proven-exact
 * beardSkip bounds — blocks inside it get the beard-sentinel byte, CPU decides them),
 * the ore-vein positional seed halves (per-dimension, from
 * {@code RandomState.oreRandom()}), and the veins-enabled bit. If capture fails for a
 * chunk (no {@code NoiseBasedAquifer} — nether/end/disabled aquifer —, blending
 * active, no captured route for the dimension, non-Xoroshiro ore RNG, ...) that
 * chunk's ids are simply ABSENT and it follows the normal corner-only path (clean
 * fallback, counted per reason).
 *
 * <p><b>Per-dimension routing.</b> {@link #captureRouter} runs at the C2ME router
 * compile ({@code MixinNoiseConfig}) and captures each dimension's finalDensity/
 * barrier/vein ASTs (stashed by {@code BytecodeGen.compile} — capture is LAZY and
 * deliberately does NOT require any GPU form: the full finalDensity never
 * GPU-compiles as one kernel; member/AST identity is resolved only at
 * {@link #buildPlan} time) + the ore positional seed. A chunk's aux binds to its
 * route via the aquifer's own {@code barrierNoise} GPU-delegate identity (the
 * delegate object survives the per-chunk {@code mapAll} copy and needs no
 * resolution), and the decide dispatcher only decides chunks whose route matches
 * the plan it was built for — a second dimension sharing the batcher can never be
 * decided with the wrong math (it just gets no ids).
 *
 * <p>Everything is fail-soft: any error disables only the decide chain (corner batching
 * and the whole non-batched per-worker fused path are COMPLETELY untouched). fp64 only.
 *
 * <p>All log lines are greppable as {@code [compact-ids]}.
 */
public final class CompactIds {

    private static final Logger LOG = LoggerFactory.getLogger("SuperChunk-CompactIds");

    /**
     * Raw flag value ({@code off|probe|verify|on}). DEFAULT {@code on} since round 8
     * (user decision: enabling the GPU should deliver the full validated recipe out of
     * the box). The chain is opt-in at the {@code gpu.enabled} level, fp64-GPU-only,
     * fail-soft per chunk (absent aux/route → the vanilla CPU decide path), and its
     * output envelope is measured by the flip census — zero deviations across every
     * census ever run (455.9M + 4×460M blocks). {@code -Dsuperchunk.gpu.compactIds=off}
     * restores the strict bit-exact-gate-only mode.
     */
    private static final String MODE = System.getProperty("superchunk.gpu.compactIds", "on").trim();
    /**
     * CONSUME mode ({@code on}, Stage 6): the fill seam consumes the ids INSTEAD of
     * running the per-block decision machinery ({@link CompactConsume}).
     */
    public static final boolean CONSUME = "on".equalsIgnoreCase(MODE);
    /**
     * VERIFY mode ({@code verify}, Stage 6 side-effect equivalence gate): the ORIGINAL
     * loop runs and ships; after it, the consume path's predicted palette blocks,
     * heightmaps, section counters, Lithium flags and postprocessing set are compared
     * against the shipped chunk ({@link CompactConsume#verifyCompare}) — zero
     * divergence required before any {@code on} A/B.
     */
    public static final boolean VERIFY = "verify".equalsIgnoreCase(MODE);
    /**
     * Decide CHAIN active (modes {@code probe|verify|on}): aux is captured, the chained
     * {@code sc_decide_multichunk} kernel runs, ids ride to {@link GpuBatchStore}. The
     * historical name is kept — in plain {@code probe} the consumer discards the ids
     * (Stage-5 plumbing-tax probe); {@code verify}/{@code on} additionally read them.
     */
    public static final boolean PROBE = "probe".equalsIgnoreCase(MODE) || VERIFY || CONSUME;

    private CompactIds() {
    }

    // =====================================================================
    // Per-dimension routes (captured at router compile).
    // =====================================================================

    /**
     * One dimension's route: the compiled router ASTs (captured LAZILY — no GPU form
     * required; the decide plan emits from the ASTs and resolves fused-member identity
     * only at {@link #buildPlan} time, long after every delegate has resolved) + the
     * barrier binding key + ore seed. The full finalDensity deliberately has NO GPU
     * kernel of its own (its tree contains McToAst-opaque nodes, e.g. vanilla
     * {@code BlendedNoise}); only the AST is needed here.
     */
    public static final class Route {
        final AstNode finalDensityAst;
        final AstNode barrierAst;
        final AstNode veinToggleAst;
        final AstNode veinRidgedAst;
        final AstNode veinGapAst;
        final long oreSeedLo;
        final long oreSeedHi;
        final boolean oreSeedOk;

        Route(AstNode finalDensityAst, AstNode barrierAst,
              AstNode veinToggleAst, AstNode veinRidgedAst, AstNode veinGapAst,
              long oreSeedLo, long oreSeedHi, boolean oreSeedOk) {
            this.finalDensityAst = finalDensityAst;
            this.barrierAst = barrierAst;
            this.veinToggleAst = veinToggleAst;
            this.veinRidgedAst = veinRidgedAst;
            this.veinGapAst = veinGapAst;
            this.oreSeedLo = oreSeedLo;
            this.oreSeedHi = oreSeedHi;
            this.oreSeedOk = oreSeedOk;
        }
    }

    private static final List<Route> ROUTES = new CopyOnWriteArrayList<>();
    /**
     * barrierNoise GPU DELEGATE -> route (the chunk-side binding key). Keyed by the
     * {@link GpuDfcDelegate} object — NOT its resolved {@code gpu()} — because the
     * delegate is the identity that survives both the per-chunk {@code mapAll} copy
     * ({@code c2me$copyGpuDelegateFrom}) and any deferred (merged-batch) resolution
     * ordering: it exists and is stable from the moment {@code maybeAttach} runs.
     */
    private static final Map<GpuDfcDelegate, Route> ROUTE_BY_BARRIER = new ConcurrentHashMap<>();

    /**
     * Captures one dimension's route from the just-compiled router. Called from
     * {@code MixinNoiseConfig.postCreate} after the DFC compile. Reads the ASTs
     * stashed by {@code BytecodeGen.compile} ({@code c2me$getSourceAst}) — a route is
     * captured even though the FULL finalDensity never GPU-compiles as one kernel
     * (only its interpolated subtrees do). The only hard requirements are the
     * finalDensity+barrier ASTs and the barrier's GPU delegate (the chunk-side
     * binding key — without it no chunk could ever be routed here safely).
     * No-op unless PROBE. Never throws.
     */
    public static void captureRouter(RandomState randomState, NoiseRouter compiledRouter) {
        if (!PROBE || compiledRouter == null) {
            return;
        }
        try {
            AstNode finAst = astOf(compiledRouter.finalDensity());
            AstNode barAst = astOf(compiledRouter.barrierNoise());
            GpuDfcDelegate barKey = delegateOf(compiledRouter.barrierNoise());
            if (finAst == null || barAst == null || barKey == null) {
                LOG.info("[compact-ids] route NOT captured for this dimension (finalDensityAst={}, barrierAst={}, "
                                + "barrierDelegate={}) — chunks of this dimension get no ids. (A constant/uncompiled "
                                + "barrier means no NoiseBasedAquifer route binding is possible — expected for "
                                + "nether/end.)",
                        finAst != null, barAst != null, barKey != null);
                return;
            }
            long lo = 0L, hi = 0L;
            boolean seedOk = false;
            try {
                PositionalRandomFactory ore = randomState == null ? null : randomState.oreRandom();
                if (ore instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory) {
                    lo = ((IXoroshiro128PlusPlusRandomDeriver) ore).getSeedLo();
                    hi = ((IXoroshiro128PlusPlusRandomDeriver) ore).getSeedHi();
                    seedOk = true;
                }
            } catch (Throwable ignored) {
            }
            Route r = new Route(finAst, barAst,
                    astOf(compiledRouter.veinToggle()),
                    astOf(compiledRouter.veinRidged()),
                    astOf(compiledRouter.veinGap()),
                    lo, hi, seedOk);
            ROUTES.add(r);
            ROUTE_BY_BARRIER.put(barKey, r);
            // Activation marker for probe-run greps: "[compact-ids] route captured".
            LOG.info("[compact-ids] route captured: dim#{} interpolatedRoots={} veinAsts={}/{}/{} "
                            + "oreSeed(Xoroshiro)={} (routes={}).",
                    ROUTES.size(), countInterpolated(finAst),
                    r.veinToggleAst != null, r.veinRidgedAst != null, r.veinGapAst != null,
                    seedOk, ROUTES.size());
        } catch (Throwable t) {
            LOG.warn("[compact-ids] route capture threw — this dimension gets no ids.", t);
        }
    }

    /**
     * The AST a router slot was compiled from: prefer the {@code BytecodeGen.compile}
     * stash (present even when the DF has no GPU form); fall back to the GPU form's
     * own AST for robustness. {@code null} for constants/uncompiled slots.
     */
    private static AstNode astOf(DensityFunction df) {
        if (df instanceof SubCompiledDensityFunction scdf) {
            AstNode ast = scdf.c2me$getSourceAst();
            if (ast != null) {
                return ast;
            }
            GpuDfcDelegate del = scdf.c2me$getGpuDelegate();
            if (del != null && del.gpu() != null) {
                return del.gpu().sourceAst();
            }
        }
        return null;
    }

    private static GpuDfcDelegate delegateOf(DensityFunction df) {
        return df instanceof SubCompiledDensityFunction scdf ? scdf.c2me$getGpuDelegate() : null;
    }

    /** Counts interpolated-marker nodes in {@code n} (route diagnostics only). */
    private static int countInterpolated(AstNode n) {
        int c = 0;
        if (n instanceof CacheLikeNode cn
                && cn.getCacheLike() instanceof DensityFunctions.MarkerOrMarked mm
                && mm.type() == DensityFunctions.Marker.Type.Interpolated) {
            c = 1;
        }
        AstNode[] children = n.getChildren();
        if (children != null) {
            for (AstNode child : children) {
                c += countInterpolated(child);
            }
        }
        return c;
    }

    // =====================================================================
    // Aux capture (worker-side, prefetch seam).
    // =====================================================================

    /**
     * The aquifer-side aux snapshot built by {@link ScCompactAuxSource} (the
     * {@code NoiseBasedAquifer} mixin) — the exact census/mode-A upload contract.
     * {@code locCache} is the aquifer's own preloaded array (immutable after init;
     * referenced, not copied). {@code barrierNoise} is only used host-side to resolve
     * the route (never uploaded).
     */
    public static final class AquiferAux {
        public final long[] locCache;
        public final int[] fluidLevel;
        public final int[] fluidTypeId;
        public final int minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ;
        public final int seaLevel, lavaLevel, defaultFluidId;
        public final double flowingUpdateSimilarity;
        public final DensityFunction barrierNoise;
        /**
         * HIGH-AIR fast-path floor (kernel aux slot 17): for blocks with
         * {@code y >= airSkipY} the full aquifer chain provably returns AIR and its
         * bit7 depends only on {@code similarity(dist1,dist2)} — see
         * {@code aq_airpath_similarity} (aquifer.cl) for the proof sketch. Computed as
         * {@code max(all cell fluidLevels, seaLevel) + 5}: the +5 makes every pressure
         * pair's {@code q < -2} (barrier never consumed, no branch can return stone).
         * Overflow-clamped; a degenerate huge value just disables the fast path (safe).
         */
        public final int airSkipY;

        public AquiferAux(long[] locCache, int[] fluidLevel, int[] fluidTypeId,
                          int minGridX, int minGridY, int minGridZ, int gridSizeX, int gridSizeZ,
                          int seaLevel, int lavaLevel, int defaultFluidId,
                          double flowingUpdateSimilarity, DensityFunction barrierNoise) {
            this.locCache = locCache;
            this.fluidLevel = fluidLevel;
            this.fluidTypeId = fluidTypeId;
            this.minGridX = minGridX;
            this.minGridY = minGridY;
            this.minGridZ = minGridZ;
            this.gridSizeX = gridSizeX;
            this.gridSizeZ = gridSizeZ;
            this.seaLevel = seaLevel;
            this.lavaLevel = lavaLevel;
            this.defaultFluidId = defaultFluidId;
            this.flowingUpdateSimilarity = flowingUpdateSimilarity;
            this.barrierNoise = barrierNoise;
            if (AIR_PATH) {
                long maxFl = seaLevel;
                for (int fl : fluidLevel) {
                    if (fl > maxFl) {
                        maxFl = fl;
                    }
                }
                this.airSkipY = (int) Math.min(maxFl + 5L, Integer.MAX_VALUE);
            } else {
                // Kill-switch: an unreachable floor disables the kernel's fast path
                // (every block takes the full aq_decide, the pre-fast-path behavior).
                this.airSkipY = Integer.MAX_VALUE;
            }
        }
    }

    /** Kill-switch for the kernel high-air fast path (-Dsuperchunk.gpu.airPath=false). */
    private static final boolean AIR_PATH =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.gpu.airPath", "true"));

    /** CPU-assist split: every Nth chunk decides on the CPU (0 = off; see captureAux). */
    private static final int CPU_ASSIST_NTH = Integer.getInteger("superchunk.gpu.cpuAssistNth", 0);
    private static final java.util.concurrent.atomic.AtomicLong cpuAssistCounter =
            new java.util.concurrent.atomic.AtomicLong();

    /** One chunk's full decide-kernel aux (attached to the batch request). */
    public static final class ChunkAux {
        final Route route;
        final AquiferAux aq;
        final boolean veinsEnabled;
        final boolean hasBeard;
        final int bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ;

        ChunkAux(Route route, AquiferAux aq, boolean veinsEnabled,
                 boolean hasBeard, int[] box6) {
            this.route = route;
            this.aq = aq;
            this.veinsEnabled = veinsEnabled;
            this.hasBeard = hasBeard;
            this.bMinX = hasBeard ? box6[0] : 0;
            this.bMinY = hasBeard ? box6[1] : 0;
            this.bMinZ = hasBeard ? box6[2] : 0;
            this.bMaxX = hasBeard ? box6[3] : 0;
            this.bMaxY = hasBeard ? box6[4] : 0;
            this.bMaxZ = hasBeard ? box6[5] : 0;
        }
    }

    /**
     * Captures one chunk's decide-kernel aux at the prefetch seam. Returns
     * {@code null} on ANY failure — the chunk then follows the normal corner-only
     * batched path (ids absent; counted per reason). Never throws.
     */
    public static ChunkAux captureAux(NoiseChunk nc) {
        if (!PROBE || nc == null) {
            return null;
        }
        try {
            // fp64-only feature (aq_decide is vanilla-double): don't waste the
            // 315-cell FluidStatus capture when the decide chain can never build.
            if (OpenCLBackend.isFp32()) {
                return null;
            }
            // Blending-active chunks: the emitted tail assumes the fresh-chunk
            // (StripBlending) form — never decide them.
            if (nc.getBlender() != Blender.empty()) {
                auxFailBlending.increment();
                return null;
            }
            Aquifer aquifer;
            try {
                aquifer = nc.aquifer();
            } catch (Throwable t) {
                auxFailOther.increment();
                return null;
            }
            // Nether/End/disabled aquifers are not NoiseBasedAquifer -> skip cleanly.
            if (!(aquifer instanceof ScCompactAuxSource src)) {
                auxFailNoAquifer.increment();
                return null;
            }
            AquiferAux aq = src.superchunk$compactAquiferAux();
            if (aq == null) {
                auxFailOther.increment();
                return null;
            }
            Route route = routeOf(aq.barrierNoise);
            if (route == null) {
                auxFailNoRoute.increment();
                return null;
            }
            // Veins enabled for this dimension: the NoiseChunk built a second
            // MaterialRuleList filler (the OreVeinifier) iff oreVeinsEnabled.
            boolean veins = false;
            NoiseChunk.BlockStateFiller rule = ((IChunkNoiseSampler) nc).getBlockStateRule();
            if (rule instanceof MaterialRuleList mrl) {
                veins = mrl.materialRuleList() != null && mrl.materialRuleList().size() >= 2;
            }
            if (veins && !route.oreSeedOk) {
                auxFailVeinSeed.increment();
                return null;
            }
            boolean hasBeard = false;
            int[] box = new int[6];
            DensityFunctions.BeardifierOrMarker beard = ((IChunkNoiseSampler) nc).getBeardifier();
            if (beard instanceof ScBeardBoxAccess bb) {
                hasBeard = bb.superchunk$beardBox(box);
            }
            // CPU-ASSIST heterogeneous split (-Dsuperchunk.gpu.cpuAssistNth=N, default
            // 0 = off; CONSUME mode only), gated LAST so only otherwise-decidable
            // chunks count toward the Nth-chunk split: the skipped chunk rides the
            // corner-only batched path and the fill's ORIGINAL per-block decision
            // machinery decides it on the CPU — the standard, vanilla-exact "chunk
            // without ids" fallback. No new parity surface of any kind. MEASURED
            // NET-NEGATIVE on the RTX 3070 / i7-14700K (2026-07-16, r2048 sweep:
            // nth=8/6 = -1.5% cps vs off): the CPU-decided chunks' much longer
            // per-chunk latency stalls the neighbor-dependency schedule even though
            // raw CPU headroom existed. Kept default-OFF as a documented experiment
            // for other CPU/GPU balances.
            if (CPU_ASSIST_NTH > 0 && CONSUME
                    && cpuAssistCounter.incrementAndGet() % CPU_ASSIST_NTH == 0) {
                return null;
            }
            auxCaptured.increment();
            return new ChunkAux(route, aq, veins, hasBeard, box);
        } catch (Throwable t) {
            auxFailOther.increment();
            if (LOG.isDebugEnabled()) {
                LOG.debug("[compact-ids] aux capture threw — corner-only for this chunk.", t);
            }
            return null;
        }
    }

    private static Route routeOf(DensityFunction barrierNoise) {
        // The aquifer's barrierNoise is the per-chunk mapAll COPY of the router slot;
        // it carries the SAME GpuDfcDelegate object (c2me$copyGpuDelegateFrom), which
        // is the binding key — resolved or not (no gpu()!=null requirement, so the
        // binding is independent of merged-batch/deferred resolution ordering).
        if (barrierNoise instanceof SubCompiledDensityFunction scdf) {
            GpuDfcDelegate del = scdf.c2me$getGpuDelegate();
            if (del != null) {
                return ROUTE_BY_BARRIER.get(del);
            }
        }
        return null;
    }

    // =====================================================================
    // Plan build (per fused group; called by GpuBatchDispatcher.compile).
    // =====================================================================

    /** The decide program for one (fused group, route) pair. Holds no CL resources. */
    public static final class Plan {
        public final Route route;
        public final String source;                     // full concatenated CL source
        public final String kernelName = "sc_decide_multichunk";
        public final NoiseRegistry registry;            // decide program's own noise state
        public final boolean veins;                     // vein path resolved into the kernel
        public final int gridSlots;                     // ScDecG size (tail lerp3 + vein value-order slots)

        Plan(Route route, String source, NoiseRegistry registry, boolean veins, int gridSlots) {
            this.route = route;
            this.source = source;
            this.registry = registry;
            this.veins = veins;
            this.gridSlots = gridSlots;
        }
    }

    /**
     * Builds the decide plan for {@code fused}: finds the captured route whose
     * interpolated-marker subtrees are exactly {@code fused}'s corner-grid members,
     * emits the REAL finalDensity tail + barrier from the actual ASTs, and assembles
     * the full kernel source. Returns {@code null} (counted + logged) when no route
     * matches or emission fails — the decide chain then simply never runs.
     */
    public static Plan buildPlan(GpuFusedInterpolator fused) {
        if (!PROBE || fused == null) {
            return null;
        }
        try {
            if (OpenCLBackend.isFp32() || !OpenCLBackend.device().fp64()) {
                notePlanFail("fp64 required (aq_decide is vanilla-double; census proof is fp64)");
                return null;
            }
            List<GpuDensityFunction> members = fused.members();
            if (members == null || members.isEmpty()) {
                notePlanFail("fused group has no members");
                return null;
            }
            for (Route route : ROUTES) {
                Plan p = tryBuildPlan(route, members);
                if (p != null) {
                    LOG.info("[compact-ids] decide plan BUILT: {} grid slot(s) (tail lerp3 + vein value-order) over "
                                    + "M={} fused roots, veins={}, kernel={} ({} chars). PROBE mode: ids are read "
                                    + "back and DISCARDED.",
                            p.gridSlots, members.size(), p.veins, p.kernelName, p.source.length());
                    return p;
                }
            }
            notePlanFail("no captured route matches the fused group (routes=" + ROUTES.size() + ")");
            return null;
        } catch (Throwable t) {
            notePlanFail("plan build threw: " + t);
            LOG.warn("[compact-ids] plan build threw — decide chain unavailable.", t);
            return null;
        }
    }

    private static Plan tryBuildPlan(Route route, List<GpuDensityFunction> members) {
        AstNode tail = route.finalDensityAst;
        AstNode barrier = route.barrierAst;
        if (tail == null || barrier == null) {
            return null;
        }
        // Map every interpolated-marker CacheLikeNode in the finalDensity AST to its
        // fused corner-grid root index. A route only matches when EVERY interpolated
        // marker resolves (else the decide math would be wrong for this group).
        // Identity-first (route AST and fused members come from the SAME
        // BytecodeGen.compile session, so the marker delegates ARE the member ASTs),
        // structural-equality fallback (matchMember).
        IdentityHashMap<AstNode, Integer> nodeToRoot = new IdentityHashMap<>();
        if (!collectGridNodes(tail, members, nodeToRoot) || nodeToRoot.isEmpty()) {
            LOG.info("[compact-ids] route dim-candidate rejected for this fused group: an interpolated marker in "
                            + "finalDensity is not among the fused corner grids (fused members={}, matched so far={}) — "
                            + "its grid is not GPU-resident (e.g. a subtree with an McToAst-opaque node such as "
                            + "vanilla BlendedNoise never GPU-compiled), so the decide math cannot reproduce the "
                            + "CPU-shipped density. Trying next route.",
                    members.size(), nodeToRoot.size());
            return null;
        }
        // Compact slot assignment (distinct roots only), stable first-use order.
        Map<Integer, Integer> slotOfRoot = new LinkedHashMap<>();
        IdentityHashMap<AstNode, Integer> gridSlots = new IdentityHashMap<>();
        for (Map.Entry<AstNode, Integer> e : nodeToRoot.entrySet()) {
            int root = e.getValue();
            Integer slot = slotOfRoot.get(root);
            if (slot == null) {
                slot = slotOfRoot.size();
                slotOfRoot.put(root, slot);
            }
            gridSlots.put(e.getKey(), slot);
        }
        int tailSlots = slotOfRoot.size();

        // ---- ORE-VEIN resolution (Phase A). The vein router slots are NOT each
        // "exactly interpolated(inner)" in vanilla 1.21.1 — only veinToggle is;
        // veinRidged is add(-0.08f, max(abs(interp(A)), abs(interp(B)))) (TWO grids
        // + a per-block tail) and veinGap is a plain non-interpolated noise. So the
        // vein slots are resolved exactly like the finalDensity tail: collect every
        // interpolated marker into fused-member grid references (VALUE-order slots —
        // the per-block interpolator order the CPU vein DF reads see) and emit the
        // rest of each vein AST as in-kernel code. ----
        String veinFailWhy = null;
        boolean veins = true;
        if (route.veinToggleAst == null || route.veinRidgedAst == null || route.veinGapAst == null) {
            veins = false;
            veinFailWhy = "vein router slot AST(s) not captured (veinToggle/veinRidged/veinGap="
                    + (route.veinToggleAst != null) + "/" + (route.veinRidgedAst != null) + "/"
                    + (route.veinGapAst != null) + ")";
        } else if (!route.oreSeedOk) {
            veins = false;
            veinFailWhy = "ore RNG is not a Xoroshiro positional factory (no bit-exact GPU port)";
        }
        IdentityHashMap<AstNode, Integer> veinNodeToRoot = new IdentityHashMap<>();
        if (veins && (!collectGridNodes(route.veinToggleAst, members, veinNodeToRoot)
                || !collectGridNodes(route.veinRidgedAst, members, veinNodeToRoot)
                || !collectGridNodes(route.veinGapAst, members, veinNodeToRoot))) {
            veins = false;
            veinFailWhy = "a vein interpolated marker is not among the fused corner grids (matched "
                    + veinNodeToRoot.size() + " over M=" + members.size() + ")";
        }
        if (veins) {
            // A node shared between the tail and a vein tree would need TWO
            // interpolation orders behind ONE emitted symbol — impossible; refuse
            // veins (cannot happen with the vanilla router, guard for datapacks).
            for (AstNode n : veinNodeToRoot.keySet()) {
                if (gridSlots.containsKey(n)) {
                    veins = false;
                    veinFailWhy = "a vein grid node is shared with the finalDensity tail";
                    break;
                }
            }
        }
        // Vein slots: VALUE-order interpolation, numbered after the tail's lerp3
        // slots (the same root may legitimately hold BOTH a lerp3 and a value slot).
        Map<Integer, Integer> veinSlotOfRoot = new LinkedHashMap<>();
        if (veins) {
            for (Map.Entry<AstNode, Integer> e : veinNodeToRoot.entrySet()) {
                int root = e.getValue();
                Integer slot = veinSlotOfRoot.get(root);
                if (slot == null) {
                    slot = tailSlots + veinSlotOfRoot.size();
                    veinSlotOfRoot.put(root, slot);
                }
                gridSlots.put(e.getKey(), slot);
            }
        }

        NoiseRegistry registry = new NoiseRegistry();
        OpenCLAstEmitter.DecideResult emitted;
        try {
            emitted = OpenCLAstEmitter.emitDecide(tail, barrier,
                    veins ? route.veinToggleAst : null,
                    veins ? route.veinRidgedAst : null,
                    veins ? route.veinGapAst : null,
                    gridSlots, tailSlots + veinSlotOfRoot.size(), registry);
        } catch (Throwable t) {
            if (!veins) {
                LOG.info("[compact-ids] decide emission rejected this route ({}) — trying next.", String.valueOf(t));
                return null;
            }
            // The failure may live in a VEIN tree only (exotic datapack node):
            // retry tail-only so the aquifer/density plan is not lost with it.
            veins = false;
            veinFailWhy = "vein/tail emission rejected: " + t;
            for (AstNode n : veinNodeToRoot.keySet()) {
                gridSlots.remove(n);
            }
            veinSlotOfRoot.clear();
            registry = new NoiseRegistry();
            try {
                emitted = OpenCLAstEmitter.emitDecide(tail, barrier, gridSlots, tailSlots, registry);
            } catch (Throwable t2) {
                LOG.info("[compact-ids] decide emission rejected this route ({}) — trying next.", String.valueOf(t2));
                return null;
            }
        }
        if (!veins) {
            LOG.warn("[compact-ids] VEIN PATH NOT RESOLVED for this route: {} — plan continues aquifer/density-only; "
                    + "veins-enabled chunks will NOT be decided (consuming veinless ids would write plain stone "
                    + "where vanilla places ore veins).", veinFailWhy);
        }
        String headers = assembleHeaders();
        if (headers == null) {
            return null;
        }
        String generated = emitted.source() + "\n" + kernelEntry(emitted, slotOfRoot, veinSlotOfRoot, veins);
        if (Boolean.getBoolean("superchunk.gpu.dumpKernel")) {
            LOG.info("[compact-ids] === generated DECIDE tail/barrier/vein + kernel (tailSlots={}, veinSlots={}, veins={}) ===\n{}",
                    tailSlots, veinSlotOfRoot.size(), veins, generated);
        }
        String full = headers + "\n" + generated;
        return new Plan(route, full, registry, veins, tailSlots + veinSlotOfRoot.size());
    }

    /**
     * Walks {@code n} collecting every CacheLikeNode whose delegate IS a fused member's
     * source AST (identity first, structural equality fallback). Returns {@code false}
     * as soon as an interpolated marker CANNOT be matched (that would silently change
     * the math — the whole route is then unusable for this fused group).
     */
    private static boolean collectGridNodes(AstNode n, List<GpuDensityFunction> members,
                                            IdentityHashMap<AstNode, Integer> out) {
        if (n instanceof CacheLikeNode cn) {
            int k = matchMember(cn.getDelegate(), members);
            if (k >= 0) {
                out.put(cn, k);
                return true;   // matched: never descend into a grid subtree
            }
            Object cacheLike = cn.getCacheLike();
            if (cacheLike instanceof DensityFunctions.MarkerOrMarked mm
                    && mm.type() == DensityFunctions.Marker.Type.Interpolated) {
                return false;  // an interpolated grid the fused group doesn't compute
            }
            // other cache kinds: keep walking (their subtrees may contain grids)
        }
        for (AstNode c : n.getChildren()) {
            if (!collectGridNodes(c, members, out)) {
                return false;
            }
        }
        return true;
    }

    private static int matchMember(AstNode delegate, List<GpuDensityFunction> members) {
        if (delegate == null) {
            return -1;
        }
        for (int i = 0; i < members.size(); i++) {
            GpuDensityFunction m = members.get(i);
            if (m != null && m.sourceAst() == delegate) {
                return i;
            }
        }
        for (int i = 0; i < members.size(); i++) {
            GpuDensityFunction m = members.get(i);
            if (m != null && m.sourceAst() != null && delegate.equals(m.sourceAst())) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Kernel source assembly.
    // ------------------------------------------------------------------

    private static String assembleHeaders() {
        String noise = load("/superchunk/kernels/noise.cl");
        String support = load("/superchunk/kernels/dfc_support.cl");
        String rng = load("/superchunk/kernels/rng.cl");
        String aquifer = load("/superchunk/kernels/aquifer.cl");
        String decide = load("/superchunk/kernels/decide.cl");
        if (noise == null || support == null || rng == null || aquifer == null || decide == null) {
            notePlanFail("kernel resources missing");
            return null;
        }
        return "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n"
                + noise + "\n" + support + "\n" + rng + "\n" + aquifer + "\n" + decide;
    }

    /**
     * Generates the {@code sc_decide_multichunk} kernel entry with the fused root
     * indices baked in. Everything vanilla-exact: the tail/barrier/vein functions are
     * the emitted REAL ASTs; {@code aq_decide} and {@code aq_vein_decide} are the
     * Stage-3-census kernel code (aquifer.cl — the census proves the exact functions
     * this kernel calls). Decision structure mirrors MaterialRuleList: aquifer filler
     * first, and EVERY aquifer-null block (density&gt;0 solids AND density&le;0
     * barrier-solidified stone) falls through to the OreVeinifier — vanilla places
     * veins on solidified-stone blocks too, so gating veins on {@code density>0}
     * alone would diverge. All computeSubstance null paths set sched=false, so the
     * bit7 semantics are unchanged by the vein override.
     */
    private static String kernelEntry(OpenCLAstEmitter.DecideResult emitted,
                                      Map<Integer, Integer> slotOfRoot,
                                      Map<Integer, Integer> veinSlotOfRoot,
                                      boolean veins) {
        StringBuilder k = new StringBuilder(4096);
        k.append("__kernel void sc_decide_multichunk(\n")
                .append("        ").append(OpenCLAstEmitter.NOISE_PARAMS).append(",\n")
                .append("        __global const double* corners,\n")   // K*M*cornerN multichunk corner buffer (ALWAYS fp64; decision math is `real`)
                .append("        __global const int* origins,\n")      // K*3 block origins (same table as corners)
                .append("        const int M, const int cornerN,\n")
                .append("        const int dimX, const int dimZ,\n")
                .append("        const int sx, const int sy, const int sz,\n")
                .append("        const int fullX, const int fullY, const int fullZ,\n")
                .append("        const int fullN, const int total,\n")
                .append("        __global const int* auxInts,\n")      // K*SC_AUX_INTS
                .append("        __global const long* auxLongs,\n")    // K*2: oreSeedLo, oreSeedHi
                .append("        __global const long* locCache,\n")    // K*cellN
                .append("        __global const int* fluidLevel,\n")   // K*cellN
                .append("        __global const int* fluidTypeId,\n")  // K*cellN
                .append("        const int cellN,\n")
                .append("        const double flowingUpdateSimilarity,\n")
                .append("        __global uchar* outIds) {\n")
                // Z-RUN decomposition: one work-item per sz-block z-cell run.
                // fullZ = (dimZ-1)*sz, so runs are always exact z-cells: every block
                // in a run shares (bx, by, cx, cy, cz, ddx, ddy) and all 8 corners of
                // every grid slot — the shared X/Y interpolation prefixes are computed
                // once per run and only the final z-lerp runs per block (bit-exact;
                // see sc_dec_lerp3_prefix, decide.cl). The host dispatches total/sz
                // work-items (GpuBatchDispatcher.enqueueDecide).
                .append("    int gid = get_global_id(0);\n")
                .append("    int totalRuns = total / sz;\n")
                .append("    if (gid >= totalRuns) return;\n")
                .append("    int runsPerChunk = fullN / sz;\n")
                .append("    int c = gid / runsPerChunk;\n")
                .append("    __global const int* aux = auxInts + c * SC_AUX_INTS;\n")
                .append("    int rin = gid - c * runsPerChunk;\n")
                .append("    int rzn = fullZ / sz;\n")
                .append("    int planeR = fullX * rzn;\n")
                .append("    int by = rin / planeR;\n")
                .append("    int rem = rin - by * planeR;\n")
                .append("    int bx = rem / rzn;\n")
                .append("    int rz = rem - bx * rzn;\n")
                .append("    int bz0 = rz * sz;\n")
                .append("    int outBase = c * fullN + (by * fullX + bx) * fullZ + bz0;\n")
                .append("    if (aux[0] == 0) {\n")
                .append("        for (int l = 0; l < sz; ++l) outIds[outBase + l] = SC_DEC_ABSENT;\n")
                .append("        return;\n")
                .append("    }\n")
                .append("    int x = origins[c * 3 + 0] + bx;\n")
                .append("    int y = origins[c * 3 + 1] + by;\n")
                .append("    int z0 = origins[c * 3 + 2] + bz0;\n")
                // Cell decode + in-cell deltas (identical to fullfield.cl); cz == rz
                // because runs are cell-aligned.
                .append("    int cx = bx / sx; int lx = bx - cx * sx;\n")
                .append("    int cy = by / sy; int ly = by - cy * sy;\n")
                .append("    int cz = rz;\n")
                .append("    real ddx = (real) lx / (real) sx;\n")
                .append("    real ddy = (real) ly / (real) sy;\n")
                .append("    int gbase = c * M * cornerN;\n")
                .append("    ScDecG _g;\n");
        // Per-run shared X/Y prefixes for every tail-referenced grid (the per-block
        // remainder is a single mc_lerp(ddz, p0, p1) — mc_lerp3's own structure).
        for (Map.Entry<Integer, Integer> e : slotOfRoot.entrySet()) {
            int slot = e.getValue();
            k.append("    real t0_").append(slot).append(", t1_").append(slot).append(";\n")
                    .append("    sc_dec_lerp3_prefix(corners, gbase + ").append(e.getKey())
                    .append(" * cornerN, cx, cy, cz, dimX, dimZ, ddx, ddy, &t0_").append(slot)
                    .append(", &t1_").append(slot).append(");\n");
        }
        if (veins) {
            // Vein prefixes are computed LAZILY on the run's first aquifer-null
            // in-band block (pure functions — eager/lazy value-identical). The y-band
            // pre-gate [-60,50] is the union of both vein types' bands and y is
            // run-constant, so it hoists out of the loop.
            k.append("    int veinBand = (aux[16] != 0 && y >= -60 && y <= 50) ? 1 : 0;\n")
                    .append("    int veinPrefixDone = 0;\n");
            for (Map.Entry<Integer, Integer> e : veinSlotOfRoot.entrySet()) {
                int slot = e.getValue();
                k.append("    real v0_").append(slot).append(" = REAL_C(0.0), v1_")
                        .append(slot).append(" = REAL_C(0.0);\n");
            }
        }
        k.append("    for (int lz = 0; lz < sz; ++lz) {\n")
                .append("        int z = z0 + lz;\n")
                // Beard AABB: CPU decides these (the density here would need the CPU-only
                // per-block beard contribution) -> sentinel byte.
                .append("        if (aux[9] != 0\n")
                .append("                && x >= aux[10] && x <= aux[13]\n")
                .append("                && y >= aux[11] && y <= aux[14]\n")
                .append("                && z >= aux[12] && z <= aux[15]) {\n")
                .append("            outIds[outBase + lz] = SC_DEC_BEARD;\n")
                .append("            continue;\n")
                .append("        }\n")
                .append("        real ddz = (real) lz / (real) sz;\n");
        for (Map.Entry<Integer, Integer> e : slotOfRoot.entrySet()) {
            int slot = e.getValue();
            k.append("        _g.v[").append(slot).append("] = mc_lerp(ddz, t0_").append(slot)
                    .append(", t1_").append(slot).append(");\n");
        }
        k.append("        real density = ").append(emitted.tailFn())
                .append("(x, y, z, ").append(OpenCLAstEmitter.NOISE_ARGS).append(", _g);\n")
                .append("        int id = 0;\n")
                .append("        int sched = 0;\n")
                // vanilla computeSubstance: density>0 -> early null (stone), sched=false;
                // density<=0 -> HIGH-AIR fast path when y >= aux[17] (aq_airpath_similarity
                // — provably byte-identical incl. bit7, barrier never consumed at that
                // height; proof in aquifer.cl), else the REAL aq_decide (aquifer.cl,
                // Stage-3 census code — bit-exact proven incl. the sched bit), fed this
                // chunk's uploaded aux slice; barrier computed IN-KERNEL from the emitted
                // router barrierNoise AST (only on the full path — the fast path's whole
                // point is skipping it). All aq-null paths return sched=0 (matches every
                // vanilla null return).
                .append("        if (!(density > REAL_C(0.0))) {\n")
                .append("            if (y >= aux[17]) {\n")
                .append("                double dsim = aq_airpath_similarity(x, y, z, locCache + c * cellN,\n")
                .append("                        aux[1], aux[2], aux[3], aux[4], aux[5]);\n")
                .append("                id = AQ_AIR;\n")
                .append("                sched = (dsim <= 0.0) ? ((dsim >= flowingUpdateSimilarity) ? 1 : 0) : 1;\n")
                .append("            } else {\n")
                .append("                double barrier = (double) ").append(emitted.barrierFn())
                .append("(x, y, z, ").append(OpenCLAstEmitter.NOISE_ARGS).append(", _g);\n")
                .append("                double m; int dg1; int flg1; int ixg1; long pg1;\n")
                .append("                id = aq_decide(x, y, z, (double) density, barrier,\n")
                .append("                        locCache + c * cellN, fluidLevel + c * cellN, fluidTypeId + c * cellN,\n")
                .append("                        aux[1], aux[2], aux[3], aux[4], aux[5],\n")
                .append("                        aux[6], aux[7], aux[8],\n")
                .append("                        flowingUpdateSimilarity,\n")
                .append("                        &m, &sched, &dg1, &flg1, &ixg1, &pg1);\n")
                .append("            }\n")
                .append("        }\n");
        if (veins) {
            // MaterialRuleList: EVERY aquifer-null block falls through to the vein
            // filler. Vein grids are interpolated in VALUE order (prefix + final
            // z-lerp — the per-block interpolator order the CPU vein DF reads see);
            // the vein tail functions (abs/max/add, the gap noise) are the emitted
            // REAL ASTs; the decision + Xoroshiro draws are the SHARED census-proven
            // aq_vein_decide.
            k.append("        if (id == 0 && veinBand != 0) {\n")
                    .append("            if (veinPrefixDone == 0) {\n");
            for (Map.Entry<Integer, Integer> e : veinSlotOfRoot.entrySet()) {
                int slot = e.getValue();
                k.append("                sc_dec_value_prefix(corners, gbase + ").append(e.getKey())
                        .append(" * cornerN, cx, cy, cz, dimX, dimZ, ddx, ddy, &v0_").append(slot)
                        .append(", &v1_").append(slot).append(");\n");
            }
            k.append("                veinPrefixDone = 1;\n")
                    .append("            }\n");
            for (Map.Entry<Integer, Integer> e : veinSlotOfRoot.entrySet()) {
                int slot = e.getValue();
                k.append("            _g.v[").append(slot).append("] = mc_lerp(ddz, v0_").append(slot)
                        .append(", v1_").append(slot).append(");\n");
            }
            // LAZY-STAGED vein evaluation (aq_vein_stage1/2/3, aquifer.cl): vt gates
            // everything; vrid is evaluated only when stage 1 survives the band/
            // veininess/skip gates (~10% of in-band blocks); vgap — the one REAL
            // noise eval in the trio — only when stage 2's d3 draw passes (~1-3%).
            // This mirrors vanilla's own lazy `&&` order exactly (see aquifer.cl);
            // the RNG stream is drawn in identical order across the stages.
            k.append("            double vt = (double) ").append(emitted.veinToggleFn())
                    .append("(x, y, z, ").append(OpenCLAstEmitter.NOISE_ARGS).append(", _g);\n")
                    .append("            xoro_t vrs; int vcopper; double vd1;\n")
                    .append("            int vr = aq_vein_stage1(x, y, z, vt,\n")
                    .append("                    auxLongs[c * 2 + 0], auxLongs[c * 2 + 1], &vrs, &vcopper, &vd1);\n")
                    .append("            if (vr == AQ_VEIN_CONT) {\n")
                    .append("                double vrid = (double) ").append(emitted.veinRidgedFn())
                    .append("(x, y, z, ").append(OpenCLAstEmitter.NOISE_ARGS).append(", _g);\n")
                    .append("                vr = aq_vein_stage2(vrid, vd1, vcopper, &vrs);\n")
                    .append("                if (vr == AQ_VEIN_CONT) {\n")
                    .append("                    double vgap = (double) ").append(emitted.veinGapFn())
                    .append("(x, y, z, ").append(OpenCLAstEmitter.NOISE_ARGS).append(", _g);\n")
                    .append("                    vr = aq_vein_stage3(vgap, vcopper, &vrs);\n")
                    .append("                }\n")
                    .append("                id = vr;\n")
                    .append("            }\n")
                    .append("        }\n");
        }
        k.append("        outIds[outBase + lz] = (uchar)(id | (sched != 0 ? 0x80 : 0x00));\n")
                .append("    }\n")
                .append("}\n");
        return k.toString();
    }

    private static String load(String resource) {
        try (InputStream in = CompactIds.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // =====================================================================
    // Stats ([compact-ids] log pattern).
    // =====================================================================

    /** Actual precision the decide program built with ("fp32"/"fp64"; "unbuilt" before
     *  initDecide) — the config asks for fp32 by default but the dispatcher may fall back. */
    private static volatile String decideFp = "unbuilt";

    public static void noteDecideFp(String fp) {
        decideFp = fp;
    }

    public static String decideFp() {
        return decideFp;
    }

    private static final LongAdder auxCaptured = new LongAdder();
    private static final LongAdder auxFailNoAquifer = new LongAdder();
    private static final LongAdder auxFailBlending = new LongAdder();
    private static final LongAdder auxFailNoRoute = new LongAdder();
    private static final LongAdder auxFailVeinSeed = new LongAdder();
    private static final LongAdder auxFailFluidType = new LongAdder();
    private static final LongAdder auxFailOther = new LongAdder();

    /**
     * A chunk's aquifer FluidStatus table contained a fluid state that is not exactly
     * vanilla air/water/lava (datapack custom fluid): the 1-byte id alphabet cannot
     * represent it, so the aux is rejected and the chunk stays corner-only. Called from
     * {@code MixinAquiferSamplerImpl.superchunk$compactAquiferAux}.
     */
    public static void noteAuxFluidTypeRejected() {
        auxFailFluidType.increment();
    }

    private static final LongAdder chunksWithIds = new LongAdder();     // batched chunks whose ids arrived
    private static final LongAdder chunksWithoutIds = new LongAdder();  // batched chunks with NO ids (fallback path)
    private static final LongAdder idBytes = new LongAdder();           // total id bytes read back

    private static final LongAdder decideDispatches = new LongAdder();  // decide NDRanges issued
    private static final LongAdder decideChunks = new LongAdder();      // chunks decided (aux present)
    // Chunks whose aux was present but whose veins-enabled dimension cannot be decided
    // by a veins=false plan (would write plain stone where vanilla places ore veins).
    private static final LongAdder decideSkipVeinless = new LongAdder();
    private static final LongAdder decideKernelNanos = new LongAdder(); // profiled kernel time
    private static final LongAdder decideWallNanos = new LongAdder();   // completer-side ids slice wall
    private static final LongAdder decideFaults = new LongAdder();
    private static volatile String planFailReason = null;

    private static final AtomicLong nextLogAt = new AtomicLong(512);

    static void notePlanFail(String reason) {
        planFailReason = reason;
        LOG.warn("[compact-ids] decide plan unavailable: {}.", reason);
    }

    /** A veins-enabled chunk was NOT decided because the plan resolved veins=false. */
    public static void noteDecideSkipVeinless() {
        decideSkipVeinless.increment();
    }

    /** Records a decide fault (sticky-disabled by the dispatcher). */
    public static void noteDecideFault(Throwable t) {
        decideFaults.increment();
        LOG.warn("[compact-ids] decide chain FAULT — disabled for this dispatcher; batched chunks continue corner-only.", t);
    }

    /** One decide NDRange over a batch: chunks decided, kernel ns (profiled; -1 unknown), drainer wall ns. */
    public static void recordDecide(int presentChunks, long kernelNanos, long wallNanos) {
        decideDispatches.increment();
        decideChunks.add(presentChunks);
        if (kernelNanos > 0) {
            decideKernelNanos.add(kernelNanos);
        }
        if (wallNanos > 0) {
            decideWallNanos.add(wallNanos);
        }
    }

    /** One batched chunk deposited into the store: did its ids arrive? */
    public static void recordDeposit(byte[] ids) {
        if (ids != null) {
            chunksWithIds.increment();
            idBytes.add(ids.length);
        } else {
            chunksWithoutIds.increment();
        }
        long total = chunksWithIds.sum() + chunksWithoutIds.sum();
        long at = nextLogAt.get();
        if (total >= at && nextLogAt.compareAndSet(at, at * 2)) {
            report("progress");
        }
    }

    /** Boot-time status line (called from SuperChunk.initGpuBackend). */
    public static void logBoot() {
        if (!"off".equalsIgnoreCase(MODE) && !PROBE) {
            LOG.warn("[compact-ids] unknown -Dsuperchunk.gpu.compactIds value '{}' — treating as off (valid: off|probe|verify|on).", MODE);
        }
        if (PROBE) {
            LOG.info("[compact-ids] decide chain ON (mode={}): batched chunks get a chained decide dispatch "
                    + "(interp+tail+aquifer+veins -> 1 byte/block ids) read back into GpuBatchStore. Requires fp64 + "
                    + "batchChunks; non-batched chunks and dimensions without a NoiseBasedAquifer stay corner-only. {}",
                    MODE.toLowerCase(),
                    CONSUME ? "CONSUME: the fill seam consumes the ids instead of the per-block decision machinery "
                                    + "(beard-sentinel cells CPU-decided; any inconsistency -> original loop)."
                            : VERIFY ? "VERIFY: the ORIGINAL loop ships; the consume path runs as a shadow and every "
                                    + "side effect is compared ([compact-consume-verify] must report 0 divergence)."
                            : "PROBE: ids are read back and DISCARDED by the consumer (plumbing-tax probe).");
        }
    }

    /** Final/periodic [compact-ids] report. */
    public static void report(String reason) {
        if (!PROBE) {
            return;
        }
        long with = chunksWithIds.sum();
        long without = chunksWithoutIds.sum();
        long disp = decideDispatches.sum();
        long dChunks = decideChunks.sum();
        long kernelNanos = decideKernelNanos.sum();
        // "n/a" = queues created without CL_QUEUE_PROFILING_ENABLE (default in CONSUME
        // mode; -Dsuperchunk.gpu.decideProfile=true re-enables) — not a zero measurement.
        String kernelMsPerChunk = dChunks == 0 || kernelNanos == 0
                ? "n/a" : String.format("%.4f", kernelNanos / 1.0e6 / dChunks);
        double wallMsPerDispatch = disp == 0 ? 0.0 : decideWallNanos.sum() / 1.0e6 / disp;
        LOG.info("[compact-ids] ({}) chunks with ids={} / without={} | id bytes read back={} ({} KB) | "
                        + "decide dispatches={} (chunks decided={}, skipped veinless-plan={}), decide kernel mean={} ms/chunk, "
                        + "ids slice mean={} ms/dispatch | aux: captured={} failures: noAquifer={} blending={} "
                        + "noRoute={} veinSeed={} fluidType={} other={} | decide faults={}{}",
                reason, with, without, idBytes.sum(), idBytes.sum() / 1024,
                disp, dChunks, decideSkipVeinless.sum(),
                kernelMsPerChunk, String.format("%.3f", wallMsPerDispatch),
                auxCaptured.sum(), auxFailNoAquifer.sum(), auxFailBlending.sum(),
                auxFailNoRoute.sum(), auxFailVeinSeed.sum(), auxFailFluidType.sum(), auxFailOther.sum(),
                decideFaults.sum(),
                planFailReason == null ? "" : " | plan: " + planFailReason);
    }

    /** Drops per-world state (routes reference a torn-down world's GPU forms). Counters persist. */
    public static void reset() {
        ROUTES.clear();
        ROUTE_BY_BARRIER.clear();
    }
}
