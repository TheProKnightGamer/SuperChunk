package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.PlainLegacyRandomSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.carver.CanyonWorldCarver;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk carver optimisation: the per-tunnel randoms.
 *
 * <p>Vanilla creates a fresh {@code RandomSource.create(seed)} (= a raw
 * {@code LegacyRandomSource}) INSIDE {@code CaveWorldCarver.createTunnel} and
 * {@code CanyonWorldCarver.doCarve} — one per tunnel/canyon — and draws from it per
 * segment. Profiling shows those draws (AtomicLong get+CAS per draw) are ~39% of all
 * carver CPU. C2ME's {@code MixinRedirectAtomicSimpleRandom} already de-atomicises the
 * OUTER {@code applyCarvers}/{@code GeodeFeature}/structure-placement randoms but does
 * NOT cover these carver-internal call sites — this mixin closes that gap.
 *
 * <p>Bit-identical: {@link PlainLegacyRandomSource} reproduces the exact vanilla ctor
 * seed-scramble and LCG on a plain {@code long} (unlike C2ME's
 * {@code SimplifiedAtomicSimpleRandom}, whose ctor stores the seed RAW — safe only at
 * sites that re-seed before drawing; these sites draw immediately, so ctor semantics
 * matter). It extends {@code LegacyRandomSource}, so any downstream {@code instanceof}
 * stays true. The randoms are method-local — thread-safety is not needed.
 *
 * <p>Mod-compat: a {@code @WrapOperation} with {@code require = 0} — degrades to vanilla
 * if the call sites move, and chains with any other mod wrapping/redirecting the same
 * call (a plain {@code @Redirect} here would be exclusive: second redirecter loses or
 * crashes). Other mods' carvers (their own classes) are untouched. Disable with
 * {@code -Dsuperchunk.worldgen.plainWorldgenRandom=false}.
 */
@Mixin(value = {CaveWorldCarver.class, CanyonWorldCarver.class})
public abstract class MixinCarverPlainRandom {

    @Unique
    private static final boolean SUPERCHUNK$PLAIN_RANDOM =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.plainWorldgenRandom", "true"));
    @Unique
    private static final boolean SUPERCHUNK$VERIFY =
            Boolean.getBoolean("superchunk.worldgen.plainWorldgenRandom.verify");

    @WrapOperation(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;create(J)Lnet/minecraft/util/RandomSource;"),
            require = 0)
    private RandomSource superchunk$plainTunnelRandom(long seed, Operation<RandomSource> original) {
        if (!SUPERCHUNK$PLAIN_RANDOM) {
            return original.call(seed);
        }
        return SUPERCHUNK$VERIFY
                ? new dev.superchunk.worldgen.VerifyingPlainLegacyRandomSource(seed)
                : new PlainLegacyRandomSource(seed);
    }
}
