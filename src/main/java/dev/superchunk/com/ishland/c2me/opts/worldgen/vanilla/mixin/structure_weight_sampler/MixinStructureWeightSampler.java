package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.structure_weight_sampler;

import com.google.common.collect.Iterators;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import java.util.concurrent.atomic.AtomicLong;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Beardifier.class)
public abstract class MixinStructureWeightSampler implements dev.superchunk.gpu.dfc.ScBeardBoxAccess {

    @Shadow @Final protected ObjectListIterator<Beardifier.Rigid> pieceIterator;

    @Shadow @Final protected ObjectListIterator<JigsawJunction> junctionIterator;

    @Shadow
    protected static double getBeardContribution(int x, int y, int z, int height) {
        throw new AbstractMethodError();
    }

    @Unique
    private Beardifier.Rigid[] c2me$pieceArray;

    @Unique
    private JigsawJunction[] c2me$junctionArray;

    // SuperChunk beard-skip (default ON): per-chunk AABB = union of every CONTRIBUTING piece/junction
    // expanded by the kernel's hard cutoff. getBeardContribution/getBuryContribution return EXACTLY 0
    // beyond Euclidean distance 6; margins are conservative ±12 horizontal + ±12 vertical to cover the
    // /2 scalings (ENCAPSULATE halves horiz; BURY/ENCAPSULATE halve vert). A block outside this AABB
    // gets 0 from every term, so compute()==0.0 — bit-identical to vanilla summing zeros. Skipping it
    // avoids the per-block piece+junction loop.
    // Default ON (parity-safe; BUGS=0 proven across multiple seeds + billions of checks, 2026-06-29).
    // Disable with -Dsuperchunk.worldgen.beardSkip=false.
    @Unique private static final boolean SC_BEARD_SKIP = Boolean.parseBoolean(System.getProperty("superchunk.worldgen.beardSkip", "true"));
    @Unique private static final boolean SC_BEARD_VERIFY = Boolean.getBoolean("superchunk.worldgen.beardSkip.verify");
    @Unique private boolean c2me$hasBeard;
    @Unique private int c2me$bMinX, c2me$bMinY, c2me$bMinZ, c2me$bMaxX, c2me$bMaxY, c2me$bMaxZ;
    @Unique private static final AtomicLong scBeardChecked = new AtomicLong();
    @Unique private static final AtomicLong scBeardSkips = new AtomicLong();
    @Unique private static final AtomicLong scBeardBugs = new AtomicLong();   // AABB excluded a nonzero block (MUST be 0)
    @Unique private static final AtomicLong scBeardNextLog = new AtomicLong(2_000_000L);
    @Unique private static final org.slf4j.Logger SC_BEARD_LOG = org.slf4j.LoggerFactory.getLogger("SuperChunk-BeardSkip");

    // STAGE-3 block-id census: it needs the per-chunk beard AABB (beard-sentinel class), so
    // bounds are also computed + published when only the census flag is on. Static-final
    // gated: zero cost when off.
    @Unique private static final boolean SC_BLOCKID_CENSUS = Boolean.getBoolean("superchunk.gpu.blockIdVerify");
    // STAGE-5 compact-ids: the decide-kernel aux capture ships the beard AABB
    // (blocks inside it get the beard-sentinel byte -> CPU decides them).
    @Unique private static final boolean SC_COMPACT_IDS = dev.superchunk.gpu.dfc.CompactIds.PROBE;
    // Census publish is per-FILL-thread (ThreadLocal in BlockIdCensus): with compact-ids
    // the prefetch worker may pre-init the arrays on ANOTHER thread, so the publish must
    // happen at the first compute() on the FILL thread, not inside initArrays.
    @Unique private boolean c2me$censusPublished;
    @Unique private boolean c2me$boundsComputed;

    @Unique
    private void c2me$initArrays() {
        this.c2me$pieceArray = Iterators.toArray(this.pieceIterator, Beardifier.Rigid.class);
        this.pieceIterator.back(Integer.MAX_VALUE);
        this.c2me$junctionArray = Iterators.toArray(this.junctionIterator, JigsawJunction.class);
        this.junctionIterator.back(Integer.MAX_VALUE);
        if (SC_BEARD_SKIP || SC_BEARD_VERIFY || SC_BLOCKID_CENSUS || SC_COMPACT_IDS) c2me$computeBeardBounds();
    }

