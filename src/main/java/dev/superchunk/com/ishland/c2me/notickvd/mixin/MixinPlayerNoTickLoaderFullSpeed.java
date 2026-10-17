package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import dev.superchunk.com.ishland.c2me.notickvd.common.Config;
import dev.superchunk.com.ishland.c2me.notickvd.common.PlayerNoTickLoader;
import dev.superchunk.config.FullSpeedLoading;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SuperChunk {@code player.fullSpeedLoading} — lifts C2ME notickvd's concurrent chunk-load cap.
 *
 * <p>{@code PlayerNoTickLoader.tickFutures()} keeps at most
 * {@code Config.maxConcurrentChunkLoads} (C2ME default: executor parallelism + 1) no-tick
 * view-distance chunk loads in flight per world ({@code while (chunkLoadFutures.size() <
 * Config.maxConcurrentChunkLoads && addOneTicket());}). That cap is sized for CPU worldgen
 * latency; with SuperChunk's GPU-augmented generation throughput it starves the pipeline, so a
 * fast-flying player outruns the no-tick ring while the generators sit idle.
 *
 * <p>This redirects the single GETSTATIC of {@code Config.maxConcurrentChunkLoads} inside
 * {@code tickFutures()} and, when the toggle is ON, raises the cap to at least
 * {@link FullSpeedLoading#NOTICKVD_MAX_CONCURRENT_LOADS} (raise-only: an explicitly higher
 * {@code c2me.noTickViewDistance.maxConcurrentChunkLoads} in the unified config still wins).
 * When the toggle is OFF (default) the original value is returned unchanged.
 *
 * <p>Targets a vendored (non-Minecraft) class, hence {@code remap = false} — same pattern as
 * {@link MixinServerAccessibleChunkSending} targeting a vendored chunk-system class. Note this
 * cap is per-WORLD (all players of a dimension share one {@code PlayerNoTickLoader}); the lift
 * only changes how many loads may be in flight, backpressure inside the chunk-system scheduler
 * itself is untouched. Server-side only: this makes chunks BECOME loadable/sendable sooner; how
 * fast the client accepts them is still governed by its own batch acks.
 */
@Mixin(value = PlayerNoTickLoader.class, remap = false)
public abstract class MixinPlayerNoTickLoaderFullSpeed {

    @Redirect(
            method = "tickFutures()V",
            at = @At(
                    value = "FIELD",
                    target = "Ldev/superchunk/com/ishland/c2me/notickvd/common/Config;maxConcurrentChunkLoads:I",
                    opcode = Opcodes.GETSTATIC
            )
    )
    private int superchunk$liftConcurrentLoadCap() {
        int configured = Config.maxConcurrentChunkLoads;
        if (FullSpeedLoading.ENABLED) {
            return Math.max(configured, FullSpeedLoading.NOTICKVD_MAX_CONCURRENT_LOADS);
        }
        return configured;
    }
}
