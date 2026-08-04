package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.jigsaw;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk jigsaw-placement octree lever (default ON; disable with
 * {@code -Dsuperchunk.worldgen.jigsawOctree=false}). Replaces the heavy per-candidate
 * {@code VoxelShape} intersection test in {@code JigsawPlacement.Placer.tryPlacingChildren}
 * with the {@link JigsawFreeShape} boundary+octree model. The two {@code MixinJigsawPlacement*}
 * redirects delegate here so all the flag / verify / fallback logic lives in one place.
 *
 * <p>Flags (mirroring the project's other worldgen levers):
 * <ul>
 *   <li>{@code -Dsuperchunk.worldgen.jigsawOctree} — use the octree fast path. By default this runs
 *       silently: just the octree test, with no per-test counters or logging.</li>
 *   <li>{@code -Dsuperchunk.worldgen.jigsawOctree.verify} — the diagnostic/test mode (default OFF):
 *       run BOTH the octree and the real VoxelShape path, assert they agree, and (crucially) RETURN
 *       THE VANILLA RESULT so output stays bit-identical while we prove correctness. This is also
 *       what enables the {@code TESTS}/{@code STUCK}/{@code BUGS} counters and the periodic
 *       {@code [jigsaw-octree]} log line — none of which run in the default fast path.</li>
 * </ul>
 *
 * <p>The free regions are keyed by {@code VoxelShape} instance identity in a per-thread map.
 * Jigsaw assembly for one structure runs synchronously on a single worker thread (the
 * {@code addPieces} Placer loop), so a thread-local map is correctly isolated per structure; it is
 * reset when a new structure seeds its top-level free region. Any region we did not seed (an
 * unrecognised free shape) transparently falls back to the real VoxelShape op, so the worst case is
 * "optimization silently not applied", never a wrong result.
 */
public final class JigsawShapeTracker {

    private static final Logger LOGGER = LogManager.getLogger("SuperChunk-Jigsaw");

    // Default ON (parity-safe; BUGS=0 proven across multiple seeds incl. nether bastions, 2026-06-29).
    // Disable with -Dsuperchunk.worldgen.jigsawOctree=false.
    public static final boolean OPTIMIZE = Boolean.parseBoolean(System.getProperty("superchunk.worldgen.jigsawOctree", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.jigsawOctree.verify");
    /** When false, every redirect is a straight pass-through to vanilla (zero behaviour change). */
    public static final boolean ACTIVE = OPTIMIZE || VERIFY;

    // Skip-blocked-jigsaw early-out (default OFF; enable with -Dsuperchunk.worldgen.jigsawSkipBlocked=true).
    // Learned from TelepathicGrunt's Structure Layout Optimizer: when a RIGID candidate's connector
    // block is already outside the placement boundary or inside a placed piece, that candidate
    // provably fails the free-space test, so its jigsaw-block enumeration is skipped. This is an
    // ADD-ON to the octree — it reads the same boundary+box model — and is inert when the octree is
    // off (region unseeded). Because the vanilla RNG-consuming shuffle has already run by the time
    // this fires (it modifies the shuffle's RETURN value), the random stream is untouched.
    public static final boolean SKIP_BLOCKED = Boolean.parseBoolean(System.getProperty("superchunk.worldgen.jigsawSkipBlocked", "false"));
    public static final boolean SKIP_BLOCKED_VERIFY = Boolean.getBoolean("superchunk.worldgen.jigsawSkipBlocked.verify");
    private static final boolean SKIP_BLOCKED_ACTIVE = SKIP_BLOCKED || SKIP_BLOCKED_VERIFY;

    private static final ThreadLocal<IdentityHashMap<VoxelShape, JigsawFreeShape>> MAP =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private static final AtomicLong TESTS = new AtomicLong();
    private static final AtomicLong STUCK = new AtomicLong();   // candidate rejected (sticks out)
    private static final AtomicLong BUGS = new AtomicLong();    // verify-mode disagreements
    private static final long LOG_EVERY = 50_000L;

    // Skip-blocked verify state: ARMED_SKIP marks that the current candidate was deemed skippable;
    // testFree then asserts such a candidate never actually places. Jigsaw assembly is single-threaded
    // per structure, so a thread-local is correctly isolated.
    private static final ThreadLocal<Boolean> ARMED_SKIP = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicLong SKIP_TESTS = new AtomicLong();   // rigid candidates examined
    private static final AtomicLong SKIP_WOULD = new AtomicLong();   // ...that we'd have skipped
    private static final AtomicLong SKIP_BUGS = new AtomicLong();    // verify: skipped-but-would-place

    private JigsawShapeTracker() {
    }

    /**
     * Seed the top-level free region for a new structure and reset the per-thread map (start of
     * structure). The top-level free passed into {@code addPieces} is
     * {@code Shapes.join(create(bounds), create(startBox), ONLY_FIRST)} == placement bounds minus
     * the start piece, so {@code free.bounds()} recovers the placement bounds (the start box is a
     * small interior cut that does not move the outer bounds) and {@code startBox} is the negative.
     */
    public static void seedTopLevel(VoxelShape free, AABB startBox) {
        if (!ACTIVE) return;
        JigsawFreeShape region = new JigsawFreeShape(free.bounds());
        region.addBox(startBox);
        IdentityHashMap<VoxelShape, JigsawFreeShape> map = MAP.get();
        map.clear();
        map.put(free, region);
    }

    /** Seed a piece-local free region: {@code free = Shapes.create(box)} == the piece's bounding box. */
    public static VoxelShape initLocal(AABB box) {
        VoxelShape real = Shapes.create(box);
        if (ACTIVE) {
            MAP.get().put(real, new JigsawFreeShape(box));
        }
        return real;
    }

    /** Redirect of {@code Shapes.joinIsNotEmpty(free, candidate, ONLY_SECOND)} (the collision test). */
    public static boolean testFree(VoxelShape free, VoxelShape candidate, BooleanOp op) {
        if (!ACTIVE) {
            return Shapes.joinIsNotEmpty(free, candidate, op);
        }
        JigsawFreeShape region = MAP.get().get(free);
        if (region == null) {
            return Shapes.joinIsNotEmpty(free, candidate, op); // unseeded region -> vanilla
        }
        boolean fast = region.candidateSticksOut(candidate.bounds());
        // Skip-blocked verify cross-check: if a candidate we armed as "skippable" (connector blocked)
        // actually fits here (would place), the skip verdict was wrong. Only runs under
        // -Dsuperchunk.worldgen.jigsawSkipBlocked.verify; the static-final guard is JIT-eliminated otherwise.
        if (SKIP_BLOCKED_VERIFY && ARMED_SKIP.get() && !fast) {
            long n = SKIP_BUGS.incrementAndGet();
            if (n <= 50) {
                AABB c = candidate.bounds();
                LOGGER.warn("[jigsaw-skipblocked] BUG #{}: candidate would place but its connector was deemed "
                        + "blocked cand=[{},{},{} -> {},{},{}]", n, c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ);
            }
        }
        if (!VERIFY) {
            // Default fast path: just the octree test. No test counting, no logging — the
            // TESTS/STUCK counters and the [jigsaw-octree] log line are diagnostics that only run
            // under -Dsuperchunk.worldgen.jigsawOctree.verify (they add contended-atomic overhead
            // and log noise on every jigsaw collision test otherwise).
            return fast;
        }
        // Verify mode: also run the real VoxelShape op, count, assert agreement, log — and RETURN
        // the vanilla result so output stays bit-identical while we prove the octree.
        TESTS.incrementAndGet();
        boolean real = Shapes.joinIsNotEmpty(free, candidate, op);
        if (real != fast) {
            long n = BUGS.incrementAndGet();
            if (n <= 50) {
                AABB c = candidate.bounds();
                LOGGER.warn("[jigsaw-octree] BUG #{}: octree={} vanilla={} cand=[{},{},{} -> {},{},{}]",
                        n, fast, real, c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ);
            }
        }
        if (real) STUCK.incrementAndGet();
        maybeLog();
        return real;
    }

    /** Redirect of {@code Shapes.joinUnoptimized(free, childBox, ONLY_FIRST)} (subtract a placed piece). */
    public static VoxelShape subtractFree(VoxelShape free, VoxelShape childBox, BooleanOp op) {
        if (!ACTIVE) {
            return Shapes.joinUnoptimized(free, childBox, op);
        }
        JigsawFreeShape region = MAP.get().get(free);
        if (region == null) {
            return Shapes.joinUnoptimized(free, childBox, op); // unseeded region -> vanilla
        }
        region.addBox(childBox.bounds());
        if (VERIFY) {
            VoxelShape grown = Shapes.joinUnoptimized(free, childBox, op);
            MAP.get().put(grown, region); // re-key the model onto the grown real shape
            return grown;
        }
        return free; // keep the same identity token; the octree holds the placed boxes
    }

    /**
     * Skip-blocked early-out ({@code -Dsuperchunk.worldgen.jigsawSkipBlocked}). Called via
     * {@code @ModifyExpressionValue} on the CANDIDATE's {@code getShuffledJigsawBlocks} (ordinal 1)
     * inside {@code tryPlacingChildren}: for a RIGID candidate whose connector position is already
     * outside the boundary or inside a placed piece, return an empty jigsaw-block list so the inner
     * attach/free-space loop does nothing — the candidate could not have been placed there anyway.
     *
     * <p>Parity: strictly one-directional. We only ever suppress candidates the full free-space test
     * would also have rejected (a rigid piece contains its own connector block). The RNG stream is
     * unaffected — the vanilla shuffle already ran (this modifies its return value). Inert unless the
     * octree seeded a region for {@code free} (i.e. requires {@code jigsawOctree}, the default).
     *
     * <p>Verify mode ({@code .verify}) never actually skips (returns {@code original}) and instead
     * arms {@link #testFree} to prove no armed candidate ever places.
     */
    public static List<StructureTemplate.StructureBlockInfo> maybeSkipBlockedCandidate(
            List<StructureTemplate.StructureBlockInfo> original,
            VoxelShape free,
            StructurePoolElement candidate,
            BlockPos connectorPos) {
        if (!SKIP_BLOCKED_ACTIVE || free == null) {
            return original;
        }
        JigsawFreeShape region = MAP.get().get(free);
        if (region == null) {                                   // octree off / unseeded -> inert
            if (SKIP_BLOCKED_VERIFY) ARMED_SKIP.set(Boolean.FALSE);
            return original;
        }
        if (candidate.getProjection() != StructureTemplatePool.Projection.RIGID) {
            if (SKIP_BLOCKED_VERIFY) ARMED_SKIP.set(Boolean.FALSE);  // non-rigid pieces can shift; never skip
            return original;
        }
        boolean blocked = region.connectorBlocks(connectorPos.getX(), connectorPos.getY(), connectorPos.getZ());
        if (SKIP_BLOCKED_VERIFY) {
            // Diagnostic: do NOT skip (output stays vanilla); arm the testFree cross-check instead.
            ARMED_SKIP.set(blocked);
            SKIP_TESTS.incrementAndGet();
            if (blocked) SKIP_WOULD.incrementAndGet();
            maybeLogSkip();
            return original;
        }
        return blocked ? List.of() : original;
    }

    private static void maybeLog() {
        long t = TESTS.get();
        if (t == 1L || t % LOG_EVERY == 0L) {
            LOGGER.info("[jigsaw-octree] {}: tests={} rejected={} bugs={}",
                    VERIFY ? "verify" : "running", t, STUCK.get(), BUGS.get());
        }
    }

    private static void maybeLogSkip() {
        long t = SKIP_TESTS.get();
        if (t == 1L || t % LOG_EVERY == 0L) {
            LOGGER.info("[jigsaw-skipblocked] verify: rigidCandidates={} wouldSkip={} bugs={}",
                    t, SKIP_WOULD.get(), SKIP_BUGS.get());
        }
    }
}
