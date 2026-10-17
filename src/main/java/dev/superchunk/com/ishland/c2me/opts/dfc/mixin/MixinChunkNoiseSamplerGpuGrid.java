package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.gpu.dfc.ChunkGridCache;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SuperChunk GPU (Stage 5 — dispatch coalescing): scopes the per-thread
 * {@link ChunkGridCache} to exactly one chunk's interpolation pass.
 *
 * <p>{@code initializeForFirstCellX} begins the interpolation loop (sets
 * {@code interpolating=true}) and {@code stopInterpolation} ends it. The GPU
 * density-fill columns we coalesce are produced strictly between these two calls,
 * on the same worker thread, so a {@link ThreadLocal} cache bound here is correct
 * and lock-free. {@code beginChunk} also derives the whole-chunk cell-corner
 * lattice extent from this sampler's fields, and binds the cache key to this
 * {@code NoiseChunk} so a stale grid can never serve another chunk.
 *
 * <p>Pure plumbing — a no-op when the GPU backend / coalescing is off.
 */
@Mixin(NoiseChunk.class)
public abstract class MixinChunkNoiseSamplerGpuGrid {

    @Inject(method = "initializeForFirstCellX", at = @At("HEAD"))
    private void superchunk$beginChunkGrid(CallbackInfo ci) {
        ChunkGridCache.beginChunk((NoiseChunk) (Object) this);
    }

    @Inject(method = "stopInterpolation", at = @At("HEAD"))
    private void superchunk$endChunkGrid(CallbackInfo ci) {
        ChunkGridCache.endChunk();
    }
}
