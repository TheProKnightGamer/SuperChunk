package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer;

import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.LoggerFactory;

import java.util.function.UnaryOperator;

/**
 * Self-test of {@link AquiferCellSharing} ({@code -Dsuperchunk.worldgen.aquiferCellSharing.selfTest=true},
 * run once at the first decision; density functions need the game's registries, so it cannot be a
 * standalone test): the overworld's shapes share, and each way a datapack can make
 * {@code computeFluid} depend on the asking chunk turns sharing off. The real vanilla routers are
 * the in-game decisions themselves ("Aquifer cell sharing is on").
 */
final class AquiferCellSharingSelfTest {

    private AquiferCellSharingSelfTest() {
    }

    static void run() {
        try {
            int checks = runChecks();
            LoggerFactory.getLogger("SuperChunk-Worldgen").info("[aquifer-cell-sharing] SELFTEST: {} routers checked -> PASS", checks);
        } catch (Throwable t) {
            LoggerFactory.getLogger("SuperChunk-Worldgen").error("[aquifer-cell-sharing] SELFTEST -> FAIL", t);
        }
    }

    private static int runChecks() {
        // No registries outside the game (NeoForge's bootstrap needs FML): build the overworld's
        // shapes from the same factories — flat_cache(cache_2d(shift)) inputs, flat_cache'd 2D
        // climate noises, a spline over them, y gradients, and the aquifer's plain 3D noises.
        Holder<NormalNoise.NoiseParameters> noise = Holder.direct(new NormalNoise.NoiseParameters(-3, 1.0, 1.0));
        DensityFunction shiftX = DensityFunctions.flatCache(DensityFunctions.cache2d(DensityFunctions.shiftA(noise)));
        DensityFunction shiftZ = DensityFunctions.flatCache(DensityFunctions.cache2d(DensityFunctions.shiftB(noise)));
        DensityFunction continents = DensityFunctions.flatCache(DensityFunctions.shiftedNoise2d(shiftX, shiftZ, 0.25, noise));
        DensityFunction erosion = DensityFunctions.flatCache(DensityFunctions.shiftedNoise2d(shiftX, shiftZ, 0.25, noise));
        DensityFunctions.Spline.Coordinate onContinents = new DensityFunctions.Spline.Coordinate(Holder.direct(continents));
        DensityFunctions.Spline.Coordinate onErosion = new DensityFunctions.Spline.Coordinate(Holder.direct(erosion));
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline = CubicSpline.builder(onContinents)
                .addPoint(-1.0F, CubicSpline.builder(onErosion).addPoint(-1.0F, 0.2F).addPoint(1.0F, -0.1F).build())
                .addPoint(1.0F, 0.4F)
                .build();
        DensityFunction offset = DensityFunctions.flatCache(DensityFunctions.cache2d(DensityFunctions.spline(spline)));
        DensityFunction depth = DensityFunctions.add(DensityFunctions.yClampedGradient(-64, 320, 1.5, -1.5), offset);
        DensityFunction initial = DensityFunctions.add(DensityFunctions.constant(0.1171875), DensityFunctions.mul(
                DensityFunctions.yClampedGradient(-64, -40, 0.0, 1.0),
                DensityFunctions.add(DensityFunctions.constant(-0.1171875),
                        DensityFunctions.mul(DensityFunctions.constant(4.0), DensityFunctions.mul(depth, offset).quarterNegative()))
                        .clamp(-64.0, 64.0)));
        DensityFunction floodedness = DensityFunctions.noise(noise, 1.0, 0.67);
        DensityFunction spread = DensityFunctions.noise(noise, 1.0, 0.7142857142857143);
        DensityFunction lava = DensityFunctions.noise(noise);
        DensityFunction zero = DensityFunctions.zero();
        NoiseRouter overworld = new NoiseRouter(zero, floodedness, spread, lava, zero, zero, continents, erosion, depth,
                zero, DensityFunctions.interpolated(initial), zero, zero, zero, zero);
        int checks = expect(overworld, r -> r, null);

        DensityFunction yGradient = DensityFunctions.yClampedGradient(-64, 320, 1.0, -1.0);
        DensityFunction flatOverY = DensityFunctions.flatCache(DensityFunctions.add(overworld.erosion(), yGradient));
        DensityFunction cache2dOverY = DensityFunctions.cache2d(DensityFunctions.add(overworld.depth(), yGradient));
        // A y-dependent flat_cache is fine where computeFluid reads only the cell position (the key has the variant).
        checks += expect(overworld, r -> with(r, "erosion", flatOverY), null);
        checks += expect(overworld, r -> with(r, "fluid_level_floodedness", flatOverY), null);
        checks += expect(overworld, r -> with(r, "initial_density_without_jaggedness",
                DensityFunctions.add(r.initialDensityWithoutJaggedness(), DensityFunctions.flatCache(r.erosion()))), null);
        // ... but not down the surface columns, nor where lava / spread read.
        checks += expect(overworld, r -> with(r, "initial_density_without_jaggedness",
                DensityFunctions.add(r.initialDensityWithoutJaggedness(), flatOverY)), "flat_cache over a y-dependent");
        checks += expect(overworld, r -> with(r, "lava", DensityFunctions.flatCache(r.erosion())), "has a flat_cache");
        checks += expect(overworld, r -> with(r, "fluid_level_spread", DensityFunctions.flatCache(r.erosion())), "has a flat_cache");
        checks += expect(overworld, r -> with(r, "depth", cache2dOverY), "cache_2d over a y-dependent");
        checks += expect(overworld, r -> with(r, "erosion",
                DensityFunctions.add(r.erosion(), DensityFunctions.BeardifierMarker.INSTANCE)), "beardifier");
        checks += expect(overworld, r -> with(r, "depth", new DensityFunction.SimpleFunction() {
            @Override
            public double compute(DensityFunction.FunctionContext context) {
                return 0.0;
            }

            @Override
            public double minValue() {
                return 0.0;
            }

            @Override
            public double maxValue() {
                return 0.0;
            }

            @Override
            public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
                throw new UnsupportedOperationException();
            }
        }), "does not know");
        return checks;
    }

    private static int expect(NoiseRouter base, UnaryOperator<NoiseRouter> edit, String reasonPart) {
        String why = AquiferCellSharing.reason(edit.apply(base));
        if (reasonPart == null ? why != null : why == null || !why.contains(reasonPart)) {
            throw new AssertionError("expected " + (reasonPart == null ? "sharing" : "'" + reasonPart + "'") + ", got " + why);
        }
        return 1;
    }

    private static NoiseRouter with(NoiseRouter r, String name, DensityFunction df) {
        return new NoiseRouter(r.barrierNoise(),
                name.equals("fluid_level_floodedness") ? df : r.fluidLevelFloodednessNoise(),
                name.equals("fluid_level_spread") ? df : r.fluidLevelSpreadNoise(),
                name.equals("lava") ? df : r.lavaNoise(),
                r.temperature(), r.vegetation(), r.continents(),
                name.equals("erosion") ? df : r.erosion(),
                name.equals("depth") ? df : r.depth(),
                r.ridges(),
                name.equals("initial_density_without_jaggedness") ? df : r.initialDensityWithoutJaggedness(),
                r.finalDensity(), r.veinToggle(), r.veinRidged(), r.veinGap());
    }
}
