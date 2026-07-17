package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization.gc_free_serializer;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.AsyncSerializationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * Redirects the block-entity collection calls inside the gc-free
 * {@link dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.ChunkDataSerializer#write} so that the
 * async-serialization snapshot (captured off-thread) is used when present.
 */
@Pseudo
@Mixin(targets = "dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.ChunkDataSerializer", remap = false)
public class MixinChunkDataSerializer {

    @Redirect(
            method = "Ldev/superchunk/com/ishland/c2me/rewrites/chunk_serializer/common/ChunkDataSerializer;write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Ldev/superchunk/com/ishland/c2me/rewrites/chunk_serializer/common/NbtWriter;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockEntitiesPos()Ljava/util/Set;"),
            require = 0
    )
    private static Set<BlockPos> onChunkGetBlockEntityPositions(ChunkAccess chunk) {
        final AsyncSerializationManager.Scope scope = AsyncSerializationManager.getScope(chunk.getPos());
        return scope != null ? scope.blockEntities.keySet() : chunk.getBlockEntitiesPos();
    }

    @Redirect(
            method = "Ldev/superchunk/com/ishland/c2me/rewrites/chunk_serializer/common/ChunkDataSerializer;write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Ldev/superchunk/com/ishland/c2me/rewrites/chunk_serializer/common/NbtWriter;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockEntityNbtForSaving(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"),
            require = 0
    )
    private static CompoundTag onChunkGetPackedBlockEntityNbt(ChunkAccess chunk, BlockPos pos, HolderLookup.Provider registries) {
        final AsyncSerializationManager.Scope scope = AsyncSerializationManager.getScope(chunk.getPos());
        if (scope == null) return chunk.getBlockEntityNbtForSaving(pos, registries);
        return scope.blockEntities.get(pos);
    }

}
