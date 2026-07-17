package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IXoroshiro128PlusPlusRandomImpl;
import net.minecraft.world.level.levelgen.MarsagliaPolarGaussian;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * SuperChunk: allocation-free {@code XoroshiroRandomSource.setSeed}.
 *
 * <p>Vanilla allocates a fresh {@link Xoroshiro128PlusPlus} (plus a {@code Seed128bit}
 * record) on EVERY reseed — and worldgen reseeds constantly ({@code setFeatureSeed} per
 * feature per chunk, {@code setDecorationSeed} per chunk, ...): 4.3% of all allocation
 * pressure in a pregen profile. Instead, write the two seed longs into the EXISTING
 * generator in place (via C2ME's {@code IXoroshiro128PlusPlusRandomImpl} accessor — the
 * same trick C2ME's own {@code RandomUtils.derive} uses).
 *
 * <p>Bit-identical: the state after reseed equals a fresh instance bit-for-bit — same
 * {@code upgradeSeedTo128bit} mixing (still vanilla's own call) and the exact
 * all-zero-seed guard constants from the {@code Xoroshiro128PlusPlus} constructor;
 * {@code gaussianSource.reset()} exactly as vanilla. Nothing in vanilla compares the
 * inner generator's identity. Disable with
 * {@code -Dsuperchunk.worldgen.xoroshiroReseed=false}.
 */
@Mixin(XoroshiroRandomSource.class)
public abstract class MixinXoroshiroReseedInPlace {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.xoroshiroReseed", "true"));

    // Exact zero-guard constants from Xoroshiro128PlusPlus(long, long).
    @Unique
    private static final long SUPERCHUNK$GUARD_LO = -7046029254386353131L;
    @Unique
    private static final long SUPERCHUNK$GUARD_HI = 7640891576956012809L;

    @Shadow
    private Xoroshiro128PlusPlus randomNumberGenerator;

    @Shadow
    @Final
    private MarsagliaPolarGaussian gaussianSource;

    @WrapMethod(method = "setSeed", require = 0)
    private void superchunk$reseedInPlace(long seed, Operation<Void> original) {
        Xoroshiro128PlusPlus gen = this.randomNumberGenerator;
        if (!SUPERCHUNK$ENABLED || gen == null) {
            original.call(seed);
            return;
        }
        RandomSupport.Seed128bit s = RandomSupport.upgradeSeedTo128bit(seed);
        long lo = s.seedLo();
        long hi = s.seedHi();
        if ((lo | hi) == 0L) {
            lo = SUPERCHUNK$GUARD_LO;
            hi = SUPERCHUNK$GUARD_HI;
        }
        IXoroshiro128PlusPlusRandomImpl impl = (IXoroshiro128PlusPlusRandomImpl) (Object) gen;
        impl.setSeedLo(lo);
        impl.setSeedHi(hi);
        this.gaussianSource.reset();
    }
}
