package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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

    /**
     * Port of upstream VMP's {@code redirectChunkFilterSet} (Yarn:
     * {@code @Redirect(method = "handlePlayerAddedOrRemoved", target = "ServerPlayerEntity.setChunkFilter")}),
     * which the Yarn-&gt;Mojmap translation of this class dropped. Mojmap's
     * {@code ChunkMap.updatePlayerStatus} is Yarn's {@code handlePlayerAddedOrRemoved}, and its
     * {@code player.setChunkTrackingView(ChunkTrackingView.EMPTY)} is the exact call upstream
     * redirects. There is only one such INVOKE in the method (the removal branch goes through
     * {@code applyChunkTrackingView}, overwritten above), so this is unambiguous.
     *
     * <p><b>Why it matters.</b> Vanilla writes the tracking view in exactly two places:
     * this EMPTY reset and the tail of {@code applyChunkTrackingView}. With
     * {@code applyChunkTrackingView} no-oped and this reset live, a joining player was left on
     * {@link ChunkTrackingView#EMPTY}: {@link MixinThreadedAnvilChunkStorage}'s HEAD injection
     * sets the real positioned view, vanilla then clobbers it, and nothing restores it.
     *
     * <p>That is a silent chunk sink. {@code ChunkMap.onChunkReadyToSend} — the edge-triggered
     * hook SuperChunk's async chunk system pushes completed chunks through — tests
     * {@code player.getChunkTrackingView().contains(pos)} and drops every chunk against an EMPTY
     * view. It never fires again for an already-accessible chunk, and VMP's lazy repair in
     * {@code AreaPlayerChunkWatchingManager.tick()} sends nothing to make up for it: it
     * re-updates the area map at the SAME centre and view distance, so no chunk enters it and no
     * send listener fires. Every chunk completed inside the window is lost until the player walks
     * far enough to re-enter it. Normally that window is under one tick; ModernFix's
     * {@code mixin.perf.suspend_integrated_server_during_load} (ON by default) skips
     * {@code MinecraftServer#tickServer} until the client acks its join, stretching it across the
     * whole singleplayer world load and turning the race into reproducible holes around spawn.
     *
     * <p>Redirecting the reset away — rather than compensating for it afterwards — leaves the
     * HEAD injection's view in place, so vanilla's own {@code updateChunkTracking} sees a
     * {@code Positioned} view at the right centre and distance and early-returns. One write per
     * join instead of three, and it matches upstream.
     */
    @Redirect(method = "updatePlayerStatus",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;setChunkTrackingView(Lnet/minecraft/server/level/ChunkTrackingView;)V"))
    private void redirectChunkTrackingViewSet(ServerPlayer instance, ChunkTrackingView view) {
        // no-op — the area map owns the view (upstream VMP redirectChunkFilterSet)
    }

}
