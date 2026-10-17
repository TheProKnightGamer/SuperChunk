package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import com.google.common.collect.ImmutableList;
import dev.superchunk.com.ishland.vmp.common.chunkwatching.AreaPlayerChunkWatchingManager;
import dev.superchunk.com.ishland.vmp.common.playerwatching.TACSExtension;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.mutable.MutableObject;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

/**
 * VMP's area-based player-watching / chunk-sending rewrite, re-based for Forge 1.20.1
 * (portplans/vmp-notickvd-player.md §2.5). The neo (1.21.1) shape was built on the
 * {@code ChunkTrackingView}/{@code applyChunkTrackingView} chokepoint; 1.20.1 predates it, so
 * this port returns to the upstream-VMP-for-1.19/1.20 shape: {@code updatePlayerStatus} and
 * {@code move} are overwritten wholesale (vanilla bodies copied verbatim from the Forge-patched
 * sources, MINUS the per-chunk {@code updateChunkTracking} loops), and the area map drives
 * send/drop through {@code updateChunkTracking} — which keeps the Forge
 * fireChunkWatch/fireChunkUnWatch events flowing.
 *
 * <p>KNOWN GAP (carried from neo): spawn-proximity checks
 * ({@code anyPlayerCloseEnoughForSpawning}/{@code getPlayersCloseForSpawning}) stay vanilla
 * O(players) — see AreaPlayerChunkWatchingManager javadoc.
 *
 * <p>Kill-switch: {@code -Dsuperchunk.vmp.enabled=false} (gated in SuperChunkMixinPlugin)
 * restores full vanilla watching.
 */
