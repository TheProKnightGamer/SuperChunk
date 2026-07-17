package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.AsyncSerializationManager;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.ChunkIoMainThreadTaskUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

@Mixin(ChunkSerializer.class)
public class MixinChunkSerializer {

    @Shadow @Final private static Logger LOGGER;

    @Redirect(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;checkConsistencyWithBlocks(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/LevelChunkSection;)V"), require = 0)
    private static void onPoiStorageInitForPalette(PoiManager instance, SectionPos sectionPos, LevelChunkSection chunkSection) {
        ChunkIoMainThreadTaskUtils.executeMain(() -> instance.checkConsistencyWithBlocks(sectionPos, chunkSection));
    }

    @Redirect(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockEntitiesPos()Ljava/util/Set;"), require = 0)
    private static Set<BlockPos> onChunkGetBlockEntityPositions(ChunkAccess chunk) {
        final AsyncSerializationManager.Scope scope = AsyncSerializationManager.getScope(chunk.getPos());
        return scope != null ? scope.blockEntities.keySet() : chunk.getBlockEntitiesPos();
    }

    @Redirect(method = "write", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockEntityNbtForSaving(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"), require = 0)
    private static CompoundTag onChunkGetPackedBlockEntityNbt(ChunkAccess chunk, BlockPos pos, HolderLookup.Provider wrapperLookup) {
        final AsyncSerializationManager.Scope scope = AsyncSerializationManager.getScope(chunk.getPos());
        if (scope == null) return chunk.getBlockEntityNbtForSaving(pos, wrapperLookup);
        return scope.blockEntities.get(pos);
    }

}
