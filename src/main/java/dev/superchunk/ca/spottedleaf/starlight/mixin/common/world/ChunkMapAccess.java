package dev.superchunk.ca.spottedleaf.starlight.mixin.common.world;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SuperChunk (Forge 1.20.1): Starlight calls these protected {@code ChunkMap} methods. An access
 * transformer that made them public would crash any mod that {@code @Overwrite}s one as protected
 * ("cannot reduce visibiliy", GitHub #31), so they are reached through invokers instead.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder superchunk$getVisibleChunkIfPresent(long pos);

    @Invoker("releaseLightTicket")
    void superchunk$releaseLightTicket(ChunkPos pos);
}