@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage implements TACSExtension {

    @Shadow int viewDistance;

    @Shadow @Final private static Logger LOGGER;

    @Shadow @Final private PlayerMap playerMap;

    @Shadow @Final private Int2ObjectMap<ChunkMap.TrackedEntity> entityMap;

    @Shadow @Final private ChunkMap.DistanceManager distanceManager;

    @Shadow @Final ServerLevel level;

    @Shadow protected abstract SectionPos updatePlayerPos(ServerPlayer player);

    @Shadow protected abstract boolean skipPlayer(ServerPlayer player);

    @Shadow protected abstract void updateChunkTracking(ServerPlayer player, ChunkPos pos,
            MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, boolean wasLoaded, boolean load);

    @Unique
    private AreaPlayerChunkWatchingManager areaPlayerChunkWatchingManager;

    @Override
    public AreaPlayerChunkWatchingManager getAreaPlayerChunkWatchingManager() {
        return this.areaPlayerChunkWatchingManager;
    }

    // TAIL, not upstream's pre-setViewDistance INVOKE point: runtime mixin 0.8.5 rejects
    // mid-constructor @Inject points ("Found @Inject targetting a constructor"). The
    // setViewDistance RETURN hook below is null-guarded for the in-ctor call this ordering
    // skips, and the initial watch distance is applied here instead — same end state.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void redirectNewPlayerChunkWatchingManager(CallbackInfo ci) {
        // The listeners are the 1.20.1 equivalents of neo's markChunkPendingToSend/dropChunk:
        // going through updateChunkTracking keeps trackChunk/untrackChunk + the Forge
        // ChunkWatch/ChunkUnWatch events flowing.
        this.areaPlayerChunkWatchingManager = new AreaPlayerChunkWatchingManager(
                (player, chunkX, chunkZ) -> this.updateChunkTracking(player, new ChunkPos(chunkX, chunkZ), new MutableObject<>(), false, true),
                (player, chunkX, chunkZ) -> this.updateChunkTracking(player, new ChunkPos(chunkX, chunkZ), new MutableObject<>(), true, false),
                (ChunkMap) (Object) this);
        this.areaPlayerChunkWatchingManager.setWatchDistance(this.viewDistance);
    }

    // EXPLICIT tick()V descriptor — tick(Ljava/util/function/BooleanSupplier;)V is the unload path.
    @Inject(method = "tick()V", at = @At("RETURN"))
    private void onTick(CallbackInfo ci) {
        this.areaPlayerChunkWatchingManager.tick();
    }

    @Inject(method = "setViewDistance(I)V", at = @At("RETURN"))
    private void onSetViewDistance(CallbackInfo ci) {
        if (this.areaPlayerChunkWatchingManager == null) {
            // The in-ctor setViewDistance call, before the TAIL inject above constructed the
            // manager — that inject applies the initial watch distance itself.
            return;
        }
        // VMP Config.SHOW_CHUNK_TRACKING_MESSAGES defaults to true.
        LOGGER.info("Changing watch distance to {}", this.viewDistance);
        this.areaPlayerChunkWatchingManager.setWatchDistance(this.viewDistance);
    }

    /**
     * Kills the vanilla mass-resend loop in setViewDistance — the area map handles resend/drop
     * via setWatchDistance. (The getPlayers call is directly in setViewDistance, not a lambda.)
     */
    @Redirect(method = "setViewDistance(I)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;"))
    private List<ServerPlayer> noVanillaViewDistanceResend(ChunkMap self, ChunkPos pos, boolean edgeOnly) {
        return Collections.emptyList();
    }

    /**
     * @author ishland
     * @reason use array for iteration &amp; use squares as cylinders are expensive
     */
    @Overwrite
    public List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge) {
        // NOTE: no chunkSender.isPending clause — the API is 1.20.2+; 1.20.1 has no pending set.
        final Object[] set = this.areaPlayerChunkWatchingManager.getPlayersWatchingChunkArray(chunkPos.toLong());
        final ImmutableList.Builder<ServerPlayer> builder = ImmutableList.builder();
        for (Object __player : set) {
            if (__player instanceof ServerPlayer serverPlayer) {
                final SectionPos watched = serverPlayer.getLastSectionPos();
                final int cheb = Math.max(Math.abs(watched.x() - chunkPos.x), Math.abs(watched.z() - chunkPos.z));
                if (cheb > this.viewDistance) continue;
                if (!onlyOnWatchDistanceEdge || cheb == this.viewDistance) builder.add(serverPlayer);
            }
        }
        return builder.build();
    }

    /**
     * @author ishland
     * @reason area-map-driven chunk watching (vanilla body verbatim MINUS the trailing per-chunk
     * updateChunkTracking double loop, PLUS area-map registration)
     */
    @Overwrite
    void updatePlayerStatus(ServerPlayer player, boolean added) {
        final boolean skip = this.skipPlayer(player);
        final boolean ignoredOrUnknown = this.playerMap.ignoredOrUnknown(player);
        final int i = SectionPos.blockToSectionCoord(player.getBlockX());
        final int j = SectionPos.blockToSectionCoord(player.getBlockZ());
        if (added) {
            this.playerMap.addPlayer(ChunkPos.asLong(i, j), player, skip);
            this.updatePlayerPos(player); // sets lastSectionPos + sends cache-center packet
            if (!skip) {
                this.distanceManager.addPlayer(SectionPos.of(player), player);
            }
            this.areaPlayerChunkWatchingManager.add(player, player.getLastSectionPos().chunk().toLong());
        } else {
            final SectionPos sectionpos = player.getLastSectionPos();
            this.playerMap.removePlayer(sectionpos.chunk().toLong(), player);
            if (!ignoredOrUnknown) {
                this.distanceManager.removePlayer(sectionpos, player);
            }
            this.areaPlayerChunkWatchingManager.remove(player);
        }
    }

    /**
     * @author ishland
     * @reason area-map-driven chunk watching (vanilla body verbatim with BOTH per-chunk
     * updateChunkTracking loop blocks removed; area map updated inside vanilla's
     * section-change/skip-flip guard, matching neo's "on section change" semantics)
     */
    @Overwrite
    public void move(ServerPlayer player) {
        for (ChunkMap.TrackedEntity trackedEntity : this.entityMap.values()) {
            if (trackedEntity.entity == player) { // TrackedEntity.entity: AT'd public (f_140472_)
                trackedEntity.updatePlayers(this.level.players());
            } else {
                trackedEntity.updatePlayer(player);
            }
        }

        final SectionPos sectionpos = player.getLastSectionPos();
        final SectionPos sectionpos1 = SectionPos.of(player);
        final long i = sectionpos.chunk().toLong();
        final long j = sectionpos1.chunk().toLong();
        final boolean flag = this.playerMap.ignored(player);
        final boolean flag1 = this.skipPlayer(player);
        final boolean flag2 = sectionpos.asLong() != sectionpos1.asLong();
        if (flag2 || flag != flag1) {
            this.updatePlayerPos(player);
            if (!flag) {
                this.distanceManager.removePlayer(sectionpos, player);
            }

            if (!flag1) {
                this.distanceManager.addPlayer(sectionpos1, player);
            }

            if (!flag && flag1) {
                this.playerMap.ignorePlayer(player);
            }

            if (flag && !flag1) {
                this.playerMap.unIgnorePlayer(player);
            }

            if (i != j) {
                this.playerMap.updatePlayer(i, j, player);
            }

            this.areaPlayerChunkWatchingManager.movePlayer(player.getLastSectionPos().chunk().toLong(), player);
        }
    }

}
