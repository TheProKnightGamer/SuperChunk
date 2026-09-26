package dev.superchunk.com.ishland.c2me.notickvd.mixin.servercore;

import com.bawnorton.mixinsquared.TargetHandler;
import dev.superchunk.com.ishland.c2me.base.common.theinterface.IFastChunkHolder;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkManager {

    @TargetHandler(
            mixin = "me.wesley1808.servercore.mixin.optimizations.ticking.chunk.broadcast.ServerChunkCacheMixin",
            name = "servercore$broadcastChanges"
    )
    @Redirect(method = "@MixinSquared:Handler", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"), require = 0)
    private LevelChunk includeAccessibleChunks(ChunkHolder instance) {
        if (instance instanceof IFastChunkHolder fastChunkHolder) {
            // SuperChunk: a FULL chunk outside the ticking range is listed only so its pending
            // block/light changes get broadcast (vanilla lists ticking chunks only, and the spawn
            // and block-tick steps refuse non-ticking ones anyway). Listing every FULL chunk cost a
            // shuffle draw, an allocation and map lookups per chunk per tick (~1.7 ms/tick at view
            // distance 44). A change made to one during this tick goes out next tick.
            if (!fastChunkHolder.c2me$blockTicking()
                    && !((dev.superchunk.com.ishland.c2me.notickvd.common.IPendingBroadcast) instance).superchunk$hasPendingBroadcast()) {
                return null;
            }
            return fastChunkHolder.c2me$immediateWorldChunk();
        } else {
            return instance.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
        }
    }

}
