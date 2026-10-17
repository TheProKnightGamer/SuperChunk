package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.SchedulingManager;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorageTicketManager;
import dev.superchunk.com.ishland.c2me.notickvd.common.ChunkTicketManagerExtension;
import dev.superchunk.com.ishland.c2me.notickvd.common.NoTickSystem;
import dev.superchunk.config.PlayerLatency;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.20.1 port of the neo mixin — all injection targets verified identical on 1.20.1
 * (plan §3.3). The {@code player.priorityBias} (P2) blocks are KEPT (plan drop REVERSED): the
 * plan predates Wave C, which ported the full c2me chunk system including the per-world
 * {@code SchedulingManager} the band lives in — the neo implementation ports cleanly.
 */
@Mixin(DistanceManager.class)
public class MixinChunkTicketManager implements ChunkTicketManagerExtension {

    @Shadow private long ticketTickCounter;

    @Shadow @Final private DistanceManager.PlayerTicketTracker playerTicketManager;

    @Unique
    private NoTickSystem noTickSystem;

    /**
     * SuperChunk player.priorityBias (P2): the per-world SchedulingManager whose player-proximity
     * priority band is fed from the same player-source hooks below. Resolved lazily (first player
     * event, long after both ChunkMap and this DistanceManager finished constructing) rather than
     * in {@link #onInit} to stay clear of mixin field-initializer ordering during ChunkMap's ctor.
     */
    @Unique
    private SchedulingManager superchunk$schedulingManager;

    @Unique
    private SchedulingManager superchunk$schedulingManager() {
        SchedulingManager manager = this.superchunk$schedulingManager;
        if (manager == null) {
            manager = ((IVanillaChunkManager) ((IThreadedAnvilChunkStorageTicketManager) this).c2me$getSuperClass()).c2me$getSchedulingManager();
            this.superchunk$schedulingManager = manager;
        }
        return manager;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        this.noTickSystem = new NoTickSystem(((IThreadedAnvilChunkStorageTicketManager) this).c2me$getSuperClass());
    }

    // These two injections are the player section-crossing observers: vanilla calls
    // DistanceManager.addPlayer/removePlayer from ChunkMap.updatePlayerStatus (join/leave) and
    // ChunkMap.move (remove old section + add new section whenever a player crosses a section
    // boundary). notickvd feeds them to PlayerNoTickLoader; player.priorityBias additionally
    // feeds the same chunk positions to the SchedulingManager's priority band (which hops to the
    // c2me-sched thread itself, exactly like sync-load updates).
    // 1.20.1 addPlayer body: `naturalSpawnChunkCounter.update(i, 0, true)` (owner
    // FixedPlayerDistanceChunkTracker — THE ordinal-0 match) then `playerTicketManager.update(...)`
    // (owner PlayerTicketTracker — different owner, not matched). Same for removePlayer.

    @Inject(method = "addPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;update(JIZ)V", ordinal = 0, shift = At.Shift.AFTER))
    private void onHandleChunkEnter(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
        this.noTickSystem.addPlayerSource(pos.chunk());
        if (PlayerLatency.PRIORITY_BIAS) {
            this.superchunk$schedulingManager().addPlayerSource(pos.chunk().toLong());
        }
    }

    @Inject(method = "removePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;update(JIZ)V", ordinal = 0, shift = At.Shift.AFTER))
    private void onHandleChunkLeave(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
        this.noTickSystem.removePlayerSource(pos.chunk());
        if (PlayerLatency.PRIORITY_BIAS) {
            this.superchunk$schedulingManager().removePlayerSource(pos.chunk().toLong());
        }
    }

    @Inject(method = "purgeStaleTickets", at = @At("RETURN"))
    private void onPurge(CallbackInfo ci) {
        this.noTickSystem.runPurge(this.ticketTickCounter);
    }

    @Inject(method = "runAllUpdates", at = @At("HEAD"))
    private void beforeTick(ChunkMap chunkStorage, CallbackInfoReturnable<Boolean> cir) {
        this.noTickSystem.beforeTicketTicks();
    }

    @Inject(method = "runAllUpdates", at = @At("RETURN"))
    private void onTick(ChunkMap chunkStorage, CallbackInfoReturnable<Boolean> cir) {
        this.noTickSystem.afterTicketTicks();
        this.noTickSystem.tick();
    }

    @Inject(method = "updateSimulationDistance", at = @At("HEAD"))
    public void mapSimulationDistance(int simulationDistance, CallbackInfo ci) {
        this.playerTicketManager.updateViewDistance(simulationDistance);
    }

    /**
     * @author ishland
     * @reason remap setWatchDistance to no-tick one
     */
    @Overwrite
    public void updatePlayerTickets(int viewDistance) {
        this.noTickSystem.setNoTickViewDistance(viewDistance + 1);
        if (PlayerLatency.PRIORITY_BIAS) {
            // Keep the priority band's radius in lockstep with the no-tick view distance
            // (viewDistance + 1, same value the PlayerNoTickLoader targets); the band adds its
            // own +8 margin internally.
            this.superchunk$schedulingManager().setPlayerPriorityViewDistance(viewDistance + 1);
        }
    }

    @Override
    @Unique
    public long c2me$getPendingLoadsCount() {
        return this.noTickSystem.getPendingLoadsCount();
    }

    /**
     * SuperChunk player.predictiveGen: hands the per-world NoTickSystem to the predictive
     * tracker (see MixinChunkMapPredictiveGen), which feeds corridor updates through the same
     * pending-actions queue the player-source hooks above use.
     */
    @Override
    @Unique
    public NoTickSystem superchunk$getNoTickSystem() {
        return this.noTickSystem;
    }

    @Override
    public void c2me$closeNoTickVD() {
        this.noTickSystem.close();
    }
}
