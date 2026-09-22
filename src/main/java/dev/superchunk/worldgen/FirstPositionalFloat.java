package dev.superchunk.worldgen;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IAtomicSimpleRandomDeriver;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IXoroshiro128PlusPlusRandomDeriver;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.LoggerFactory;

/** The first float from a vanilla positional factory, without constructing its discarded RNG. */
public final class FirstPositionalFloat {
    private static final float FLOAT_UNIT = 0x1.0p-24F;
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.surfaceGradientRandom.verify");
    private static final LongAdder CHECKED = new LongAdder();
    private static final LongAdder MISMATCHES = new LongAdder();

    private FirstPositionalFloat() {
    }

    /** The complete vanilla gradient condition, with only its discarded RNG allocation removed. */
    public static boolean testGradient(PositionalRandomFactory factory, int x, int y, int z,
                                       int lower, int upper, boolean enabled) {
        // Order matters for equal or inverted anchors: vanilla accepts y <= lower first.
        if (y <= lower) {
            return true;
        } else if (y >= upper) {
            return false;
        }
        double probability = Mth.map((double) y, (double) lower, (double) upper, 1.0, 0.0);
        float sample = enabled ? sample(factory, x, y, z) : Float.NaN;
        if (Float.isNaN(sample)) {
            sample = factory.at(x, y, z).nextFloat();
        } else if (VERIFY) {
            verify(factory, x, y, z, sample);
        }
        return (double) sample < probability;
    }

    /** Called only in verify mode; production sampling does not touch shared counters. */
    public static void verify(PositionalRandomFactory factory, int x, int y, int z, float sample) {
        float vanilla = factory.at(x, y, z).nextFloat();
        CHECKED.increment();
        if (Float.floatToRawIntBits(sample) != Float.floatToRawIntBits(vanilla)) {
            MISMATCHES.increment();
            throw new IllegalStateException("Surface-gradient positional RNG mismatch at " + x + "," + y + "," + z);
        }
    }

    public static void reportVerify() {
        if (VERIFY) {
            LoggerFactory.getLogger("SuperChunk-SurfaceGradient").info(
                    "[surface-gradient-verify] checked={} mismatches={}", CHECKED.sum(), MISMATCHES.sum());
        }
    }

    /** NaN means unsupported: the caller must invoke the original factory and generator. */
    public static float sample(PositionalRandomFactory factory, int x, int y, int z) {
        if (factory.getClass() == XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class
                && factory instanceof IXoroshiro128PlusPlusRandomDeriver seeds) {
            return xoroshiro(seeds.getSeedLo(), seeds.getSeedHi(), x, y, z);
        }
        if (factory.getClass() == LegacyRandomSource.LegacyPositionalRandomFactory.class
                && factory instanceof IAtomicSimpleRandomDeriver seeds) {
            return legacy(seeds.getSeed(), x, y, z);
        }
        return Float.NaN;
    }

    public static float xoroshiro(long factoryLo, long factoryHi, int x, int y, int z) {
        long lo = Mth.getSeed(x, y, z) ^ factoryLo;
        long hi = factoryHi;
        // Xoroshiro128PlusPlus's constructor repairs the otherwise absorbing zero state.
        if ((lo | hi) == 0L) {
            lo = -7046029254386353131L;
            hi = 7640891576956012809L;
        }
        long first = Long.rotateLeft(lo + hi, 17) + lo;
        return (float) (first >>> 40) * FLOAT_UNIT;
    }

    public static float legacy(long factorySeed, int x, int y, int z) {
        long seed = (Mth.getSeed(x, y, z) ^ factorySeed ^ 25214903917L) & 281474976710655L;
        long next = (seed * 25214903917L + 11L) & 281474976710655L;
        return (float) (next >>> 24) * FLOAT_UNIT;
    }
}
