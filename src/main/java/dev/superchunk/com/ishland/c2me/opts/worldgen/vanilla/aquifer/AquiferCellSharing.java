package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer;

import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whether a dimension's noise router lets chunks share aquifer {@code computeFluid} results
 * ({@link ScAquiferCellCache}), decided once per {@code RandomState} from its vanilla router.
 *
 * <p>{@code computeFluid} for a cell runs the router's functions through the asking chunk's
 * {@code NoiseChunk}, and two of its wrappers make a single-point read depend on that chunk:
 * {@code flat_cache} answers from the chunk's own quart grid (the value at the quart corner, y = 0)
 * when the point falls in it, and computes at the point otherwise; {@code cache_2d} returns the
 * chunk's last value for the same (x, z) whatever the y. The beardifier is the chunk's own
 * structures. Everything else a router is built from is a pure function of the point. Per function,
 * by where {@code computeFluid} reads it:
 * <ul>
 * <li>erosion, depth, fluid_level_floodedness — at the cell's jittered position. A
 * {@code flat_cache} is fine: whether that position is in the chunk's grid is part of the cache key
 * ({@link ScAquiferCellCache#packCell}).</li>
 * <li>initial_density_without_jaggedness — down quart-aligned columns around the cell
 * ({@code preliminarySurfaceLevel}), in or out of the grid depending on the chunk. A
 * {@code flat_cache} is fine only over a y-independent function: then the grid's y = 0 value is
 * the value at every y.</li>
 * <li>fluid_level_spread, lava — at scaled-down positions: no {@code flat_cache}.</li>
 * </ul>
 * and in all of them {@code cache_2d} only over y-independent functions, no beardifier, and no
 * function type this class does not know (a mod's own type could hold anything). Vanilla's
 * overworld passes; a failing router keeps the per-chunk path, which is vanilla's.
 */
public final class AquiferCellSharing {

    private enum Site { CELL, COLUMNS, OTHER }

    // DensityFunctions.EndIslandDensityFunction is not accessible; it reads (x / 8, z / 8) only.
    private static final Class<?> END_ISLANDS = DensityFunctions.endIslands(0L).getClass();

    private AquiferCellSharing() {
    }

    private static final java.util.concurrent.atomic.AtomicBoolean SELF_TESTED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final String LEVELGEN = "net.minecraft.world.level.levelgen.";
    private static volatile byte hooks; // dev.superchunk.worldgen.ForeignHooks states

    /**
     * True when no other mod hooks the code whose results the cache stands in for:
     * {@code computeFluid} and what it calls, and the surface scan. Cached once the classes are
     * scanned; while one is not loaded yet the answer is false and asked again later.
     */
    public static boolean unhooked() {
        byte s = hooks;
        if (s == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            s = decideHooks();
        }
        return s == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    private static synchronized byte decideHooks() {
        byte s = hooks;
        if (s == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            String aquifer = LEVELGEN + "Aquifer$NoiseBasedAquifer#";
            String foreign = dev.superchunk.MixinTargetScan.foreignHook(aquifer + "computeFluid",
                    aquifer + "computeFluidType", aquifer + "computeSurfaceLevel",
                    aquifer + "computeRandomizedFluidSurfaceLevel", LEVELGEN + "NoiseChunk#preliminarySurfaceLevel",
                    LEVELGEN + "NoiseChunk#computePreliminarySurfaceLevel");
            if (foreign != null && foreign.startsWith("<")) {
                return dev.superchunk.worldgen.ForeignHooks.UNKNOWN; // a class not loaded yet
            }
            s = foreign == null ? dev.superchunk.worldgen.ForeignHooks.CLEAR : dev.superchunk.worldgen.ForeignHooks.HOOKED;
            if (foreign != null) {
                LoggerFactory.getLogger("SuperChunk-Worldgen").info(
                        "[SuperChunk] Aquifer cell sharing is off: aquifer fluid levels are also changed by {}", foreign);
            }
            hooks = s;
        }
        return s;
    }

    public static boolean decide(NoiseRouter router) {
        if (Boolean.getBoolean("superchunk.worldgen.aquiferCellSharing.selfTest") && SELF_TESTED.compareAndSet(false, true)) {
            AquiferCellSharingSelfTest.run();
        }
        String why = reason(router);
        if (why != null) {
            LoggerFactory.getLogger("SuperChunk-Worldgen").info(
                    "[SuperChunk] Aquifer cell sharing is off for a dimension: its {}", why);
        } else {
            LoggerFactory.getLogger("SuperChunk-Worldgen").info("[SuperChunk] Aquifer cell sharing is on for a dimension");
        }
        return why == null;
    }

    /** Null when the router's aquifer functions can be shared; otherwise why not. */
    public static String reason(NoiseRouter r) {
        Walk walk = new Walk();
        String why;
        if ((why = walk.check(r.initialDensityWithoutJaggedness(), Site.COLUMNS)) != null) return "initial_density_without_jaggedness " + why;
        if ((why = walk.check(r.erosion(), Site.CELL)) != null) return "erosion " + why;
        if ((why = walk.check(r.depth(), Site.CELL)) != null) return "depth " + why;
        if ((why = walk.check(r.fluidLevelFloodednessNoise(), Site.CELL)) != null) return "fluid_level_floodedness " + why;
        if ((why = walk.check(r.fluidLevelSpreadNoise(), Site.OTHER)) != null) return "fluid_level_spread " + why;
        if ((why = walk.check(r.lavaNoise(), Site.OTHER)) != null) return "lava " + why;
        return null;
    }

    private static final class Walk {
        private static final String OK = "";
        // Routers are DAGs (holders reused all over the splines): memoize per node.
        private final Map<DensityFunction, String>[] checked = newMaps();
        private final Map<DensityFunction, Boolean> yIndependent = new IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        private static Map<DensityFunction, String>[] newMaps() {
            Map<DensityFunction, String>[] maps = new Map[Site.values().length];
            for (int i = 0; i < maps.length; i++) {
                maps[i] = new IdentityHashMap<>();
            }
            return maps;
        }

        String check(DensityFunction df, Site site) {
            Map<DensityFunction, String> memo = this.checked[site.ordinal()];
            String known = memo.get(df);
            if (known == null) {
                known = this.checkNew(df, site);
                memo.put(df, known == null ? OK : known);
            }
            return known == null || known.isEmpty() ? null : known;
        }

        private String checkNew(DensityFunction df, Site site) {
            if (df instanceof DensityFunctions.Marker marker) {
                switch (marker.type()) {
                    case FlatCache -> {
                        if (site == Site.OTHER) {
                            return "has a flat_cache";
                        }
                        if (site == Site.COLUMNS && !this.yIndependent(marker.wrapped())) {
                            return "has a flat_cache over a y-dependent function";
                        }
                    }
                    case Cache2D -> {
                        if (!this.yIndependent(marker.wrapped())) {
                            return "has a cache_2d over a y-dependent function";
                        }
                    }
                    default -> {
                        // interpolated, cache_once, cache_all_in_cell compute single points directly
                    }
                }
            }
            if (df instanceof DensityFunctions.BeardifierOrMarker) {
                return "reads the beardifier";
            }
            List<DensityFunction> children = children(df);
            if (children == null) {
                return "has a density function SuperChunk does not know (" + df.getClass().getName() + ")";
            }
            for (DensityFunction child : children) {
                String why = this.check(child, site);
                if (why != null) {
                    return why;
                }
            }
            return null;
        }

        boolean yIndependent(DensityFunction df) {
            Boolean known = this.yIndependent.get(df);
            if (known == null) {
                known = this.yIndependentNew(df);
                this.yIndependent.put(df, known);
            }
            return known;
        }

        private boolean yIndependentNew(DensityFunction df) {
            if (df instanceof DensityFunctions.Noise noise && noise.yScale() != 0.0) {
                return false;
            }
            if (df instanceof DensityFunctions.ShiftedNoise noise && noise.yScale() != 0.0) {
                return false;
            }
            if (df instanceof DensityFunctions.Shift || df instanceof DensityFunctions.YClampedGradient
                    || df instanceof DensityFunctions.WeirdScaledSampler || df instanceof BlendedNoise
                    || df instanceof DensityFunctions.BeardifierOrMarker) {
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
    }

    /**
     * The functions {@code df} evaluates at the same point, or null for a type not known here.
     * Leaves read only the point: noises (ShiftA/ShiftB at y = 0), gradients, constants, and the
     * blend markers (constant without a blender, and blending chunks never share).
     */
    private static List<DensityFunction> children(DensityFunction df) {
        return switch (df) {
            case DensityFunctions.Constant f -> List.of();
            case DensityFunctions.Noise f -> List.of();
            case DensityFunctions.Shift f -> List.of();
            case DensityFunctions.ShiftA f -> List.of();
            case DensityFunctions.ShiftB f -> List.of();
            case DensityFunctions.YClampedGradient f -> List.of();
            case DensityFunctions.BlendAlpha f -> List.of();
            case DensityFunctions.BlendOffset f -> List.of();
            case BlendedNoise f -> List.of();
            case DensityFunction f when f.getClass() == END_ISLANDS -> List.of();
            case DensityFunctions.Marker f -> List.of(f.wrapped());
            case DensityFunctions.HolderHolder f -> List.of(f.function().value());
            case DensityFunctions.ShiftedNoise f -> List.of(f.shiftX(), f.shiftY(), f.shiftZ());
            case DensityFunctions.TwoArgumentSimpleFunction f -> List.of(f.argument1(), f.argument2());
            case DensityFunctions.PureTransformer f -> List.of(f.input());
            case DensityFunctions.BlendDensity f -> List.of(f.input());
            case DensityFunctions.RangeChoice f -> List.of(f.input(), f.whenInRange(), f.whenOutOfRange());
            case DensityFunctions.WeirdScaledSampler f -> List.of(f.input());
            case DensityFunctions.Spline f -> {
                List<DensityFunction> coordinates = new ArrayList<>();
                splineCoordinates(f.spline(), coordinates);
                yield coordinates;
            }
            default -> null;
        };
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
