package dev.superchunk.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
import dev.superchunk.config.PlayerLatency;
import dev.superchunk.latency.PlayerLatencyMetrics;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * SuperChunk {@code player.latencyMetrics} — proof metrics on vanilla {@link PlayerChunkSender}.
 * Every hook is a checked no-op while the flag is OFF (static-final, JIT-eliminated), so default
 * behavior is unchanged. Nothing here mutates sender state; it only observes.
 *
 * <ul>
 *   <li><b>Collect-miss counter (metric a).</b> {@code collectChunksToSend} probes
 *       {@code ChunkMap.getChunkToSend} for its send candidates and silently filters nulls
 *       (chunks pending but not yet exposed by the holder — the P1 defect). The probes are made
 *       through method references inside streams, so instead of wrapping the call sites we count
 *       arithmetically: attempts = the exact number of probes vanilla makes
 *       ({@code min(floor(batchQuota), pendingChunks.size())} on the non-memory branch,
 *       {@code pendingChunks.size()} otherwise, matching both vanilla branches), and
 *       misses = attempts - chunks actually collected. Accumulated per sender (i.e. per player)
 *       and flushed once per second from {@code sendNextChunks}.</li>
 *   <li><b>Send-latency stamp (metric c).</b> At {@code sendChunk} we read the holder's
 *       SERVER_ACCESSIBLE_CHUNK_SENDING completion stamp (written by
 *       {@code TheChunkSystem.onItemUpgrade} while the flag is ON) and record
 *       {@code now - stamp} for the 1s p50/p99 line.</li>
 * </ul>
 */
@Mixin(PlayerChunkSender.class)
public abstract class MixinPlayerChunkSenderLatencyMetrics {

    @Shadow
    @Final
    private LongSet pendingChunks;

    @Shadow
    @Final
    private boolean memoryConnection;

    @Shadow
    private float batchQuota;

    @Unique
    private int superchunk$collectAttempts;

    @Unique
    private int superchunk$collectMisses;

    @Unique
    private int superchunk$attemptsThisCall;

    @Unique
    private long superchunk$lastFlushMillis;

    @Inject(method = "sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 0)
    private void superchunk$flushPerSecond(ServerPlayer player, CallbackInfo ci) {
        if (!PlayerLatency.LATENCY_METRICS) return;
        // Lazy collector start: first player send tick == there is something to measure, and
        // GlobalExecutors has long been initialized by the chunk system.
        PlayerLatencyMetrics.ensureStarted();
        final long now = System.currentTimeMillis();
        if (this.superchunk$lastFlushMillis == 0L) {
            this.superchunk$lastFlushMillis = now;
            return;
        }
        if (now - this.superchunk$lastFlushMillis >= 1000L) {
            PlayerLatencyMetrics.logCollectStats(player.getScoreboardName(),
                    this.superchunk$collectAttempts, this.superchunk$collectMisses, this.pendingChunks.size());
            this.superchunk$collectAttempts = 0;
            this.superchunk$collectMisses = 0;
            this.superchunk$lastFlushMillis = now;
        }
    }

    @Inject(method = "collectChunksToSend", at = @At("HEAD"), require = 0)
    private void superchunk$countAttempts(ChunkMap chunkMap, ChunkPos chunkPos, CallbackInfoReturnable<List<LevelChunk>> cir) {
        if (!PlayerLatency.LATENCY_METRICS) return;
        // Mirror of vanilla's two branches: with a non-memory connection and a backlog larger
        // than the batch quota, exactly floor(batchQuota) candidates are probed; otherwise every
        // pending chunk is probed. pendingChunks is still unmodified at HEAD.
        final int quotaFloor = Mth.floor(this.batchQuota);
        final int pending = this.pendingChunks.size();
        this.superchunk$attemptsThisCall = (!this.memoryConnection && pending > quotaFloor) ? quotaFloor : pending;
    }

    @Inject(method = "collectChunksToSend", at = @At("RETURN"), require = 0)
    private void superchunk$countMisses(ChunkMap chunkMap, ChunkPos chunkPos, CallbackInfoReturnable<List<LevelChunk>> cir) {
        if (!PlayerLatency.LATENCY_METRICS) return;
        final int attempts = this.superchunk$attemptsThisCall;
        this.superchunk$attemptsThisCall = 0;
        final int sent = cir.getReturnValue().size();
        this.superchunk$collectAttempts += attempts;
        this.superchunk$collectMisses += Math.max(0, attempts - sent);
    }

    @Inject(method = "sendChunk", at = @At("HEAD"), require = 0)
    private static void superchunk$stampSendLatency(ServerGamePacketListenerImpl packetListener, ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
        if (!PlayerLatency.LATENCY_METRICS) return;
        // Main thread; updatingChunkMap is owned by the main thread, so this lookup is safe.
        final ChunkHolder holder = ((IThreadedAnvilChunkStorage) level.getChunkSource().chunkMap)
                .getCurrentChunkHolders().get(chunk.getPos().toLong());
        if (holder instanceof NewChunkHolderVanillaInterface newHolder) {
            final long readyNanos = newHolder.superchunk$getSendingReadyNanos();
            if (readyNanos != 0L) {
                PlayerLatencyMetrics.recordSendLatency(System.nanoTime() - readyNanos);
            }
        }
    }
}
