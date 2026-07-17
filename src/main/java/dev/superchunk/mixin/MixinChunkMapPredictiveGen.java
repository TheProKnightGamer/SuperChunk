package dev.superchunk.mixin;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
import dev.superchunk.com.ishland.c2me.notickvd.common.ChunkTicketManagerExtension;
import dev.superchunk.config.PredictiveGen;
import dev.superchunk.predictive.PredictiveGenTracker;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SuperChunk {@code player.predictiveGen} — the once-per-tick server hook that drives the
 * per-player velocity tracker.
 *
 * <p>{@code ChunkMap.tick()} (the no-arg overload, called once per server tick per world from
 * {@code ServerChunkCache.tick} — the same vanilla player-update path VMP's playerwatching area
 * map hooks) is where {@link PredictiveGenTracker} measures every player's horizontal position
 * delta and, for players above the speed threshold, publishes corridor updates to the world's
 * notickvd {@code NoTickSystem} and predicted positions to the P2 priority band. The explicit
 * {@code tick()V} descriptor keeps this off the {@code tick(BooleanSupplier)} unload overload.
 *
 * <p>Flag OFF (default): one static-final boolean check, nothing allocates, byte-identical
 * behavior. The tracker is created lazily on the first ticked tick so construction happens long
 * after both the {@code DistanceManager} (notickvd {@code NoTickSystem}) and the
 * {@code SchedulingManager} (c2me base mixin field) exist.
 */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMapPredictiveGen {

    @Shadow
    @Final
    public ServerLevel level;

    @Unique
    private PredictiveGenTracker superchunk$predictiveTracker;

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void superchunk$predictiveTick(CallbackInfo ci) {
        if (!PredictiveGen.ENABLED) return;
        PredictiveGenTracker tracker = this.superchunk$predictiveTracker;
        if (tracker == null) {
            final ChunkMap self = (ChunkMap) (Object) this;
            tracker = new PredictiveGenTracker(
                    (ChunkTicketManagerExtension) self.getDistanceManager(),
                    ((IVanillaChunkManager) this).c2me$getSchedulingManager());
            this.superchunk$predictiveTracker = tracker;
        }
        tracker.tick(this.level.players());
    }
}
