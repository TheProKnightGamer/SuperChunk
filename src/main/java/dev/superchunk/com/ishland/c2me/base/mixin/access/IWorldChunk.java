package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunk.class)
public interface IWorldChunk {

    @Accessor("loaded")
    boolean isLoadedToWorld();

}
