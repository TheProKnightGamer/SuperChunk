package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunkSection.class)
public interface IChunkSection {
    @Accessor("states")
    PalettedContainer<BlockState> getBlockStateContainer();

    @Accessor("biomes")
    PalettedContainerRO<Holder<Biome>> getBiomeContainer();
}
