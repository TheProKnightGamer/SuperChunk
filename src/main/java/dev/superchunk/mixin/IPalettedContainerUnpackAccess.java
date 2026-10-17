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
 *
 * <p>Forge 1.20.1: signature-identical to 1.21.1 (src1201 PalettedContainer:184,
 * PalettedContainerRO.PackedData). A STATIC {@code @Invoker} in an interface accessor mixin is an
 * established shape in this tree on runtime mixin 0.8.5 (see
 * {@code net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.collisions.empty_space.ArrayVoxelShapeInvoker});
 * the PORT-NOTES "no injectors in interface mixins" rule is about mixins whose TARGET is an
 * interface — accessors/invokers onto a class target are unaffected.
 */
@Mixin(PalettedContainer.class)
public interface IPalettedContainerUnpackAccess {
    @Invoker("unpack")
    static <T> DataResult<PalettedContainer<T>> superchunk$unpack(
            IdMap<T> registry, PalettedContainer.Strategy strategy, PalettedContainerRO.PackedData<T> packedData) {
        throw new AssertionError("mixin not applied");
    }
}
