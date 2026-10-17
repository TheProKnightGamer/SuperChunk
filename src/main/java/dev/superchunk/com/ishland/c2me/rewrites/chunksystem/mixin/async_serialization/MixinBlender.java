package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.ProtoChunkExtension;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Blender.class)
public class MixinBlender {

    @Redirect(method = "of", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/WorldGenRegion;isOldChunkAround(Lnet/minecraft/world/level/ChunkPos;I)Z"), require = 0)
    private static boolean redirectNeedsBlending(WorldGenRegion instance, ChunkPos chunkPos, int checkRadius) {
        return ((ProtoChunkExtension) instance.getChunk(chunkPos.x, chunkPos.z)).getNeedBlending();
    }

}
