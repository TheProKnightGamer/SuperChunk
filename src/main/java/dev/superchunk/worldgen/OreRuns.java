package dev.superchunk.worldgen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exact shortcuts in ore placement, used by {@code MixinOreFeatureFast}. Each one keeps vanilla's
 * blocks, random draws and order; it only skips work whose answer is already known.
 *
 * <ul>
 * <li>{@link #HEIGHT_SCAN}: {@code OreFeature.place} asks {@code level.getHeight(OCEAN_FLOOR_WG)} for
 *     every column of the ore's box until one reaches the box's bottom: up to 81 columns for each of the
 *     90 upper iron ores a chunk tries, most of them in the air. {@link #reaches} finds each chunk once,
 *     the first time vanilla would, reads its heightmap directly, and skips a chunk whose highest column is
 *     below the box.</li>
 * <li>{@link #RUNS}: {@code doPlace} tests every block of each sphere's bounding box against the
 *     sphere and checks a bit set for blocks an earlier sphere already took. Along one row the test
 *     holds on one interval of blocks ({@link #run}), so the row's ends are found directly, and the
 *     bit set is laid out with that row's blocks next to each other, so the ones already taken are
 *     skipped a word at a time.</li>
 * <li>{@link #TARGET_MEMO}: when every target is a plain block or tag test, the tests' answer for a
 *     block state is remembered for the rest of the call.</li>
 * </ul>
 *
 * Kill switches {@code -Dsuperchunk.worldgen.oreHeightScan=false}, {@code .oreRuns=false},
 * {@code .oreTargetMemo=false}; {@code -Dsuperchunk.worldgen.oreVerify=true} also runs vanilla's loops
 * next to them and compares.
 */
public final class OreRuns {

    public static final int HEIGHT_SCAN = 1;
    public static final int RUNS = 2;
    public static final int TARGET_MEMO = 4;

    public static final int FAST_DEFAULT = (on("superchunk.worldgen.oreHeightScan") ? HEIGHT_SCAN : 0)
            | (on("superchunk.worldgen.oreRuns") ? RUNS : 0)
            | (on("superchunk.worldgen.oreTargetMemo") ? TARGET_MEMO : 0);
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.oreVerify");

    private static final AtomicLong SCANS = new AtomicLong();
    private static final AtomicLong SCAN_MISMATCHES = new AtomicLong();
    private static final AtomicLong PLACES = new AtomicLong();
    private static final AtomicLong PLACE_MISMATCHES = new AtomicLong();
    private static final AtomicLong BLOCKS = new AtomicLong();
    private static final AtomicLong MEMO_CHECKS = new AtomicLong();
    private static final AtomicLong MEMO_MISMATCHES = new AtomicLong();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SuperChunk-Ores");

    private OreRuns() {
    }

    private static boolean on(String property) {
        return !"false".equalsIgnoreCase(System.getProperty(property, "true"));
    }

    /** The shortcuts in effect for a call in this level (the A/B timer turns some off on half the chunks). */
    public static int fast(WorldGenLevel level) {
        return OreTiming.AB_MASK != 0 && OreTiming.half(level) == 1 ? FAST_DEFAULT & ~OreTiming.AB_MASK : FAST_DEFAULT;
    }

    // ---- the column scan of OreFeature.place ----

    /**
     * Vanilla's {@code for x in [x0, x0 + span], z in [z0, z0 + span]: if (minY <= level.getHeight(OCEAN_FLOOR_WG, x, z))}.
     * {@code WorldGenRegion.getHeight} is {@code getChunk(x >> 4, z >> 4).getHeight(type, x & 15, z & 15) + 1}; each chunk is
     * looked up once, at the column vanilla would first look it up at, so a chunk the region cannot give fails at the same
     * column. Which column answers does not change the result. In a plain {@code ProtoChunk} whose heightmap is primed,
     * {@code getHeight} is that heightmap's {@code getFirstAvailable - 1}, so the heightmap is found once per chunk, and a
     * chunk whose highest column ({@link #highest}) is below {@code minY} is not read at all.
     */
    public static boolean reaches(WorldGenRegion region, int minY, int x0, int z0, int span) {
        if (span < 0 || span > 64) {
            return reachesVanilla(region, minY, x0, z0, span);
        }
        boolean direct = directHeights();
        int cx0 = x0 >> 4;
        int cz0 = z0 >> 4;
        int ncz = ((z0 + span) >> 4) - cz0 + 1;
        int slots = (((x0 + span) >> 4) - cx0 + 1) * ncz;
        ChunkAccess[] chunks = new ChunkAccess[slots];
        Heightmap[] heightmaps = new Heightmap[slots];
        // per chunk: 0 not looked at yet, READ_HEIGHTMAP, NONE_REACH (its highest column is below minY), CALL_GET_HEIGHT
        byte[] how = new byte[slots];
        for (int x = x0; x <= x0 + span; x++) {
            int row = ((x >> 4) - cx0) * ncz;
            int z = z0;
            while (z <= z0 + span) {
                int zEnd = Math.min(z0 + span, z | 15);
                int slot = row + ((z >> 4) - cz0);
                ChunkAccess chunk = chunks[slot];
                if (chunk == null) {
                    chunk = region.getChunk(x >> 4, z >> 4);
                    chunks[slot] = chunk;
                }
                byte way = how[slot];
                if (way == 0) {
                    way = CALL_GET_HEIGHT;
                    if (direct && chunk.getClass() == ProtoChunk.class && chunk.hasPrimedHeightmap(Heightmap.Types.OCEAN_FLOOR_WG)) {
                        Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
                        heightmaps[slot] = heightmap;
                        way = highest(heightmap) < minY ? NONE_REACH : READ_HEIGHTMAP;
                    }
                    how[slot] = way;
                }
                if (way == NONE_REACH) {
                    z = zEnd + 1;
                } else if (way == READ_HEIGHTMAP) {
                    Heightmap heightmap = heightmaps[slot];
                    for (; z <= zEnd; z++) {
                        if (minY <= heightmap.getFirstAvailable(x & 15, z & 15)) {
                            return true;
                        }
                    }
                } else {
                    for (; z <= zEnd; z++) {
                        if (minY <= chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15) + 1) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static final byte READ_HEIGHTMAP = 1;
    private static final byte NONE_REACH = 2;
    private static final byte CALL_GET_HEIGHT = 3;

    /** Implemented on {@code Heightmap} by {@code MixinHeightmapTop}. */
    public interface TopHolder {
        Top superchunk$top();

        void superchunk$setTop(Top top);
    }

    /** A heightmap's highest {@code getFirstAvailable}, and a copy of the raw words it was read from. */
    public record Top(long[] raw, int highest) {
    }

    /**
     * The highest {@code getFirstAvailable} of the heightmap's 256 columns. It is kept on the heightmap with a copy of the
     * raw words it was read from, and used again only while the heightmap's words still equal that copy (36 longs for a
     * 384-block world), so a heightmap written since is read again. {@code Integer.MAX_VALUE} (no shortcut) when the
     * words changed while they were read.
     */
    static int highest(Heightmap heightmap) {
        long[] raw = heightmap.getRawData();
        TopHolder holder = (TopHolder) heightmap;
        Top top = holder.superchunk$top();
        if (top != null && java.util.Arrays.equals(top.raw(), raw)) {
            return top.highest();
        }
        long[] copy = raw.clone();
        int highest = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                highest = Math.max(highest, heightmap.getFirstAvailable(x, z));
            }
        }
        if (!java.util.Arrays.equals(copy, raw)) {
            return Integer.MAX_VALUE;
        }
        holder.superchunk$setTop(new Top(copy, highest));
        return highest;
    }

    private static byte heightHooks;

    /** Whether {@code ProtoChunk.getHeight} may be answered from its heightmap ({@link ForeignHooks}). */
    private static boolean directHeights() {
        byte state = heightHooks;
        if (state == ForeignHooks.UNKNOWN) {
            state = ForeignHooks.state("The ore height check's heightmap reads (ChunkAccess.getHeight)",
                    "net.minecraft.world.level.chunk.ChunkAccess#getHeight",
                    "net.minecraft.world.level.chunk.ChunkAccess#hasPrimedHeightmap",
                    "net.minecraft.world.level.chunk.ChunkAccess#getOrCreateHeightmapUnprimed",
                    "net.minecraft.world.level.chunk.ProtoChunk#getHeight",
                    "net.minecraft.world.level.levelgen.Heightmap#getFirstAvailable",
                    "net.minecraft.world.level.levelgen.Heightmap#getRawData");
            heightHooks = state;
        }
        return state == ForeignHooks.CLEAR;
    }

    private static boolean reachesVanilla(WorldGenLevel level, int minY, int x0, int z0, int span) {
        for (int x = x0; x <= x0 + span; x++) {
            for (int z = z0; z <= z0 + span; z++) {
                if (minY <= level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void verifyReaches(WorldGenLevel level, int minY, int x0, int z0, int span, boolean answer) {
        SCANS.incrementAndGet();
        if (reachesVanilla(level, minY, x0, z0, span) != answer && SCAN_MISMATCHES.incrementAndGet() <= 20) {
            LOGGER.warn("[ore-runs] VERIFY MISMATCH height scan at {},{},{} span {}: {}", x0, minY, z0, span, answer);
        }
    }

    // ---- the sphere loops of OreFeature.doPlace ----

    /**
     * vanilla's sphere test for block {@code k} of a row: {@code s + d * d < 1.0} with {@code d = ((double) k + 0.5 - c) / r},
     * where {@code s} is the part the row's other coordinates add.
     */
    static boolean inside(double s, int k, double c, double r) {
        double d = ((double) k + 0.5 - c) / r;
        return s + d * d < 1.0;
    }

    /**
     * {@link #inside}, with the division replaced by a multiplication by {@code invR = 1 / r} wherever that cannot
     * change the answer: the two left sides differ by a few units in the last place (under 1e-14 for these
     * magnitudes), so outside a 1e-9 band around 1 the product decides, and inside it the division does.
     */
    static boolean insideFast(double s, int k, double c, double r, double invR) {
        double g = (double) k + 0.5 - c;
        double a = g * invR;
        double v = s + a * a;
        if (v < 1.0 - 1.0E-9) {
            return true;
        }
        if (v > 1.0 + 1.0E-9) {
            return false;
        }
        double d = g / r;
        return s + d * d < 1.0;
    }

    /**
     * The {@code k} in {@code [min, max]} that pass {@link #inside} as {@code lo << 32 | hi} ({@code lo > hi} when none),
     * for {@code 0 <= s < 1}, {@code r > 0} and {@code invR = 1 / r}.
     *
     * <p>The test passes on one interval of {@code k}: {@code k + 0.5 - c} rounds monotonically in {@code k}, and the
     * division by {@code r}, the square of its magnitude and the sum with {@code s} each round monotonically, so the left
     * side is a function that falls and then rises. The interval's ends are estimated in closed form and confirmed with
     * the test itself: both ends pass and the blocks past them fail. When the estimate is empty, the integer below it lies
     * left of the centre and the one above it right of it, so if both fail every block further out fails too. When the
     * check does not hold (rounding right at an end), the row is tested block by block as vanilla does.
     */
    public static long run(double s, double c, double r, double invR, int min, int max) {
        double w = r * Math.sqrt(1.0 - s);
        double mid = c - 0.5;
        int lo = Mth.ceil(mid - w);
        int hi = Mth.floor(mid + w);
        boolean known = lo <= hi
                ? insideFast(s, lo, c, r, invR) && insideFast(s, hi, c, r, invR)
                        && !insideFast(s, lo - 1, c, r, invR) && !insideFast(s, hi + 1, c, r, invR)
                : !insideFast(s, hi, c, r, invR) && !insideFast(s, lo, c, r, invR);
        if (!known) {
            lo = max + 1;
            hi = max;
            for (int k = min; k <= max; k++) {
                if (inside(s, k, c, r)) {
                    if (lo > max) {
                        lo = k;
                    }
                    hi = k;
                }
            }
        }
        return pack(Math.max(lo, min), Math.min(hi, max));
    }

    static long pack(int lo, int hi) {
        return (long) lo << 32 | (hi & 0xFFFFFFFFL);
    }

    public static int lo(long run) {
        return (int) (run >> 32);
    }

    public static int hi(long run) {
        return (int) run;
    }

    /**
     * Whether every block {@code doPlace}'s spheres can visit lies inside its box
     * {@code [x, x + width) x [y, y + height) x [z, z + width)}: vanilla's bit set index is unique there, so any other
     * one-to-one index gives the same "already taken" answers. Vanilla's own {@code place} always fits (its margin of
     * {@code ceil(size / 8) + ceil(size / 16 + 0.5)} exceeds a centre's offset plus its radius); other callers may not.
     * Spheres with a coordinate or radius that is not finite (or a radius of 0) take vanilla's loop.
     */
    public static boolean spheresFit(double[] spheres, int count, int x, int y, int z, int width, int height) {
        if (width <= 0 || height <= 0 || width > 1024 || height > 1024 || (long) width * width * height > 1 << 26) {
            return false;
        }
        for (int n = 0; n < count; n++) {
            double r = spheres[n * 4 + 3];
            if (!(r < 0.0)) {
                double cx = spheres[n * 4];
                double cy = spheres[n * 4 + 1];
                double cz = spheres[n * 4 + 2];
                if (!(r > 0.0 && r < 1.0E6 && Math.abs(cx) < 1.0E8 && Math.abs(cy) < 1.0E8 && Math.abs(cz) < 1.0E8)) {
                    return false;
                }
                // the loops' last block on each axis, as vanilla bounds them
                if (Math.max(Mth.floor(cx + r), Math.max(Mth.floor(cx - r), x)) >= x + width
                        || Math.max(Mth.floor(cy + r), Math.max(Mth.floor(cy - r), y)) >= y + height
                        || Math.max(Mth.floor(cz + r), Math.max(Mth.floor(cz - r), z)) >= z + width) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final ThreadLocal<long[]> TAKEN = ThreadLocal.withInitial(() -> new long[256]);

    /**
     * The thread's bits for the blocks a {@code doPlace} call has taken, at least {@code bits} of them, cleared. Like the
     * pooled {@code BitSet} vanilla's loop uses, one per thread: {@code doPlace} does not re-enter itself.
     */
    public static long[] taken(int bits) {
        int words = (bits + 63) >>> 6;
        long[] taken = TAKEN.get();
        if (taken.length < words) {
            taken = new long[words];
            TAKEN.set(taken);
        } else {
            java.util.Arrays.fill(taken, 0, words, 0L);
        }
        return taken;
    }

    /** {@code (x, y, z)} of each block in the order vanilla's loops would hand them on, for {@link #verifyPlace}. */
    static IntArrayList vanillaBlocks(double[] spheres, int count, int x, int y, int z, int width, int height,
                                      int minBuild, int maxBuildExcl) {
        IntArrayList out = new IntArrayList();
        BitSet bitset = new BitSet(width * height * width);
        for (int j4 = 0; j4 < count; j4++) {
            double d9 = spheres[j4 * 4 + 3];
            if (!(d9 < 0.0)) {
                double d11 = spheres[j4 * 4 + 0];
                double d13 = spheres[j4 * 4 + 1];
                double d15 = spheres[j4 * 4 + 2];
                int k4 = Math.max(Mth.floor(d11 - d9), x);
                int l = Math.max(Mth.floor(d13 - d9), y);
                int i1 = Math.max(Mth.floor(d15 - d9), z);
                int j1 = Math.max(Mth.floor(d11 + d9), k4);
                int k1 = Math.max(Mth.floor(d13 + d9), l);
                int l1 = Math.max(Mth.floor(d15 + d9), i1);
                for (int i2 = k4; i2 <= j1; i2++) {
                    double d5 = ((double) i2 + 0.5 - d11) / d9;
                    if (d5 * d5 < 1.0) {
                        for (int j2 = l; j2 <= k1; j2++) {
                            double d6 = ((double) j2 + 0.5 - d13) / d9;
                            if (d5 * d5 + d6 * d6 < 1.0) {
                                for (int k2 = i1; k2 <= l1; k2++) {
                                    double d7 = ((double) k2 + 0.5 - d15) / d9;
                                    if (d5 * d5 + d6 * d6 + d7 * d7 < 1.0 && j2 >= minBuild && j2 < maxBuildExcl) {
                                        int l2 = i2 - x + (j2 - y) * width + (k2 - z) * width * height;
                                        if (!bitset.get(l2)) {
                                            bitset.set(l2);
                                            out.add(i2);
                                            out.add(j2);
                                            out.add(k2);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** {@code blocks}: the {@code (x, y, z)} the fast loops handed on, in order. */
    public static void verifyPlace(double[] spheres, int count, int x, int y, int z, int width, int height,
                                   int minBuild, int maxBuildExcl, IntArrayList blocks) {
        PLACES.incrementAndGet();
        BLOCKS.addAndGet(blocks.size() / 3);
        IntArrayList expected = vanillaBlocks(spheres, count, x, y, z, width, height, minBuild, maxBuildExcl);
        if (!expected.equals(blocks) && PLACE_MISMATCHES.incrementAndGet() <= 20) {
            int at = 0;
            while (at < Math.min(expected.size(), blocks.size()) && expected.getInt(at) == blocks.getInt(at)) {
                at++;
            }
            LOGGER.warn("[ore-runs] VERIFY MISMATCH ore at {},{},{} ({} spheres): {} blocks, vanilla {}; first difference at block {}",
                    x, y, z, count, blocks.size() / 3, expected.size() / 3, at / 3);
        }
    }

    public static void verifyMemo(int memoMask, int freshMask) {
        MEMO_CHECKS.incrementAndGet();
        if (memoMask != freshMask && MEMO_MISMATCHES.incrementAndGet() <= 20) {
            LOGGER.warn("[ore-runs] VERIFY MISMATCH target memo: {} vs {}", memoMask, freshMask);
        }
    }

    public static void report() {
        if (VERIFY) {
            long m = SCAN_MISMATCHES.get() + PLACE_MISMATCHES.get() + MEMO_MISMATCHES.get();
            LOGGER.info("[ore-runs] VERIFY: height scans={} mismatches={} (heightmaps read directly: {}); placements={} blocks={} "
                            + "mismatches={}; target memo blocks={} mismatches={} -> {}",
                    SCANS.get(), SCAN_MISMATCHES.get(), directHeights(), PLACES.get(), BLOCKS.get(), PLACE_MISMATCHES.get(),
                    MEMO_CHECKS.get(), MEMO_MISMATCHES.get(), m == 0 ? "PASS" : "FAIL");
        }
    }
}
