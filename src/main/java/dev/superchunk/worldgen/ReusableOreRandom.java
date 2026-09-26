package dev.superchunk.worldgen;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IXoroshiro128PlusPlusRandomDeriver;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * The ore-vein positional factory, handing each thread one reused generator.
 *
 * <p>Vanilla's ore-vein filler asks {@code random.at(x, y, z)} for a fresh
 * {@link XoroshiroRandomSource} (plus its {@code Xoroshiro128PlusPlus} and Gaussian helper) for
 * every block that passes the vein-band gate, draws at most three floats, and drops it: 3.4 GB
 * of sampled allocation over a 16.6k-chunk pregen. The filler is unchanged; only the object it
 * receives is reused, reset to exactly the state a new {@code XoroshiroRandomSource(lo, hi)}
 * starts in (the zero-state repair included, and no pending Gaussian).
 *
 * <p>The filler uses each generator only within one call and never re-enters itself, so each
 * thread reuses one generator of its own; nothing is shared between threads. Everything but
 * {@code at(x, y, z)} is delegated. The factory is only wrapped while no other mod's mixin is
 * applied to the Xoroshiro classes ({@link dev.superchunk.MixinTargetScan}), since reuse bypasses
 * their constructors. Disable with {@code -Dsuperchunk.worldgen.oreRandomReuse=false}.
 */
public final class ReusableOreRandom implements PositionalRandomFactory {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.oreRandomReuse", "true"));
    private static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.oreRandomReuse.verify");
    private static final java.util.concurrent.atomic.AtomicLong VERIFIED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MISMATCHES = new java.util.concurrent.atomic.AtomicLong();

    /** Implemented on {@code XoroshiroRandomSource} by {@code MixinXoroshiroReseedInPlace}. */
    public interface Resettable {
        /** The state {@code new XoroshiroRandomSource(lo, hi)} would start in. */
        void superchunk$resetState(long lo, long hi);
    }

    /** One generator per thread, reset in place for every {@code at(x, y, z)}. */
    private static final ThreadLocal<XoroshiroRandomSource> REUSED =
            ThreadLocal.withInitial(() -> new XoroshiroRandomSource(0L, 0L));
    /** 0 = not yet decided, 1 = usable, -1 = stand down. */
    private static volatile int usable;

    private final PositionalRandomFactory delegate;
    private final long seedLo;
    private final long seedHi;

    private ReusableOreRandom(PositionalRandomFactory delegate, long seedLo, long seedHi) {
        this.delegate = delegate;
        this.seedLo = seedLo;
        this.seedHi = seedHi;
    }

    /** Wraps exact vanilla Xoroshiro factories; anything else is returned unchanged. */
    public static PositionalRandomFactory wrap(PositionalRandomFactory factory) {
        if (!ENABLED || factory.getClass() != XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class
                || !(factory instanceof IXoroshiro128PlusPlusRandomDeriver seeds) || !usable()) {
            return factory;
        }
        return new ReusableOreRandom(factory, seeds.getSeedLo(), seeds.getSeedHi());
    }

    private static boolean usable() {
        int state = usable;
        if (state == 0) {
            synchronized (ReusableOreRandom.class) {
                state = usable;
                if (state == 0) {
                    String foreign = dev.superchunk.MixinTargetScan.foreignMixin(
                            "net.minecraft.world.level.levelgen.XoroshiroRandomSource",
                            "net.minecraft.world.level.levelgen.XoroshiroRandomSource$XoroshiroPositionalRandomFactory",
                            "net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus",
                            // The consumer: reuse is exact only for vanilla's one-at()-at-a-time use.
                            // A class literal, not a string: this check runs before the first vein
                            // fill loads OreVeinifier, and loading it is what scans it.
                            net.minecraft.world.level.levelgen.OreVeinifier.class.getName());
                    boolean resettable = REUSED.get() instanceof Resettable;
                    state = foreign == null && resettable ? 1 : -1;
                    usable = state;
                    if (state < 0) {
                        org.slf4j.LoggerFactory.getLogger("SuperChunk-OreRandom").info(
                                "Ore-vein generator reuse not used: {}",
                                foreign != null ? "the Xoroshiro classes are also modified by " + foreign
                                        : "the in-place reset is unavailable");
                    }
                }
            }
        }
        return state > 0;
    }

    @Override
    public RandomSource at(int x, int y, int z) {
        XoroshiroRandomSource reused = REUSED.get();
        // XoroshiroPositionalRandomFactory.at: new XoroshiroRandomSource(getSeed(x, y, z) ^ seedLo, seedHi)
        ((Resettable) reused).superchunk$resetState(Mth.getSeed(x, y, z) ^ this.seedLo, this.seedHi);
        if (VERIFY) {
            return this.verify(reused, x, y, z);
        }
        return reused;
    }

    /** Verify mode: compares the reset generator's first draws with a fresh one, returns vanilla's. */
    private RandomSource verify(XoroshiroRandomSource reused, int x, int y, int z) {
        RandomSource fresh = this.delegate.at(x, y, z);
        boolean same = true;
        for (int i = 0; i < 4; i++) {
            same &= reused.nextLong() == fresh.nextLong();
        }
        same &= reused.nextGaussian() == fresh.nextGaussian();
        long checked = VERIFIED.incrementAndGet();
        if (!same && MISMATCHES.incrementAndGet() <= 20) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-OreRandom").error(
                    "[ore-random] VERIFY MISMATCH at {}, {}, {}", x, y, z);
        }
        if ((checked & ((1L << 20) - 1)) == 0) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-OreRandom").info(
                    "[ore-random] verify: checked={} mismatches={}", checked, MISMATCHES.get());
        }
        return this.delegate.at(x, y, z);
    }

    public static void reportVerify() {
        if (VERIFY) {
            long m = MISMATCHES.get();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-OreRandom").info(
                    "[ore-random] VERIFY: checked={} MISMATCHES={} -> {}", VERIFIED.get(), m, m == 0 ? "PASS" : "FAIL");
        }
    }

    @Override
    public RandomSource fromHashOf(String name) {
        return this.delegate.fromHashOf(name);
    }

    @Override
    public RandomSource fromSeed(long seed) {
        return this.delegate.fromSeed(seed);
    }

    @Override
    public void parityConfigString(StringBuilder builder) {
        this.delegate.parityConfigString(builder);
    }
}
