package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import dev.superchunk.com.ishland.c2me.base.common.theinterface.IFastChunkHolder;
import dev.superchunk.com.ishland.c2me.base.common.util.FilteringIterable;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkTicketManager;
import dev.superchunk.com.ishland.c2me.base.mixin.access.ISimulationDistanceLevelPropagator;
import dev.superchunk.com.ishland.c2me.notickvd.common.IChunkHolder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 1.20.1 port (plan §3.3). The getTickingChunk redirect puts border (FULL, no-tick) chunks into
 * tickChunks' ChunkAndHolder list; block-tick/spawn logic stays gated by vanilla
 * ({@code level.shouldTickBlocksAt}, spawn checks) while {@code holder.broadcastChanges(chunk)}
 * now reaches no-tick chunks (pairs with {@link MixinChunkHolder}). The neo
 * {@code IFastChunkHolder} branch is KEPT (Wave C ported the chunk system — holders are
 * NewChunkHolderVanillaInterface when it is enabled); the fallback goes through the
 * {@link IChunkHolder} duck merged by {@link MixinChunkHolder}. The getAllEntities wrap keeps
 * mobcap/despawn accounting scoped to simulation-distance chunks.
 */
@Mixin(ServerChunkCache.class)
public class MixinServerChunkManager {

    @Shadow @Final private DistanceManager distanceManager;

    @Redirect(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"))
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
            return ((IChunkHolder) instance).getAccessibleChunk();
        }
    }

    @WrapOperation(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getAllEntities()Ljava/lang/Iterable;"))
    private Iterable<Entity> redirectIterateEntities(ServerLevel serverWorld, Operation<Iterable<Entity>> op) {
        Long2ByteMap trackedChunks = ((ISimulationDistanceLevelPropagator) ((IChunkTicketManager) this.distanceManager).getSimulationDistanceTracker()).getLevels();
        return new FilteringIterable<>(op.call(serverWorld), entity -> trackedChunks.containsKey(entity.chunkPosition().toLong()));
    }

}
