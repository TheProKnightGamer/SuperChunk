package dev.superchunk.mixin;

import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the package-private {@code PalettedContainer.Strategy.calculateBitsForSerialization} to
 * the chunk-save palette encoders ({@code dev.superchunk.chunkio.FastPalettePacking},
 * {@code DirectPaletteEncoder}). An access transformer that made it public would crash any mod that
 * {@code @Overwrite}s it at vanilla visibility ("cannot reduce visibiliy", GitHub #31).
 */
@Mixin(PalettedContainer.Strategy.class)
public interface IPalettedContainerStrategyAccess {
    @Invoker("calculateBitsForSerialization")
    <A> int superchunk$calculateBitsForSerialization(IdMap<A> registry, int size);
}
