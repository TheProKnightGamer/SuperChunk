package dev.superchunk.mixin;

import com.mojang.serialization.DataResult;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private-static {@code PalettedContainer.unpack(IdMap, Strategy, PackedData)} so the
 * fast chunk-load palette reader can reuse vanilla's exact container build (palette order +
 * {@code SimpleBitStorage}) after decoding the palette entries directly. See
 * {@code dev.superchunk.chunkio.FastChunkPaletteRead}.
 */
@Mixin(PalettedContainer.class)
public interface IPalettedContainerUnpackAccess {
    @Invoker("unpack")
    static <T> DataResult<PalettedContainer<T>> superchunk$unpack(
            IdMap<T> registry, PalettedContainer.Strategy strategy, PalettedContainerRO.PackedData<T> packedData) {
        throw new AssertionError("mixin not applied");
    }
}
