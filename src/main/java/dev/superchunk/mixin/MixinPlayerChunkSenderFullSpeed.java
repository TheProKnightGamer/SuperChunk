package dev.superchunk.mixin;

import dev.superchunk.config.FullSpeedLoading;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SuperChunk {@code player.fullSpeedLoading} — lifts vanilla {@link PlayerChunkSender}'s
 * server-side send-rate self-throttle (the "radius shrink" a fast-flying player sees).
 *
 * <p><b>What vanilla does.</b> {@code sendNextChunks} (once per server tick per player, from
 * MinecraftServer's "send chunks" phase) refills {@code batchQuota} by
 * {@code desiredChunksPerTick}, capped at {@code max(1, desiredChunksPerTick)}.
 * {@code desiredChunksPerTick} starts at 9 and is REPLACED on every batch ack
 * ({@code onChunkBatchReceivedByClient}) with the client-reported value clamped to
 * [0.01, 64] (NaN &rarr; 0.01); when the in-flight batch count drains to 0, the quota is also
 * reset to 1 (slow-start). A client that is busy meshing terrain during fast flight reports
 * tiny values, so the server shrinks its own send rate exactly when the player needs the most
 * chunks — the loaded ring collapses behind the flight path.
 *
 * <p><b>What this mixin does when the toggle is ON</b> (pure no-op when OFF — default):
 * <ul>
 *   <li>{@link #superchunk$pinSendBudget}: pins {@code desiredChunksPerTick} to
 *       {@link FullSpeedLoading#CHUNKS_PER_TICK} at the top of every {@code sendNextChunks}.
 *       One pin point neutralizes ALL three rate reducers — the slow-ack shrink, the NaN
 *       degradation, and the post-drain slow-start (the very next refill is
 *       {@code min(1 + pinned, pinned) = pinned}, i.e. full budget again in one tick).</li>
 *   <li>{@link #superchunk$widenAckWindow}: raise-only widening of the unacked-batch window
 *       (vanilla hard-codes 10 after the first ack). Left at vanilla 10 by default.</li>
 * </ul>
 *
 * <p><b>Deliberately kept:</b> the {@code unacknowledgedBatches < maxUnacknowledgedBatches}
 * gate itself (and the pre-first-ack window of 1). That window is genuine CLIENT backpressure:
 * it is what stops the server from burying a slow client under an unbounded packet backlog.
 * This toggle removes SERVER-side self-throttling only — a slow client still decodes, renders
 * and acks batches at its own pace, and sends pause while the window is full.
 */
@Mixin(PlayerChunkSender.class)
public abstract class MixinPlayerChunkSenderFullSpeed {

    @Shadow
    private float desiredChunksPerTick;

    @Shadow
    private int maxUnacknowledgedBatches;

    @Inject(method = "sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 0)
    private void superchunk$pinSendBudget(ServerPlayer player, CallbackInfo ci) {
        if (FullSpeedLoading.ENABLED) {
            // Overwrite whatever the last ack shrank this to. Runs before the quota refill, so
            // the refill this tick already uses the pinned value.
            this.desiredChunksPerTick = FullSpeedLoading.CHUNKS_PER_TICK;
        }
    }

    @Inject(method = "onChunkBatchReceivedByClient(F)V", at = @At("TAIL"), require = 0)
    private void superchunk$widenAckWindow(float desiredBatchSize, CallbackInfo ci) {
        // At TAIL vanilla has just set maxUnacknowledgedBatches = 10 (its constant). Raise-only:
        // with the default config value of 10 this is a no-op; it can never LOWER the window,
        // and it never touches the initial pre-first-ack window of 1 (join handshake warm-up).
        if (FullSpeedLoading.ENABLED && FullSpeedLoading.MAX_UNACKED_BATCHES > this.maxUnacknowledgedBatches) {
            this.maxUnacknowledgedBatches = FullSpeedLoading.MAX_UNACKED_BATCHES;
        }
    }
}
