package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.DistanceManager.class)
public interface IThreadedAnvilChunkStorageTicketManager {

    @Accessor("this$0")
    ChunkMap c2me$getSuperClass();

}
