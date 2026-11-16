package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.structure_weight_sampler;

import com.google.common.collect.Iterators;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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

    // SuperChunk beard reach: one exact influence box per term (pieces in order, then junctions),
    // {minX, minY, minZ, maxX, maxY, maxZ} inclusive. Outside its box a term is exactly 0.0 (see
    // superchunk$computeReach), and the sum is never -0.0, so leaving the term out changes nothing.
    // compute() visits only the terms whose box meets the block's 4x4x4 bucket, in the original
    // order, and the cell sign skip asks whether any box meets a cell.
    // Kill switch -Dsuperchunk.worldgen.beardReach=false; verify -Dsuperchunk.worldgen.beardReach.verify=true
    // sums every term as well and compares the bits.
    @Unique private static final boolean SC_BEARD_REACH = Boolean.parseBoolean(System.getProperty("superchunk.worldgen.beardReach", "true"));
    @Unique private static final boolean SC_BEARD_REACH_VERIFY = Boolean.getBoolean("superchunk.worldgen.beardReach.verify");
    @Unique private static final AtomicLong scReachChecked = new AtomicLong();
    @Unique private static final AtomicLong scReachBugs = new AtomicLong();
    @Unique private static final AtomicLong scReachNextLog = new AtomicLong(2_000_000L);
    @Unique private int[] superchunk$reach;
    // Per term, for the sign of its value: Integer.MAX_VALUE = never negative (the bury kernels),
    // Integer.MIN_VALUE = unknown, anything else = the base height o of a beard kernel term, whose
    // value is <= 0 at and above o and >= 0 below it (see superchunk$beardSigns).
    @Unique private int[] superchunk$signBase;
    @Unique private int[] superchunk$near;      // term indices meeting the current bucket, ascending
    @Unique private int superchunk$nearCount;
    @Unique private boolean superchunk$bucketKnown;
    @Unique private int superchunk$bucketX, superchunk$bucketY, superchunk$bucketZ;

    /**
     * The influence boxes. Each bound is where the term's own code returns 0.0:
     * BURY {@code getBuryContribution(m, p, n)} (which halves p) is 0 for m or n over 6 (its early
     * return) and for |p| over 12 (distance at least 6.5); BEARD_THIN and junctions use
     * {@code getBeardContribution}, 0 unless each offset is in [-12, 11], and m, n are never
     * negative; BEARD_BOX the same with its clamped height offset in [0, 11]. A terrain adjustment
     * this code does not know gets an unbounded box and is always visited.
     */
    @Unique
    private void superchunk$computeReach() {
        final Beardifier.Rigid[] pieces = this.c2me$pieceArray;
        final JigsawJunction[] junctions = this.c2me$junctionArray;
        final int[] r = new int[(pieces.length + junctions.length) * 6];
        final int[] base = new int[pieces.length + junctions.length];
        int w = 0;
        for (Beardifier.Rigid piece : pieces) {
            final BoundingBox b = piece.box();
            final int o = b.minY() + piece.groundLevelDelta();
            int minX, minY, minZ, maxX, maxY, maxZ;
            switch (piece.terrainAdjustment()) {
                case NONE -> {
                    minX = minY = minZ = 1;
                    maxX = maxY = maxZ = 0; // empty
                }
                case BURY -> {
                    minX = b.minX() - 6; maxX = b.maxX() + 6;
                    minZ = b.minZ() - 6; maxZ = b.maxZ() + 6;
                    minY = o - 12; maxY = o + 12;
                }
                case BEARD_THIN -> {
                    minX = b.minX() - 11; maxX = b.maxX() + 11;
                    minZ = b.minZ() - 11; maxZ = b.maxZ() + 11;
                    minY = o - 12; maxY = o + 11;
                }
                case BEARD_BOX -> {
                    minX = b.minX() - 11; maxX = b.maxX() + 11;
                    minZ = b.minZ() - 11; maxZ = b.maxZ() + 11;
                    minY = o - 11; maxY = b.maxY() + 11;
                }
                default -> {
                    minX = minY = minZ = Integer.MIN_VALUE;
                    maxX = maxY = maxZ = Integer.MAX_VALUE;
                }
            }
            base[w / 6] = switch (piece.terrainAdjustment()) {
                case BEARD_THIN, BEARD_BOX -> o;
                case NONE, BURY -> Integer.MAX_VALUE;
                default -> Integer.MIN_VALUE;
            };
            r[w++] = minX; r[w++] = minY; r[w++] = minZ;
            r[w++] = maxX; r[w++] = maxY; r[w++] = maxZ;
        }
        for (JigsawJunction jj : junctions) {
            base[w / 6] = jj.getSourceGroundY();
            r[w++] = jj.getSourceX() - 12; r[w++] = jj.getSourceGroundY() - 12; r[w++] = jj.getSourceZ() - 12;
            r[w++] = jj.getSourceX() + 11; r[w++] = jj.getSourceGroundY() + 11; r[w++] = jj.getSourceZ() + 11;
        }
        this.superchunk$signBase = base;
        this.superchunk$near = new int[pieces.length + junctions.length];
        this.superchunk$reach = r;
    }

    /**
     * Which way the beardifier can move the density anywhere in the block box (inclusive bounds):
     * 0 when every term is exactly 0.0 there, else {@code BEARD_MAY_RAISE} and/or
     * {@code BEARD_MAY_LOWER}. The bury kernels return {@code 1 - d / 6} for d up to 6 and 0.0
     * beyond, never negative. A beard kernel term is
     * {@code -(height + 0.5) * fastInvSqrt(..) / 2 * BEARD_KERNEL[..]} times 0.8 or 0.4, with a
     * positive inverse square root and positive kernel entries ({@code e^-x}), or 0.0 out of the
     * kernel's range: never positive for height >= 0 (at and above the base), never negative for
     * height <= -1.
     */
    @Override
    public int superchunk$beardSigns(int x0, int y0, int z0, int x1, int y1, int z1) {
        if (this.c2me$pieceArray == null || this.c2me$junctionArray == null) {
            this.c2me$initArrays();
        }
        if (!this.c2me$boundsComputed) {
            this.c2me$computeBeardBounds();
        }
        if (!this.c2me$hasBeard || x1 < this.c2me$bMinX || x0 > this.c2me$bMaxX || y1 < this.c2me$bMinY
                || y0 > this.c2me$bMaxY || z1 < this.c2me$bMinZ || z0 > this.c2me$bMaxZ) {
            return 0;
        }
        if (!SC_BEARD_REACH || (dev.superchunk.diag.StageAb.BEARD_REACH && dev.superchunk.diag.StageAb.off())) {
            return BEARD_MAY_RAISE | BEARD_MAY_LOWER;
        }
        int[] r = this.superchunk$reach;
        if (r == null) {
            superchunk$computeReach();
            r = this.superchunk$reach;
        }
        final int[] base = this.superchunk$signBase;
        int signs = 0;
        for (int t = 0, at = 0; at < r.length; t++, at += 6) {
            if (x1 >= r[at] && x0 <= r[at + 3] && y1 >= r[at + 1] && y0 <= r[at + 4]
                    && z1 >= r[at + 2] && z0 <= r[at + 5]) {
                final int o = base[t];
                if (o == Integer.MAX_VALUE) {
                    signs |= BEARD_MAY_RAISE;
                } else if (o == Integer.MIN_VALUE) {
                    return BEARD_MAY_RAISE | BEARD_MAY_LOWER;
                } else if (y0 >= o) {
                    signs |= BEARD_MAY_LOWER;
                } else if (y1 < o) {
                    signs |= BEARD_MAY_RAISE;
                } else {
                    return BEARD_MAY_RAISE | BEARD_MAY_LOWER;
                }
            }
        }
        return signs;
    }

    /** The terms whose box meets the 4x4x4 bucket of blocks at (bx, by, bz) << 2. */
    @Unique
    private void superchunk$selectBucket(int bx, int by, int bz) {
        int[] r = this.superchunk$reach;
        if (r == null) {
            superchunk$computeReach();
            r = this.superchunk$reach;
        }
        final int x0 = bx << 2, y0 = by << 2, z0 = bz << 2;
        final int x1 = x0 + 3, y1 = y0 + 3, z1 = z0 + 3;
        final int[] near = this.superchunk$near;
        int n = 0;
        for (int t = 0, at = 0; at < r.length; t++, at += 6) {
            if (x1 >= r[at] && x0 <= r[at + 3] && y1 >= r[at + 1] && y0 <= r[at + 4]
                    && z1 >= r[at + 2] && z0 <= r[at + 5]) {
                near[n++] = t;
            }
        }
        this.superchunk$nearCount = n;
        this.superchunk$bucketX = bx;
        this.superchunk$bucketY = by;
        this.superchunk$bucketZ = bz;
        this.superchunk$bucketKnown = true;
    }

    /** {@code compute()}'s sum over the terms that can reach block (i, j, k), in its order. */
    @Unique
    private double superchunk$sumNear(int i, int j, int k) {
        final int bx = i >> 2, by = j >> 2, bz = k >> 2;
        if (!this.superchunk$bucketKnown || bx != this.superchunk$bucketX || by != this.superchunk$bucketY
                || bz != this.superchunk$bucketZ) {
            superchunk$selectBucket(bx, by, bz);
        }
        final int[] near = this.superchunk$near;
        final int[] r = this.superchunk$reach;
        final Beardifier.Rigid[] pieces = this.c2me$pieceArray;
        final int count = this.superchunk$nearCount;
        double d = 0.0;
        for (int c = 0; c < count; c++) {
            final int t = near[c];
            final int at = t * 6;
            if (i < r[at] || i > r[at + 3] || j < r[at + 1] || j > r[at + 4] || k < r[at + 2] || k > r[at + 5]) {
                continue; // exactly 0.0 here
            }
            if (t < pieces.length) {
                d += superchunk$pieceTerm(pieces[t], i, j, k);
            } else {
                final JigsawJunction jigsawJunction = this.c2me$junctionArray[t - pieces.length];
                final int rx = i - jigsawJunction.getSourceX();
                final int l = j - jigsawJunction.getSourceGroundY();
                final int m = k - jigsawJunction.getSourceZ();
                d += getBeardContribution(rx, l, m, l) * 0.4;
            }
        }
        return d;
    }

    /** One piece's term, as {@code compute()} adds it. */
    @Unique
    private static double superchunk$pieceTerm(Beardifier.Rigid piece, int i, int j, int k) {
        BoundingBox blockBox = piece.box();
        int l = piece.groundLevelDelta();
        int m = Math.max(0, Math.max(blockBox.minX() - i, i - blockBox.maxX()));
        int n = Math.max(0, Math.max(blockBox.minZ() - k, k - blockBox.maxZ()));
        int o = blockBox.minY() + l;
        int p = j - o;
        return switch (piece.terrainAdjustment()) { // 2 switch statement merged
            case NONE -> 0.0;
            case BURY -> getBuryContribution(m, p, n);
            case BEARD_THIN -> getBeardContribution(m, p, n, p) * 0.8;
            case BEARD_BOX -> getBeardContribution(m, Math.max(0, Math.max(o - j, j - blockBox.maxY())), n, p) * 0.8;
        };
    }

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

    @Override
    public int[] superchunk$beardTerms(int maxInts) {
        if (this.c2me$pieceArray == null || this.c2me$junctionArray == null) {
            this.c2me$initArrays();
        }
        int pieces = 0;
        for (Beardifier.Rigid piece : this.c2me$pieceArray) {
            if (piece.terrainAdjustment() != TerrainAdjustment.NONE) {
                pieces++;
            }
        }
        long len = 2L + 8L * pieces + 3L * this.c2me$junctionArray.length;
        if (len > maxInts) {
            return null;
        }
        int[] out = new int[(int) len];
        out[0] = pieces;
        out[1] = this.c2me$junctionArray.length;
        int w = 2;
        for (Beardifier.Rigid piece : this.c2me$pieceArray) {
            // FORGE 1.20.1 PORT: no ENCAPSULATE. BURY's kernel term (decide.cl sc_bury_contribution
            // of (m, p / 2.0, n)) is this tree's getBuryContribution(m, p, n): m and n are >= 0, so
            // its |x|, |z| > 6 early-outs and (x*x + (y/2)*(y/2)) + z*z are the same operations.
            int code = switch (piece.terrainAdjustment()) {
                case NONE -> 0;
                case BURY -> 1;
                case BEARD_THIN -> 2;
                case BEARD_BOX -> 3;
                default -> -1;   // an added constant: compute() would not know it either
            };
            if (code == 0) {
                continue;
            }
            if (code < 0) {
                return null;
            }
            BoundingBox b = piece.box();
            out[w++] = b.minX();
            out[w++] = b.minY();
            out[w++] = b.minZ();
            out[w++] = b.maxX();
            out[w++] = b.maxY();
            out[w++] = b.maxZ();
            out[w++] = piece.groundLevelDelta();
            out[w++] = code;
        }
        for (JigsawJunction jj : this.c2me$junctionArray) {
            out[w++] = jj.getSourceX();
            out[w++] = jj.getSourceGroundY();
            out[w++] = jj.getSourceZ();
        }
        return out;
    }

    @Override
    public double[] superchunk$beardTables() {
        double[] t = new double[dev.superchunk.gpu.dfc.ScBeardBoxAccess.BEARD_TABLES_LEN];
        for (int x = -12; x < 12; x++) {
            for (int y = -12; y < 12; y++) {
                for (int z = -12; z < 12; z++) {
                    int idx = ((x + 12) * 24 + (y + 12)) * 24 + (z + 12);
                    double b = getBeardContribution(x, y, z, y);
                    t[idx] = b * 0.4;           // junction: getBeardContribution(r, l, m, l) * 0.4
                    t[13824 + idx] = b * 0.8;   // BEARD_THIN: getBeardContribution(m, p, n, p) * 0.8
                }
            }
        }
        // FORGE 1.20.1 PORT: BURY is getBuryContribution(m, p, n) on raw ints; no ENCAPSULATE (zeros).
        for (int i1 = 0; i1 <= 6; i1++) {
            for (int l1 = -12; l1 <= 12; l1++) {
                for (int j1 = 0; j1 <= 6; j1++) {
                    t[27648 + (i1 * 25 + (l1 + 12)) * 7 + j1] = getBuryContribution(i1, l1, j1);
                }
            }
        }
        return t;
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

    /** Stand down when another mod hooks compute ({@link dev.superchunk.worldgen.ForeignHooks}). */
    @Unique
    private static byte superchunk$hooks;

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("The beardifier replica (Beardifier.compute)",
                    "net.minecraft.world.level.levelgen.Beardifier#compute");
            superchunk$hooks = state;
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    /**
     * C2ME's optimized compute (ishland). Upstream overwrites the method; a wrap leaves vanilla's
     * body in place, so another mod's injection into it still applies and runs when this stands
     * down. Over the overwrite, Qliphoth Awakening's injection (its arenas' terrain) failed to
     * apply and broke world generation. Measured against the overwrite (CPU, r1024, three pairs):
     * -0.6%, -0.8% and +0.3% CPU per chunk.
     */
    @WrapMethod(method = "compute")
    private double superchunk$compute(DensityFunction.FunctionContext pos, Operation<Double> original) {
        if (!superchunk$unhooked()) {
            return original.call(pos);
        }
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

        if (SC_BEARD_REACH && !SC_BEARD_VERIFY && !SC_BEARD_REACH_VERIFY
                && !(dev.superchunk.diag.StageAb.BEARD_REACH && dev.superchunk.diag.StageAb.off())) {
            return superchunk$sumNear(i, j, k);
        }

        double d = 0.0;

        // FORGE 1.20.1 PORT (superchunk$pieceTerm): vanilla 1.20.1's two switches merged. BURY
        // passes RAW ints (the y-halving lives inside 1.20.1's getBuryContribution); ENCAPSULATE
        // does not exist on 1.20.1 (added in 1.21).
        for (Beardifier.Rigid piece : this.c2me$pieceArray) {
            d += superchunk$pieceTerm(piece, i, j, k);
        }

        for (JigsawJunction jigsawJunction : this.c2me$junctionArray) {
            int r = i - jigsawJunction.getSourceX();
            int l = j - jigsawJunction.getSourceGroundY();
            int m = k - jigsawJunction.getSourceZ();
            d += getBeardContribution(r, l, m, l) * 0.4;
        }

        if (SC_BEARD_REACH_VERIFY) {
            double near = superchunk$sumNear(i, j, k);
            long n = scReachChecked.incrementAndGet();
            if (Double.doubleToRawLongBits(near) != Double.doubleToRawLongBits(d) && scReachBugs.incrementAndGet() <= 20) {
                SC_BEARD_LOG.error("[beard-reach] MISMATCH at {} {} {}: near {} full {}", i, j, k, near, d);
            }
            long la = scReachNextLog.get();
            if (n >= la && scReachNextLog.compareAndSet(la, la + 2_000_000L)) {
                SC_BEARD_LOG.info("[beard-reach] checked={} MISMATCHES={}", n, scReachBugs.get());
            }
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
     * <p>FORGE 1.20.1 PORT: 1.20.1's signature is {@code (int,int,int)} taking RAW SIGNED
     * block offsets with the y-halving inside ({@code Mth.length(x, y/2.0, z)} →
     * {@code Mth.clampedMap(d, 0, 6, 1, 0)}); 1.21.x moved to pre-scaled non-negative
     * doubles. Same early-out idea, adapted: |x| or |z| &gt; 6 ⇒ length &gt; 6 ⇒ 0.0
     * without the sqrt. The computed branch is bit-identical to vanilla 1.20.1
     * ({@code clampedMap(d,0,6,1,0)} ≡ {@code 1 - d/6} for d in [0,6], 0 beyond).
     */
    @Overwrite
    protected static double getBuryContribution(int x, int y, int z) {
        if (x > 6 || x < -6 || z > 6 || z < -6) {
            return 0.0;
        }
        // Exact vanilla operand order (Mth.lengthSquared: (x*x + y2*y2) + z*z) so the
        // rounding — and therefore the terrain — is BIT-identical to 1.20.1 vanilla.
        double dx = x;
        double hy = (double) y / 2.0;
        double dz = z;
        double d = Math.sqrt(dx * dx + hy * hy + dz * dz);
        if (d > 6.0) {
            return 0.0;
        } else {
            return 1.0 - d / 6.0;
        }
    }

}
