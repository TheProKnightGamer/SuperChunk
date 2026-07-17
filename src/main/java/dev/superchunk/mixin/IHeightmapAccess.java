package dev.superchunk.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Predicate;

/** Accessor for {@link Heightmap} privates used by {@code MixinHeightmapPrimeFast}. */
@Mixin(Heightmap.class)
public interface IHeightmapAccess {

    @Accessor("isOpaque")
    Predicate<BlockState> superchunk$isOpaque();

    @Invoker("setHeight")
    void superchunk$setHeight(int x, int z, int height);
}
