package dev.superchunk.worldgen;

import com.google.common.collect.MapMaker;
import net.minecraft.core.QuartPos;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Biome fill computes each climate parameter once per quart column instead of once per quart cell,
 * for the parameters that do not depend on y — what Noisium's biome-fill mixin was after, without
 * changing a single biome.
 *
 * <p>{@code ChunkAccess.fillBiomesFromNoise} asks {@code Climate.Sampler.sample} for every quart cell
 * of the chunk, 1,536 of them in a 384-block world, and each sample computes all six climate density
 * functions. In vanilla's overworld only depth reads y: temperature and vegetation are
 * {@code shifted_noise} with {@code y_scale} 0, continents, erosion and ridges are
 * {@code flat_cache}d 2D noise. So five of the six are the same for all 96 cells of a column, and the
 * two uncached noises (temperature, vegetation) were most of the sampler's cost.
 *
 * <p>Which functions qualify is decided once per noise router from its vanilla density functions
 * ({@link #decide}; DFC calls it before compiling, then {@link #register}s the compiled router),
 * with the same conservative rules as the aquifer's {@code AquiferCellSharing}: any function type
 * this class does not know, a mod's own types included, counts as reading y. Only the samplers
 * {@code NoiseChunk.cachedClimateSampler} makes for biome fill are tagged, and only while
 * {@code fillBiomesFromNoise} runs on this thread ({@link #arm}). The lookups themselves still happen
 * one per cell in vanilla's order, so the R-tree's warm start (which breaks exact ties) and every
 * biome are unchanged; each returned target is the one vanilla computes, value for value.
 *
 * <p>Stands down when another mod hooks {@code Climate.Sampler.sample} or
 * {@code NoiseChunk.cachedClimateSampler}. Kill switch {@code -Dsuperchunk.worldgen.climateColumns=false};
 * verify mode ({@code -Dsuperchunk.worldgen.climateColumns.verify=true}) also computes every target the
 * vanilla way and counts differences.
 */
public final class ClimateColumns {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.climateColumns", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.climateColumns.verify");

    /** Mask bits, in {@code Climate.Sampler} order. */
    public static final int TEMPERATURE = 1, HUMIDITY = 2, CONTINENTALNESS = 4, EROSION = 8, DEPTH = 16, WEIRDNESS = 32;

    /** Implemented on {@code Climate.Sampler}: which of its functions are reused per column (0 = none). */
    public interface Tagged {
        byte superchunk$columnMask();

        void superchunk$setColumnMask(byte mask);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-ClimateColumns");
    // Weak keys compare by identity: one entry per live router, compiled or vanilla.
    private static final Map<NoiseRouter, Byte> MASKS = new MapMaker().weakKeys().makeMap();
    private static final ThreadLocal<Columns> ARMED = ThreadLocal.withInitial(Columns::new);
    private static final AtomicBoolean LOGGED = new AtomicBoolean();
    private static final LongAdder VERIFIED = new LongAdder();
    private static final LongAdder MISMATCHES = new LongAdder();
    private static volatile byte hooks; // ForeignHooks state

    // DensityFunctions.EndIslandDensityFunction is not accessible; it reads (x / 8, z / 8) only.
    private static final Class<?> END_ISLANDS = DensityFunctions.endIslands(0L).getClass();

    private ClimateColumns() {
    }

    /** The column mask for a vanilla (uncompiled) router. */
    public static byte decide(NoiseRouter router) {
        Walk walk = new Walk();
        int mask = 0;
        if (walk.yIndependent(router.temperature())) mask |= TEMPERATURE;
        if (walk.yIndependent(router.vegetation())) mask |= HUMIDITY;
        if (walk.yIndependent(router.continents())) mask |= CONTINENTALNESS;
        if (walk.yIndependent(router.erosion())) mask |= EROSION;
        if (walk.yIndependent(router.depth())) mask |= DEPTH;
        if (walk.yIndependent(router.ridges())) mask |= WEIRDNESS;
        if (mask != 0 && ENABLED && LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[climate-columns] biome fill reuses per quart column: {}", describe(mask));
        }
        return (byte) mask;
    }

    /** Records the mask decided for {@code router} (DFC: the compiled router, decided from its vanilla form). */
    public static void register(NoiseRouter router, byte mask) {
        MASKS.put(router, mask);
    }

    /** The mask for the router a climate sampler is built from; 0 when disabled or hooked. */
    public static byte maskFor(NoiseRouter router) {
        if (!ENABLED || !unhooked()) {
            return 0;
        }
        Byte mask = MASKS.get(router);
        if (mask == null) {
            mask = decide(router); // no DFC: the RandomState's router is the vanilla one
            MASKS.put(router, mask);
        }
        return mask;
    }

    private static boolean unhooked() {
        byte state = hooks;
        if (state == ForeignHooks.UNKNOWN) {
            state = ForeignHooks.state("Per-column climate reuse (Climate.Sampler.sample)",
                    "net.minecraft.world.level.biome.Climate$Sampler#sample",
                    "net.minecraft.world.level.levelgen.NoiseChunk#cachedClimateSampler");
            if (state != ForeignHooks.UNKNOWN) {
                hooks = state;
            }
        }
        return state == ForeignHooks.CLEAR;
    }

    /** Starts column reuse for {@code sampler} on this thread; true if armed (then {@link #disarm} after). */
    public static boolean arm(Climate.Sampler sampler) {
        if (((Tagged) (Object) sampler).superchunk$columnMask() == 0) {
            return false;
        }
        Columns columns = ARMED.get();
        if (columns.sampler != null) {
            return false; // nested fill: leave the outer one armed
        }
        columns.sampler = sampler;
        java.util.Arrays.fill(columns.valid, false);
        return true;
    }

    public static void disarm() {
        ARMED.get().sampler = null;
    }

    /**
     * The target for quart (x, y, z), reusing this column's y-independent values; null when this
     * thread is not filling biomes with {@code sampler}.
     */
    public static Climate.TargetPoint sample(Climate.Sampler sampler, int mask, int x, int y, int z) {
        Columns c = ARMED.get();
        if (c.sampler != sampler) {
            return null;
        }
        DensityFunction.SinglePointContext ctx =
                new DensityFunction.SinglePointContext(QuartPos.toBlock(x), QuartPos.toBlock(y), QuartPos.toBlock(z));
        int slot = ((x & 3) << 2) | (z & 3);
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        boolean hit = c.valid[slot] && c.keys[slot] == key;
        float[] v = c.values;
        int base = slot * 6;
        // Vanilla's order; a reused value is the one this column computed at its first cell.
        float temperature = hit && (mask & TEMPERATURE) != 0 ? v[base] : (float) sampler.temperature().compute(ctx);
        float humidity = hit && (mask & HUMIDITY) != 0 ? v[base + 1] : (float) sampler.humidity().compute(ctx);
        float continentalness = hit && (mask & CONTINENTALNESS) != 0 ? v[base + 2] : (float) sampler.continentalness().compute(ctx);
        float erosion = hit && (mask & EROSION) != 0 ? v[base + 3] : (float) sampler.erosion().compute(ctx);
        float depth = hit && (mask & DEPTH) != 0 ? v[base + 4] : (float) sampler.depth().compute(ctx);
        float weirdness = hit && (mask & WEIRDNESS) != 0 ? v[base + 5] : (float) sampler.weirdness().compute(ctx);
        if (!hit) {
            v[base] = temperature;
            v[base + 1] = humidity;
            v[base + 2] = continentalness;
            v[base + 3] = erosion;
            v[base + 4] = depth;
            v[base + 5] = weirdness;
            c.keys[slot] = key;
            c.valid[slot] = true;
        }
        Climate.TargetPoint target = Climate.target(temperature, humidity, continentalness, erosion, depth, weirdness);
        if (VERIFY) {
            Climate.TargetPoint vanilla = Climate.target((float) sampler.temperature().compute(ctx),
                    (float) sampler.humidity().compute(ctx), (float) sampler.continentalness().compute(ctx),
                    (float) sampler.erosion().compute(ctx), (float) sampler.depth().compute(ctx),
                    (float) sampler.weirdness().compute(ctx));
            VERIFIED.increment();
            if (!vanilla.equals(target)) {
                MISMATCHES.increment();
            }
        }
        return target;
    }

    public static void reportVerify() {
        long m = MISMATCHES.sum();
        LOGGER.info("[climate-columns] VERIFY: checked={} MISMATCHES={} -> {}", VERIFIED.sum(), m, m == 0 ? "PASS" : "FAIL");
    }

    private static String describe(int mask) {
        StringBuilder b = new StringBuilder();
        String[] names = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};
        for (int i = 0; i < names.length; i++) {
            if ((mask & (1 << i)) != 0) {
                b.append(b.length() == 0 ? "" : ", ").append(names[i]);
            }
        }
        return b.toString();
    }

    /** One thread's 4x4 quart columns of the chunk being filled. */
    private static final class Columns {
        Climate.Sampler sampler;
        final long[] keys = new long[16];
        final boolean[] valid = new boolean[16];
        final float[] values = new float[16 * 6];
    }

    /** Whether a vanilla density function's value can depend on y (see the class comment). */
    private static final class Walk {
        private final Map<DensityFunction, Boolean> known = new IdentityHashMap<>();

        boolean yIndependent(DensityFunction df) {
            Boolean k = this.known.get(df);
            if (k == null) {
                this.known.put(df, Boolean.FALSE); // a cycle reads as y-dependent
                k = this.yIndependentNew(df);
                this.known.put(df, k);
            }
            return k;
        }

        private boolean yIndependentNew(DensityFunction df) {
            if (df instanceof DensityFunctions.Noise noise && noise.yScale() != 0.0) {
                return false;
            }
            if (df instanceof DensityFunctions.ShiftedNoise noise && noise.yScale() != 0.0) {
                return false;
            }
            // Blending's density blend reads y; the beardifier is the chunk's own structures.
            if (df instanceof DensityFunctions.Shift || df instanceof DensityFunctions.YClampedGradient
                    || df instanceof DensityFunctions.WeirdScaledSampler || df instanceof BlendedNoise
                    || df instanceof DensityFunctions.BeardifierOrMarker || df instanceof DensityFunctions.BlendDensity) {
                return false;
            }
            List<DensityFunction> children = children(df);
            if (children == null) {
                return false;
            }
            for (DensityFunction child : children) {
                if (!this.yIndependent(child)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * What {@code df} evaluates at the same point, or null for a type not known here. Leaves read
         * only the point: noises (ShiftA/ShiftB at y = 0), constants, the end islands (x / 8, z / 8)
         * and the blend alpha/offset markers (per column). Cache markers read through to their input:
         * over a y-independent input every NoiseChunk cache returns the same value at every y.
         */
        private static List<DensityFunction> children(DensityFunction df) {
            if (df instanceof DensityFunctions.Constant || df instanceof DensityFunctions.Noise
                    || df instanceof DensityFunctions.ShiftA || df instanceof DensityFunctions.ShiftB
                    || df instanceof DensityFunctions.BlendAlpha || df instanceof DensityFunctions.BlendOffset
                    || df.getClass() == END_ISLANDS) {
                return List.of();
            }
            if (df instanceof DensityFunctions.Marker f) {
                return List.of(f.wrapped());
            }
            if (df instanceof DensityFunctions.HolderHolder f) {
                return List.of(f.function().value());
            }
            if (df instanceof DensityFunctions.ShiftedNoise f) {
                return List.of(f.shiftX(), f.shiftY(), f.shiftZ());
            }
            if (df instanceof DensityFunctions.TwoArgumentSimpleFunction f) {
                return List.of(f.argument1(), f.argument2());
            }
            if (df instanceof DensityFunctions.PureTransformer f) {
                return List.of(f.input());
            }
            if (df instanceof DensityFunctions.RangeChoice f) {
                return List.of(f.input(), f.whenInRange(), f.whenOutOfRange());
            }
            if (df instanceof DensityFunctions.Spline f) {
                List<DensityFunction> coordinates = new ArrayList<>();
                splineCoordinates(f.spline(), coordinates);
                return coordinates;
            }
            return null;
        }

        private static void splineCoordinates(CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline,
                                              List<DensityFunction> out) {
            if (spline instanceof CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> multipoint) {
                out.add(multipoint.coordinate().function().value());
                for (CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> value : multipoint.values()) {
                    splineCoordinates(value, out);
                }
            }
        }
    }
}