    /**
     * STAGE-5 compact-ids: the per-chunk beard AABB for the decide-kernel aux, forcing
     * the (cheap) piece/junction array + bounds init if the fill hasn't run yet. Called
     * WORKER-side at the batch prefetch seam, strictly before this chunk's fill uses
     * this beardifier — race-free, and compute() below sees the arrays ready.
     */
    @Override
    public boolean superchunk$beardBox(int[] out6) {
        if (this.c2me$pieceArray == null || this.c2me$junctionArray == null) {
            this.c2me$initArrays();
        }
        if (!this.c2me$boundsComputed) {
            this.c2me$computeBeardBounds();
        }
        if (!this.c2me$hasBeard) {
            return false;
        }
        out6[0] = this.c2me$bMinX;
        out6[1] = this.c2me$bMinY;
        out6[2] = this.c2me$bMinZ;
        out6[3] = this.c2me$bMaxX;
        out6[4] = this.c2me$bMaxY;
        out6[5] = this.c2me$bMaxZ;
        return true;
    }

    @Unique
    private void c2me$computeBeardBounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        // Real kernels: getBuryContribution = sphere length<=6; getBeardContribution = 24^3 kernel
        // cube, nonzero only when each raw arg in [-12,11] (isInKernelRange(arg+12)). So the worst-case
        // reach on any axis is 12 (cube), and bury's sphere (<=6, plus /2 scalings -> <=12) is covered
        // by 12 too. Use a symmetric +-12 superset everywhere (covers [-12,11]).
        final int M = 12;
        for (Beardifier.Rigid piece : this.c2me$pieceArray) {
            if (piece.terrainAdjustment() == TerrainAdjustment.NONE) continue;   // contributes 0
            BoundingBox b = piece.box();
            int o = b.minY() + piece.groundLevelDelta();
            minX = Math.min(minX, b.minX() - M); maxX = Math.max(maxX, b.maxX() + M);
            minZ = Math.min(minZ, b.minZ() - M); maxZ = Math.max(maxZ, b.maxZ() + M);
            minY = Math.min(minY, Math.min(b.minY(), o) - M); maxY = Math.max(maxY, Math.max(b.maxY(), o) + M);
            any = true;
        }
        for (JigsawJunction jj : this.c2me$junctionArray) {
            minX = Math.min(minX, jj.getSourceX() - M); maxX = Math.max(maxX, jj.getSourceX() + M);
            minY = Math.min(minY, jj.getSourceGroundY() - M); maxY = Math.max(maxY, jj.getSourceGroundY() + M);
            minZ = Math.min(minZ, jj.getSourceZ() - M); maxZ = Math.max(maxZ, jj.getSourceZ() + M);
            any = true;
        }
        this.c2me$hasBeard = any;
        this.c2me$bMinX = minX; this.c2me$bMinY = minY; this.c2me$bMinZ = minZ;
        this.c2me$bMaxX = maxX; this.c2me$bMaxY = maxY; this.c2me$bMaxZ = maxZ;
        this.c2me$boundsComputed = true;
    }

    @Unique
    private boolean c2me$outsideBeard(int x, int y, int z) {
        return !this.c2me$hasBeard
            || x < this.c2me$bMinX || x > this.c2me$bMaxX
            || y < this.c2me$bMinY || y > this.c2me$bMaxY
            || z < this.c2me$bMinZ || z > this.c2me$bMaxZ;
    }

    /**
     * @author ishland
     * @reason optimize impl
     */
    @Overwrite
    public double compute(DensityFunction.FunctionContext pos) {
        if (this.c2me$pieceArray == null || this.c2me$junctionArray == null) {
            this.c2me$initArrays();
        }
        if (SC_BLOCKID_CENSUS && !this.c2me$censusPublished) {
            // Publish THIS chunk's beard AABB for the census ON THE FILL THREAD (its box
            // is a ThreadLocal). First per-block beard compute of the chunk's fill runs
            // strictly before any computeSubstance consumption of the same chunk, so the
            // census always sees the CURRENT chunk's box — even when compact-ids
            // pre-initialized the arrays on the prefetch worker.
            this.c2me$censusPublished = true;
            dev.superchunk.gpu.aquifer.BlockIdCensus.noteBeardBounds(this.c2me$hasBeard,
                    this.c2me$bMinX, this.c2me$bMinY, this.c2me$bMinZ,
                    this.c2me$bMaxX, this.c2me$bMaxY, this.c2me$bMaxZ);
        }

        int i = pos.blockX();
        int j = pos.blockY();
        int k = pos.blockZ();

        // fast path: provably outside every piece/junction influence => 0 (bit-identical)
        if (SC_BEARD_SKIP && !SC_BEARD_VERIFY && c2me$outsideBeard(i, j, k)) {
            return 0.0;
        }

        double d = 0.0;

        for (Beardifier.Rigid piece : this.c2me$pieceArray) {
            BoundingBox blockBox = piece.box();
            int l = piece.groundLevelDelta();
            int m = Math.max(0, Math.max(blockBox.minX() - i, i - blockBox.maxX()));
            int n = Math.max(0, Math.max(blockBox.minZ() - k, k - blockBox.maxZ()));
            int o = blockBox.minY() + l;
            int p = j - o;

            d += switch (piece.terrainAdjustment()) { // 2 switch statement merged
                case NONE -> 0.0;
                case BURY -> getBuryContribution(m, (double)p / 2.0, n);
                case BEARD_THIN -> getBeardContribution(m, p, n, p) * 0.8;
                case BEARD_BOX -> getBeardContribution(m, Math.max(0, Math.max(o - j, j - blockBox.maxY())), n, p) * 0.8;
                case ENCAPSULATE -> getBuryContribution((double)m / 2.0, (double)Math.max(0, Math.max(blockBox.minY() - j, j - blockBox.maxY())) / 2.0, (double)n / 2.0) * 0.8;
            };
        }

        for (JigsawJunction jigsawJunction : this.c2me$junctionArray) {
            int r = i - jigsawJunction.getSourceX();
            int l = j - jigsawJunction.getSourceGroundY();
            int m = k - jigsawJunction.getSourceZ();
            d += getBeardContribution(r, l, m, l) * 0.4;
        }

        // verify: prove the AABB never excludes a nonzero block (BUGS must be 0)
        if (SC_BEARD_VERIFY) {
            long n = scBeardChecked.incrementAndGet();
            if (c2me$outsideBeard(i, j, k)) {
                scBeardSkips.incrementAndGet();
                if (d != 0.0) scBeardBugs.incrementAndGet();
            }
            long la = scBeardNextLog.get();
            if (n >= la && scBeardNextLog.compareAndSet(la, la + 2_000_000L)) {
                SC_BEARD_LOG.info("[beard-verify] checked={} wouldSkip={} ({}%) BUGS(nonzero-excluded)={}",
                        n, scBeardSkips.get(), String.format("%.1f", 100.0 * scBeardSkips.get() / n), scBeardBugs.get());
            }
        }

        return d;
    }

    /**
     * @author ishland
     * @reason optimize impl
     *
     * <p>Declared {@code protected} to match the target's (NeoForge-widened) visibility —
     * a {@code private} overwrite of a protected method logs a "Static binding violation"
     * warning on every worldgen thread's first classload.
     */
    @Overwrite
    protected static double getBuryContribution(double x, double y, double z) {
        // SuperChunk: provably-zero early-out before the sqrt. Callers pass non-negative,
        // already-scaled horizontal distances (BURY: x=m, z=n; ENCAPSULATE: x=m/2, z=n/2),
        // so x,z >= 0. If either exceeds 6 then x*x+y*y+z*z > 36 => d > 6 => the result is
        // 0.0 — identical to the computed branch below, just without the sqrt. (y is left to
        // the sqrt since it can be negative and is rarely the limiting axis.)
        if (x > 6.0 || z > 6.0) {
            return 0.0;
        }
        double d = Math.sqrt(x * x + y * y + z * z);
        if (d > 6.0) {
            return 0.0;
        } else {
            return 1.0 - d / 6.0;
        }
    }

}
