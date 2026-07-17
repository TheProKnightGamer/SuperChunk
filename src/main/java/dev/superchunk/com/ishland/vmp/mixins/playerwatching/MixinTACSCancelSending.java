package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Ported from VMP playerwatching.MixinTACSCancelSending.
 *
 * <p>VMP (Yarn) no-ops ServerChunkLoadingManager.sendWatchPackets(...) and redirects the
 * mass-resend triggers so that chunk sending is fully driven by the area distance map.
 *
 * <p>Mojmap consolidates the entire watch-packet path into a single chokepoint:
 * {@code ChunkMap.applyChunkTrackingView(ServerPlayer, ChunkTrackingView)} — it sends the
 * chunk-cache-center packet, runs ChunkTrackingView.difference (markChunkPendingToSend /
 * dropChunk) and stores the player's ChunkTrackingView. No-oping it here cancels all vanilla
 * sending; {@link MixinThreadedAnvilChunkStorage} (via AreaPlayerChunkWatchingManager) then
 * owns both the chunk send/drop and the ChunkTrackingView/center-packet state.
 */
@Mixin(value = ChunkMap.class, priority = 1005)
public class MixinTACSCancelSending {

    /**
     * @author ishland
     * @reason Stop packet sending, handled by distance map
     */
    @Overwrite
    private void applyChunkTrackingView(ServerPlayer player, ChunkTrackingView chunkTrackingView) {
        // no-op
    }

}
