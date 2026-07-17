package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IXoroshiro128PlusPlusRandomDeriver;
import dev.superchunk.gpu.aquifer.BlockIdCensus;
import dev.superchunk.gpu.aquifer.ScVeinCaptureDf;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * STAGE-3 census (Phase A) — installs the ore-vein CPU oracle.
 *
 * <p>Wraps the {@code OreVeinifier.create(veinToggle, veinRidged, veinGap, oreRandom)}
 * call inside the {@code NoiseChunk} constructor: when {@code
 * -Dsuperchunk.gpu.blockIdVerify} is on, the three vein density functions are wrapped
 * in {@link ScVeinCaptureDf} pass-through recorders (exact values forwarded — the
 * vanilla lambda and shipped terrain are byte-identical) so the census sees, per
 * block, the very vein DF values the CPU decision consumed, exactly as lazily as
 * vanilla computed them. The ore positional-Xoroshiro seed halves ride along (via the
 * C2ME accessor) so the census kernel/ref can recompute the RNG draws bit-exactly.
 * Flag off (the default): the original call runs with untouched arguments.
 */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunkVeinCensus {

    @Unique
    private static final boolean SUPERCHUNK$CENSUS = Boolean.getBoolean("superchunk.gpu.blockIdVerify");

    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create("
                            + "Lnet/minecraft/world/level/levelgen/DensityFunction;"
                            + "Lnet/minecraft/world/level/levelgen/DensityFunction;"
                            + "Lnet/minecraft/world/level/levelgen/DensityFunction;"
                            + "Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)"
                            + "Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"))
    private NoiseChunk.BlockStateFiller superchunk$captureVeinInputs(
            DensityFunction veinToggle, DensityFunction veinRidged, DensityFunction veinGap,
            PositionalRandomFactory random, Operation<NoiseChunk.BlockStateFiller> original) {
        if (!SUPERCHUNK$CENSUS) {
            return original.call(veinToggle, veinRidged, veinGap, random);
        }
        long lo = 0L, hi = 0L;
        boolean seedOk = false;
        try {
            if (random instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory) {
                lo = ((IXoroshiro128PlusPlusRandomDeriver) random).getSeedLo();
                hi = ((IXoroshiro128PlusPlusRandomDeriver) random).getSeedHi();
                seedOk = true;
            }
        } catch (Throwable ignored) {
            // seedOk stays false -> the census counts these chunks as vein-oracle-absent.
        }
        return original.call(
                new ScVeinCaptureDf(veinToggle, BlockIdCensus.VEIN_TOGGLE, lo, hi, seedOk),
                new ScVeinCaptureDf(veinRidged, BlockIdCensus.VEIN_RIDGED, lo, hi, seedOk),
                new ScVeinCaptureDf(veinGap, BlockIdCensus.VEIN_GAP, lo, hi, seedOk),
                random);
    }
}
