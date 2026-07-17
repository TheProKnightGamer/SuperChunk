package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import com.google.common.collect.ImmutableList;
import dev.superchunk.com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager;
import dev.superchunk.com.ishland.vmp.common.playerwatching.TACSExtension;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * VMP's area-based player-watching / chunk-sending rewrite, translated from Yarn
 * ServerChunkLoadingManager to Mojmap {@link ChunkMap}.
 *
 * <p>Yarn -> Mojmap mapping for this mixin:
 * <ul>
 *   <li>track(player, ChunkPos)            -> markChunkPendingToSend(player, ChunkPos) (private)</li>
 *   <li>untrack(player, ChunkPos) [static] -> dropChunk(player, ChunkPos) (private static)</li>
 *   <li>updateWatchedSection(player)       -> updatePlayerPos(player)  (sets last section pos)</li>
 *   <li>getViewDistance(player)            -> getPlayerViewDistance(player)</li>
 *   <li>watchDistance                      -> serverViewDistance</li>
 *   <li>setViewDistance(I)                 -> setServerViewDistance(I)</li>
 *   <li>getPlayersWatchingChunk(pos, edge) -> getPlayers(pos, edge)  [@Overwrite]</li>
 *   <li>handlePlayerAddedOrRemoved         -> updatePlayerStatus(player, added)</li>
 *   <li>updatePosition (@ updateWatchedSection) -> move (@ updateChunkTracking)</li>
 *   <li>player.getWatchedSection()         -> player.getLastSectionPos()</li>
 *   <li>ChunkSectionPos.toChunkPos()       -> SectionPos.chunk()</li>
 *   <li>ChunkSectionPos.getSectionX/Z()    -> SectionPos.x()/z()</li>
 *   <li>networkHandler.sendPacket(ChunkRenderDistanceCenterS2CPacket) ->
 *       connection.send(ClientboundSetChunkCacheCenterPacket)</li>
 *   <li>setChunkFilter(ChunkFilter.cylindrical(...)) -> setChunkTrackingView(ChunkTrackingView.of(...))</li>
 *   <li>chunkDataSender.isInNextBatch(l)   -> connection.chunkSender.isPending(l)</li>
 * </ul>
 *
 * <p>KNOWN GAP (doc corrected 2026-07-02 — the previous claim here was factually wrong): the Yarn
 * single-arg {@code getPlayersWatchingChunk(ChunkPos)} and {@code shouldTick(ChunkPos)} overwrites
 * are NOT ported, but their Mojmap targets DO exist on ChunkMap:
 * {@code anyPlayerCloseEnoughForSpawning(ChunkPos)} (= shouldTick) and
 * {@code getPlayersCloseForSpawning(ChunkPos)} (= single-arg getPlayersWatchingChunk) — both
 * iterate PlayerMap.getAllPlayers (every player in the dimension) per ticking chunk per tick.
 * Restoring VMP's O(nearby) lookups requires re-adding the general-player area map (see
 * AreaPlayerChunkWatchingManager) and porting the two overwrites
 * (ticketManager.shouldTick -> distanceManager.hasPlayersNearby, canTickChunk ->
 * playerIsCloseEnoughForSpawning). Until then only the watching/sending feature is ported;
 * spawn-proximity checks run vanilla O(players).
 *
 * <p>The actual cancellation of vanilla chunk sending is performed by
 * {@link MixinTACSCancelSending} (no-ops ChunkMap.applyChunkTrackingView), so the area map below
 * is the sole driver of chunk send/drop and ChunkTrackingView state.
 */
@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage implements TACSExtension {

    @Shadow private int serverViewDistance;

    @Shadow @Final private static Logger LOGGER;

    @Shadow protected abstract void updatePlayerPos(ServerPlayer player);

    @Shadow abstract int getPlayerViewDistance(ServerPlayer player);

    @Shadow protected abstract void markChunkPendingToSend(ServerPlayer player, ChunkPos pos);

    @Shadow private static void dropChunk(ServerPlayer player, ChunkPos pos) {
        throw new AbstractMethodError();
    }

    @Unique
    private AreaPlayerChunkWatchingManager areaPlayerChunkWatchingManager;

    @Override
    public AreaPlayerChunkWatchingManager getAreaPlayerChunkWatchingManager() {
        return this.areaPlayerChunkWatchingManager;
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;setServerViewDistance(I)V"))
    private void redirectNewPlayerChunkWatchingManager(CallbackInfo ci) {
        this.areaPlayerChunkWatchingManager = new AreaPlayerChunkWatchingManager(
                (player, chunkX, chunkZ) -> this.markChunkPendingToSend(player, new ChunkPos(chunkX, chunkZ)),
                (player, chunkX, chunkZ) -> dropChunk(player, new ChunkPos(chunkX, chunkZ)),
                (ChunkMap) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void onTick(CallbackInfo ci) {
        areaPlayerChunkWatchingManager.tick();
    }

    @Inject(method = "setServerViewDistance", at = @At("RETURN"))
    private void onSetViewDistance(CallbackInfo ci) {
        // VMP Config.SHOW_CHUNK_TRACKING_MESSAGES defaults to true.
        LOGGER.info("Changing watch distance to {}", this.serverViewDistance);
        areaPlayerChunkWatchingManager.setWatchDistance(this.serverViewDistance);
    }

    /**
     * @author ishland
     * @reason use array for iteration & use squares as cylinders are expensive
     */
    @Overwrite
    public List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge) {
        final AreaPlayerChunkWatchingManager watchingManager = this.areaPlayerChunkWatchingManager;

        Object[] set = watchingManager.getPlayersWatchingChunkArray(chunkPos.toLong());
        ImmutableList.Builder<ServerPlayer> builder = ImmutableList.builder();

        for (Object __player : set) {
            if (__player instanceof ServerPlayer serverPlayer) {
                SectionPos watchedPos = serverPlayer.getLastSectionPos();
                int chebyshevDistance = Math.max(Math.abs(watchedPos.x() - chunkPos.x), Math.abs(watchedPos.z() - chunkPos.z));
                if (chebyshevDistance > this.serverViewDistance) {
                    continue;
                }
                if (!serverPlayer.connection.chunkSender.isPending(chunkPos.toLong()) &&
                    (!onlyOnWatchDistanceEdge || chebyshevDistance == this.serverViewDistance)) {
                    builder.add(serverPlayer);
                }
            }
        }

        return builder.build();
    }

    @Inject(method = "updatePlayerStatus", at = @At("HEAD"))
    private void onHandlePlayerAddedOrRemoved(ServerPlayer player, boolean added, CallbackInfo ci) {
        if (added) {
            this.vmp$updateWatchedSection(player);
            this.areaPlayerChunkWatchingManager.add(player, player.getLastSectionPos().chunk().toLong());
        } else {
            this.areaPlayerChunkWatchingManager.remove(player);
        }
    }

    @Inject(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void onPlayerSectionChange(ServerPlayer player, CallbackInfo ci) {
        this.vmp$updateWatchedSection(player);
        this.areaPlayerChunkWatchingManager.movePlayer(player.getLastSectionPos().chunk().toLong(), player);
    }

    @Unique
    private void vmp$updateWatchedSection(ServerPlayer player) {
        this.updatePlayerPos(player);
        SectionPos sectionPos = player.getLastSectionPos();
        player.connection.send(new ClientboundSetChunkCacheCenterPacket(sectionPos.x(), sectionPos.z()));
        player.setChunkTrackingView(ChunkTrackingView.of(sectionPos.chunk(), this.getPlayerViewDistance(player)));
    }

}
