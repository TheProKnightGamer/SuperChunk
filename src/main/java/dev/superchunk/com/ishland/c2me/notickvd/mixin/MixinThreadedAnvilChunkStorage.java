package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import com.mojang.datafixers.util.Either;
import dev.superchunk.com.ishland.c2me.notickvd.common.Config;
import dev.superchunk.com.ishland.c2me.notickvd.common.IDirectChunkSending;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * notickvd's ChunkMap surgery, re-based for 1.20.1 (portplans/vmp-notickvd-player.md §3.3):
 * <ul>
 *   <li>(a) lifts the 32 view-distance cap ({@code Mth.clamp} arg in setViewDistance);</li>
 *   <li>(b) makes FULL (border/no-tick) chunks sendable through the tracking path — the 1.20.1
 *       seat of neo's prepareTickingChunk redirect AND of P1 (player.sendAtChunkSending):
 *       without it border chunks never send. Always on with notickvd (no separate flag);</li>
 *   <li>(c) send-on-accessible = neo's onMakeChunkAccessible: chunks are sent to watchers as
 *       soon as {@code prepareAccessibleChunk} completes (vanilla-chunk-system path; with the
 *       Wave-C chunk system the equivalent seat is {@code MixinServerAccessibleChunkSending});</li>
 *   <li>(d) suppresses the vanilla ticking-promotion re-send. The vanilla call lives in a lambda
 *       of {@code prepareTickingChunk} (untargetable robustly), so {@code playerLoadedChunk}
 *       itself is gated: only sends flagged by the (b)-path, the (c)-path or
 *       {@link IDirectChunkSending} pass. {@code ensureChunkCorrectness=true} restores vanilla
 *       double-sending semantics and is the third-party-mod escape hatch (a mod calling
 *       playerLoadedChunk directly would otherwise have its sends suppressed).</li>
 * </ul>
 * All of (b)(c)(d) run on the server main thread (updateChunkTracking: main;
 * prepareAccessibleChunk completion: main via mailbox; ticking-send lambda: main via mailbox) —
 * the plain boolean is safe.
 */
@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage implements IDirectChunkSending {

    @Shadow @Final public BlockableEventLoop<Runnable> mainThreadExecutor; // AT f_140135_ already public

    @Shadow public abstract List<ServerPlayer> getPlayers(ChunkPos pos, boolean edgeOnly);

    @Shadow protected abstract void playerLoadedChunk(ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> cache, LevelChunk chunk);

    @Unique
    private boolean superchunk$directSendAllowed; // main-thread confined

    // (a) lift the 32 view-distance cap. Target: `int i = Mth.clamp(p_140168_, 2, 32);`
    @ModifyArg(method = "setViewDistance(I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I"), index = 2)
    private int modifyMaxVD(int max) {
        return Config.maxViewDistance;
    }

    // (b) `LevelChunk levelchunk = chunkholder.getTickingChunk();` in updateChunkTracking.
    //     With the Wave-C chunk system live, holders are NewChunkHolderVanillaInterface —
    //     getFullChunkFuture() virtual-dispatches to the flowsched SERVER_ACCESSIBLE future.
    @Redirect(method = "updateChunkTracking",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk redirectSendWatchPacketsGetWorldChunk(ChunkHolder holder) {
        LevelChunk c = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).left().orElse(null);
        if (dev.superchunk.diag.ChunkSendDiag.ENABLED) dev.superchunk.diag.ChunkSendDiag.onTrackResolved(c != null);
        return c;
    }

    // The tracking path is a legitimate sender — mark it (see (d)).
    @Inject(method = "updateChunkTracking", at = @At("HEAD"))
    private void enterTracking(CallbackInfo ci) {
        this.superchunk$directSendAllowed = true;
        if (dev.superchunk.diag.ChunkSendDiag.ENABLED) dev.superchunk.diag.ChunkSendDiag.onTracking();
    }

    @Inject(method = "updateChunkTracking", at = @At("RETURN"))
    private void exitTracking(CallbackInfo ci) {
        this.superchunk$directSendAllowed = false;
    }

    // (c) send-on-accessible (vanilla-chunk-system path; never invoked when the Wave-C chunk
    //     system owns scheduling — its seat is MixinServerAccessibleChunkSending instead).
    //     prepareAccessibleChunk's future completes ON THE MAIN THREAD (composed via
    //     mainThreadMailbox — verified body).
    @Inject(method = "prepareAccessibleChunk", at = @At("RETURN"))
    private void onMakeChunkAccessible(ChunkHolder chunkHolder,
            CallbackInfoReturnable<CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>>> cir) {
        cir.getReturnValue().thenAccept(either -> either.ifLeft(worldChunk -> {
            if (Config.compatibilityMode) {
                this.mainThreadExecutor.tell(() -> this.c2me_notickvd$sendToWatchers(worldChunk));
            } else {
                this.c2me_notickvd$sendToWatchers(worldChunk);
            }
        }));
    }

    @Override
    @Unique
    public void c2me_notickvd$sendToWatchers(LevelChunk chunk) {
        this.superchunk$directSendAllowed = true;
        try {
            final MutableObject<ClientboundLevelChunkWithLightPacket> cache = new MutableObject<>();
            final List<ServerPlayer> watchers = this.getPlayers(chunk.getPos(), false);
            if (dev.superchunk.diag.ChunkSendDiag.ENABLED) dev.superchunk.diag.ChunkSendDiag.onAccessibleSend(watchers.size());
            for (ServerPlayer p : watchers) {
                this.playerLoadedChunk(p, cache, chunk);
            }
        } finally {
            this.superchunk$directSendAllowed = false;
        }
    }

    // (d) gate playerLoadedChunk itself (see class javadoc).
    @Inject(method = "playerLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void controlDuplicateChunkSending(ServerPlayer player,
            MutableObject<ClientboundLevelChunkWithLightPacket> cache, LevelChunk chunk, CallbackInfo ci) {
        final boolean cancel = !Config.ensureChunkCorrectness && !this.superchunk$directSendAllowed;
        if (dev.superchunk.diag.ChunkSendDiag.ENABLED) dev.superchunk.diag.ChunkSendDiag.onPlayerLoadedChunk(cancel);
        if (cancel) {
            ci.cancel();
        }
    }

}
