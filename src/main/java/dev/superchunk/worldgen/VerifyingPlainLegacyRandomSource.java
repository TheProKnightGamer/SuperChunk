package dev.superchunk.worldgen;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lockstep parity verifier for {@link PlainLegacyRandomSource} (enable with
 * {@code -Dsuperchunk.worldgen.plainWorldgenRandom.verify=true}).
 *
 * <p>Mirrors every {@code setSeed}/{@code next} against an authentic (atomic) vanilla
 * {@link LegacyRandomSource} seeded identically, and counts any bit-divergence. Because
 * the substituted random's ONLY observable effect is its draw sequence (plus its
 * {@code LegacyRandomSource} type identity, which the subclass preserves), a clean run
 * — 0 mismatches over millions of draws — is a complete in-vivo bit-parity proof for
 * this candidate, immune to the generation-order nondeterminism that defeats
 * region-level comparison. ({@code nextGaussian} needs no separate mirror: both sides
 * derive it from {@code nextDouble} → {@code next()}, which is what's verified.)
 */
public final class VerifyingPlainLegacyRandomSource extends PlainLegacyRandomSource {

    private static final Logger LOG = LoggerFactory.getLogger("SuperChunk-PlainRandomVerify");
    private static final AtomicLong DRAWS = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static final long LOG_EVERY = 50_000_000L;

    // The authentic vanilla random, driven in lockstep. Created lazily because the
    // super-ctor calls setSeed virtually before this field can be assigned.
    private LegacyRandomSource reference;
    private long pendingSeed;
    private boolean hasPendingSeed;

    private static volatile boolean announced;

    public VerifyingPlainLegacyRandomSource(long seed) {
        super(seed);
        // The super-ctor routed setSeed(seed) through our override while `reference`
        // was null; replay it now.
        this.reference = new LegacyRandomSource(this.hasPendingSeed ? this.pendingSeed : seed);
        if (!announced) {
            announced = true;
            LOG.info("[plainRandom-verify] lockstep verification ACTIVE (plain vs vanilla LegacyRandomSource)");
        }
    }

    @Override
    public void setSeed(long seed) {
        super.setSeed(seed);
        if (this.reference == null) {
            this.pendingSeed = seed;
            this.hasPendingSeed = true;
        } else {
            this.reference.setSeed(seed);
        }
    }

    @Override
    public int next(int size) {
        int mine = super.next(size);
        int ref = this.reference.next(size);
        long n = DRAWS.incrementAndGet();
        if (mine != ref) {
            long m = MISMATCHES.incrementAndGet();
            if (m <= 5) {
                LOG.warn("[plainRandom-verify] MISMATCH #{}: plain={} vanilla={} (size={}, draw #{})",
                        m, mine, ref, size, n);
            }
        }
        if (n % LOG_EVERY == 0) {
            LOG.info("[plainRandom-verify] draws={} mismatches={}", n, MISMATCHES.get());
        }
        return mine;
    }

    /** Final tally for harness greps. */
    public static String summary() {
        return "draws=" + DRAWS.get() + " mismatches=" + MISMATCHES.get();
    }
}
