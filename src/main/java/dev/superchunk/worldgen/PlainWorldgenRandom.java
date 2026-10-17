package dev.superchunk.worldgen;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * {@link WorldgenRandom} without the {@code synchronized} on {@code setSeed} (SuperChunk
 * worldgen optimisation).
 *
 * <p><b>Why.</b> JDK 21 removed biased locking (JEP 374), so vanilla's
 * {@code synchronized setSeed} is a real monitorenter/exit pair on every reseed even on a
 * thread-confined object. {@code applyCarvers} reseeds per carver per neighbor chunk
 * (~1,200+ times/chunk via {@code setLargeFeatureSeed}), decoration reseeds per feature
 * ({@code setFeatureSeed}), and structure placement per structure set — all on
 * method-local instances that never escape the worldgen worker.
 *
 * <p><b>Why bit-identical.</b> The override body is vanilla's exactly
 * ({@code if (randomSource != null) randomSource.setSeed(seed)}) minus the monitor —
 * locks have no effect on computed values. The delegate reference is captured in our own
 * field from the constructor argument (the parent's is private); during the parent
 * constructor chain the virtual call sees our field still null and skips, exactly like
 * vanilla's own null-guard does at that same moment. Everything else — {@code next},
 * {@code setDecorationSeed}/{@code setFeatureSeed}/{@code setLargeFeatureSeed},
 * {@code getCount}, forks — is inherited unchanged.
 */
public final class PlainWorldgenRandom extends WorldgenRandom {

    private final RandomSource delegateRef;

    public PlainWorldgenRandom(RandomSource randomSource) {
        super(randomSource);
        this.delegateRef = randomSource;
    }

    @Override
    public void setSeed(long seed) {
        if (this.delegateRef != null) {
            this.delegateRef.setSeed(seed);
        }
    }
}
