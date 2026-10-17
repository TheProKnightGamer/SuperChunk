package dev.superchunk.worldgen;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IAtomicSimpleRandomDeriver;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IXoroshiro128PlusPlusRandomDeriver;
import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/** Compares raw float bits with the actual Minecraft positional factories and RNGs. */
public final class FirstPositionalFloatTest {
    public static void main(String[] args) {
        long[] seeds = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, -7046029254386353131L, 7640891576956012809L};
        int[] positions = {Integer.MIN_VALUE, -30_000_000, -2032, -64, -1, 0, 1, 63, 2031, 30_000_000, Integer.MAX_VALUE};
        int checked = 0;
        for (long lo : seeds) {
            for (long hi : seeds) {
                var xoroshiro = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(lo, hi);
                var legacy = new LegacyRandomSource.LegacyPositionalRandomFactory(lo ^ hi);
                for (int x : positions) {
                    for (int y : positions) {
                        for (int z : positions) {
                            same(xoroshiro.at(x, y, z).nextFloat(), FirstPositionalFloat.xoroshiro(lo, hi, x, y, z));
                            same(legacy.at(x, y, z).nextFloat(), FirstPositionalFloat.legacy(lo ^ hi, x, y, z));
                            checked += 2;
                        }
                    }
                }
            }
        }
        Random random = new Random(0x5fa9L);
        for (int i = 0; i < 250_000; i++) {
            long lo = random.nextLong(), hi = random.nextLong();
            int x = random.nextInt(), y = random.nextInt(), z = random.nextInt();
            var xoroshiro = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(lo, hi);
            var legacy = new LegacyRandomSource.LegacyPositionalRandomFactory(lo);
            same(xoroshiro.at(x, y, z).nextFloat(), FirstPositionalFloat.xoroshiro(lo, hi, x, y, z));
            same(legacy.at(x, y, z).nextFloat(), FirstPositionalFloat.legacy(lo, x, y, z));
            // Force the positional XOR to produce the all-zero state. The constructor
            // repairs this state before drawing; missing that repair changes the float.
            long zeroStateSeed = Mth.getSeed(x, y, z);
            var repaired = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(zeroStateSeed, 0);
            same(repaired.at(x, y, z).nextFloat(), FirstPositionalFloat.xoroshiro(zeroStateSeed, 0, x, y, z));
            checked += 3;
        }

        // Subclasses still fall through even when they expose the vanilla seed accessors.
        if (!Float.isNaN(FirstPositionalFloat.sample(new CustomXoroshiroFactory(), 1, 2, 3))
                || !Float.isNaN(FirstPositionalFloat.sample(new CustomLegacyFactory(), 1, 2, 3))) {
            throw new AssertionError("custom positional factory was bypassed");
        }
        checkGradientConditions();
        System.out.println("FirstPositionalFloat: " + checked + " actual-factory bit-parity cases and subclass gates passed");
    }

    private static void checkGradientConditions() {
        // Explicit outcomes test the production branch/threshold code. These are
        // intentionally distinct from the seeded fast-math comparisons above: ordinary
        // Minecraft factories in a unit JVM do not have the runtime seed accessors.
        GradientCase[] cases = {
                new GradientCase(-64, -64, -60, Float.NaN, true, 0),
                new GradientCase(-65, -64, -60, Float.NaN, true, 0),
                new GradientCase(-60, -64, -60, Float.NaN, false, 0),
                new GradientCase(-59, -64, -60, Float.NaN, false, 0),
                new GradientCase(-63, -64, -60, 0.75F, false, 1),
                new GradientCase(-63, -64, -60, Math.nextDown(0.75F), true, 1),
                new GradientCase(-62, -64, -60, 0.5F, false, 1),
                new GradientCase(-62, -64, -60, Math.nextDown(0.5F), true, 1),
                new GradientCase(-61, -64, -60, 0.25F, false, 1),
                new GradientCase(-61, -64, -60, Math.nextDown(0.25F), true, 1),
                new GradientCase(0, -1, 1, Float.NaN, false, 1),
                new GradientCase(0, -1, 1, Float.NEGATIVE_INFINITY, true, 1),
                new GradientCase(0, -1, 1, Float.POSITIVE_INFINITY, false, 1),
                // The mathematical midpoint is -0.5: double Mth.map must retain
                // the two sides instead of rounding the probability to float 0.5.
                new GradientCase(-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0.5F, true, 1),
                new GradientCase(0, Integer.MIN_VALUE, Integer.MAX_VALUE, 0.5F, false, 1),
                new GradientCase(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Float.NaN, true, 0),
                new GradientCase(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Float.NaN, false, 0),
                new GradientCase(1, 1, 1, Float.NaN, true, 0),
                new GradientCase(2, 1, 1, Float.NaN, false, 0),
                new GradientCase(0, 5, -5, Float.NaN, true, 0),
                new GradientCase(5, 5, -5, Float.NaN, true, 0),
                new GradientCase(6, 5, -5, Float.NaN, false, 0)
        };
        for (boolean enabled : new boolean[] {false, true}) {
            for (GradientCase test : cases) {
                FixedFactory factory = new FixedFactory(test.sample);
                boolean actual = FirstPositionalFloat.testGradient(factory, -30_000_000, test.y, 30_000_000,
                        test.lower, test.upper, enabled);
                if (actual != test.expected || factory.atCalls != test.draws || factory.floatCalls != test.draws) {
                    throw new AssertionError("gradient result/draw count mismatch: " + test + ", enabled=" + enabled);
                }
            }
        }
        System.out.println("FirstPositionalFloat: 44 gradient threshold, anchor-order and exact-draw-count cases passed");
    }

    private record GradientCase(int y, int lower, int upper, float sample, boolean expected, int draws) {
    }

    private static final class FixedFactory extends LegacyRandomSource.LegacyPositionalRandomFactory
            implements IAtomicSimpleRandomDeriver {
        private final RandomSource random;
        private int atCalls;
        private int floatCalls;

        FixedFactory(float value) {
            super(0);
            this.random = new LegacyRandomSource(0) {
                @Override
                public float nextFloat() {
                    FixedFactory.this.floatCalls++;
                    return value;
                }
            };
        }

        @Override
        public long getSeed() {
            return 0;
        }

        @Override
        public RandomSource at(int x, int y, int z) {
            atCalls++;
            if (x != -30_000_000 || z != 30_000_000) {
                throw new AssertionError("gradient changed the sampling coordinates");
            }
            return this.random;
        }
    }

    private static void same(float expected, float actual) {
        if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual)) {
            throw new AssertionError("expected float " + expected + " but got " + actual);
        }
    }

    private static final class CustomXoroshiroFactory extends XoroshiroRandomSource.XoroshiroPositionalRandomFactory
            implements IXoroshiro128PlusPlusRandomDeriver {
        CustomXoroshiroFactory() {
            super(0, 0);
        }

        @Override
        public long getSeedLo() {
            return 0;
        }

        @Override
        public long getSeedHi() {
            return 0;
        }

        @Override
        public RandomSource at(int x, int y, int z) {
            throw new AssertionError("unsupported factory should only be called by the original surface method");
        }
    }

    private static final class CustomLegacyFactory extends LegacyRandomSource.LegacyPositionalRandomFactory
            implements IAtomicSimpleRandomDeriver {
        CustomLegacyFactory() {
            super(0);
        }

        @Override
        public long getSeed() {
            return 0;
        }

        @Override
        public RandomSource at(int x, int y, int z) {
            throw new AssertionError("unsupported factory should only be called by the original surface method");
        }
    }
}
