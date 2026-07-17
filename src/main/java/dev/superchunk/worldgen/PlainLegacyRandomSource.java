package dev.superchunk.worldgen;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;

/**
 * Bit-identical {@link LegacyRandomSource} without the per-draw atomics (SuperChunk
 * carver/worldgen optimisation).
 *
 * <p><b>Why.</b> Vanilla {@code LegacyRandomSource} keeps its 48-bit LCG state in an
 * {@link java.util.concurrent.atomic.AtomicLong} and does a {@code get}+{@code compareAndSet}
 * on EVERY draw purely as a threading detector. Profiling a pregen shows that CAS is ~24% of
 * all carver CPU (plus a further slice of {@code next()} itself). The carver / mob-spawn
 * randoms are method-local objects that never escape their worldgen worker thread, so the
 * detector can never legitimately fire there — the atomicity is pure overhead.
 *
 * <p><b>Why bit-identical.</b> This subclass reproduces the exact vanilla LCG math
 * ({@code seed*25214903917+11 & (2^48-1)}, output {@code (int)(seed >> 48-bits)}) and the
 * exact {@code setSeed} scramble on a plain {@code long}. It EXTENDS LegacyRandomSource, so
 * {@code WorldgenRandom.next(bits)}'s {@code instanceof LegacyRandomSource} delegate branch
 * still takes the same path (swapping in vanilla's own {@code SingleThreadedRandomSource}
 * would take the {@code nextLong()>>>64-bits} branch instead — a DIFFERENT sequence).
 * {@code nextGaussian} uses its own {@link MarsagliaPolarGaussian} exactly like the parent.
 * {@code fork}/{@code forkPositional} are inherited: they consume the same draws and return
 * the same vanilla types.
 *
 * <p>Thread-safety: NONE (by design). Only substitute where the instance provably stays
 * method-/thread-local, e.g. {@code NoiseBasedChunkGenerator.applyCarvers} and
 * {@code spawnOriginalMobs}.
 */
public class PlainLegacyRandomSource extends LegacyRandomSource {

    private static final long MODULUS_MASK = 281474976710655L; // 2^48 - 1
    private static final long MULTIPLIER = 25214903917L;
    private static final long INCREMENT = 11L;

    // NO initializer: the parent constructor calls this.setSeed(...) virtually before
    // subclass field-initialisers run, and an initializer would then wipe the seed.
    private long plainSeed;
    // Created in our constructor (after super()); null-checked in setSeed because the
    // parent-ctor call arrives while this is still null (a fresh gaussian == reset state).
    private MarsagliaPolarGaussian plainGaussian;

    public PlainLegacyRandomSource(long seed) {
        super(seed);
        this.plainGaussian = new MarsagliaPolarGaussian(this);
    }

    @Override
    public void setSeed(long seed) {
        this.plainSeed = (seed ^ MULTIPLIER) & MODULUS_MASK;
        if (this.plainGaussian != null) {
            this.plainGaussian.reset();
        }
    }

    @Override
    public int next(int size) {
        long l = this.plainSeed * MULTIPLIER + INCREMENT & MODULUS_MASK;
        this.plainSeed = l;
        return (int) (l >> 48 - size);
    }

    @Override
    public double nextGaussian() {
        return this.plainGaussian.nextGaussian();
    }
}
