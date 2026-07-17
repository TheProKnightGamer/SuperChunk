package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.PlainWorldgenRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk: construct {@link PlainWorldgenRandom} (unsynchronized {@code setSeed} —
 * JDK 21 has no biased locking, so vanilla's monitor is a real per-reseed cost) at the
 * vanilla method-local {@code new WorldgenRandom(...)} sites: carvers/mob-spawn
 * ({@code NoiseBasedChunkGenerator}), decoration + structure selection
 * ({@code ChunkGenerator}), and structure placement probing
 * ({@code RandomSpreadStructurePlacement}). All instances are provably thread-confined.
 *
 * <p>Bit-identical: the subclass changes no math (see {@link PlainWorldgenRandom}).
 * Composes with C2ME's inner {@code new LegacyRandomSource} redirect (different NEW
 * instruction). {@code @WrapOperation} rather than {@code @Redirect}: a second mod
 * redirecting the same ctor is boot-fatal even with {@code require = 0}
 * ({@code RedirectInjector.postInject} throws), while wraps chain arbitrarily.
 * {@code require = 0}: degrades to vanilla wherever the pattern moves.
 * Disable with {@code -Dsuperchunk.worldgen.plainWorldgenRandom=false}.
 */
@Mixin(value = {NoiseBasedChunkGenerator.class, ChunkGenerator.class, RandomSpreadStructurePlacement.class})
public abstract class MixinPlainWorldgenRandomSites {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.plainWorldgenRandom", "true"));

    @WrapOperation(
            method = "*",
            at = @At(
                    value = "NEW",
                    target = "(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/level/levelgen/WorldgenRandom;"),
            require = 0)
    private WorldgenRandom superchunk$plainWorldgenRandom(RandomSource delegate, Operation<WorldgenRandom> original) {
        return SUPERCHUNK$ENABLED ? new PlainWorldgenRandom(delegate) : original.call(delegate);
    }
}
