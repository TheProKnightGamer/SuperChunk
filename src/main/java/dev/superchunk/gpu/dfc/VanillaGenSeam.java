package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.OpenCLBackend;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.disposables.Disposable;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * FORGE 1.20.1 PORT — bridge between the VANILLA chunk-status pipeline
 * ({@code CompletableFuture} steps) and the Rx {@link Completable} batch-prefetch wraps
 * ({@link GpuBatchPrefetch#wrapNoise} / {@link GpuClimatePrefetch#wrapBiomes}).
 *
 * <p>On neo, C2ME's rewritten chunk system already models generation steps as
 * {@code Completable}s, so the wraps drop in at its status delegate. Vanilla 1.20.1 models
 * them as {@code CompletableFuture}s returned by {@code NoiseBasedChunkGenerator}
 * ({@code createBiomes} / {@code fillFromNoise}); the seam mixin
 * ({@code dev.superchunk.mixin.MixinNoiseBasedChunkGeneratorGpuSeam}) intercepts those and
 * routes them through here.
 *
 * <p>Semantics preserved from the neo seams:
 * <ul>
 *   <li><b>Wrap declined == vanilla byte-for-byte:</b> the wraps return the step
 *       Completable UNCHANGED (identity) whenever batching can't apply; we detect that and
 *       run the original future directly — zero Rx machinery on that path.</li>
 *   <li><b>Step errors surface unchanged:</b> a worldgen exception from the original step
 *       is mirrored into the returned future (the prefetch itself never errors — it
 *       always completes and falls back to the per-chunk path).</li>
 *   <li><b>Cancellation evicts:</b> cancelling the returned future disposes the Rx chain,
 *       which cancels an unresolved batch request and evicts any pending deposit
 *       (the emitter cancellable inside the wraps).</li>
 * </ul>
 */
public final class VanillaGenSeam {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private VanillaGenSeam() {
    }

    /** Noise seam: {@link GpuBatchPrefetch#wrapNoise} over a vanilla future step. */
    public static CompletableFuture<ChunkAccess> wrapNoise(ChunkAccess chunk,
                                                           Supplier<CompletableFuture<ChunkAccess>> step) {
        return bridge(stepC -> GpuBatchPrefetch.wrapNoise(chunk, stepC), step);
    }

    /** Biomes seam: {@link GpuClimatePrefetch#wrapBiomes} over a vanilla future step. */
    public static CompletableFuture<ChunkAccess> wrapBiomes(BiomeSource biomeSource, RandomState randomState,
                                                            ChunkAccess chunk,
                                                            Supplier<CompletableFuture<ChunkAccess>> step) {
        return bridge(stepC -> GpuClimatePrefetch.wrapBiomes(biomeSource, randomState, chunk, stepC), step);
    }

    private static CompletableFuture<ChunkAccess> bridge(Function<Completable, Completable> wrapper,
                                                         Supplier<CompletableFuture<ChunkAccess>> step) {
        try {
            final AtomicReference<CompletableFuture<ChunkAccess>> innerRef = new AtomicReference<>();
            final AtomicReference<ChunkAccess> result = new AtomicReference<>();
            final Completable stepC = Completable.defer(() -> {
                CompletableFuture<ChunkAccess> inner = step.get();
                innerRef.set(inner);
                return Completable.fromCompletionStage(inner.thenAccept(result::set));
            });

            final Completable wrapped = wrapper.apply(stepC);
            if (wrapped == stepC) {
                // Wrap declined (flags off / not applicable for this chunk): vanilla path.
                return step.get();
            }

            final CompletableFuture<ChunkAccess> out = new CompletableFuture<>();
            final Disposable d = wrapped.subscribe(
                    () -> out.complete(result.get()),
                    err -> {
                        // The prefetch stage never errors by design — an error here is the
                        // ORIGINAL step failing (worldgen exception): mirror its outcome so
                        // the chunk system sees exactly the vanilla failure. If the step
                        // never even started (shouldn't happen), run it once.
                        CompletableFuture<ChunkAccess> inner = innerRef.get();
                        if (inner != null) {
                            inner.whenComplete((c, e) -> {
                                if (e != null) {
                                    out.completeExceptionally(e);
                                } else {
                                    out.complete(c);
                                }
                            });
                        } else {
                            LOGGER.warn("[SuperChunk-GPU] [vanilla-seam] wrap errored before the step ran — running step directly.", err);
                            try {
                                step.get().whenComplete((c, e) -> {
                                    if (e != null) {
                                        out.completeExceptionally(e);
                                    } else {
                                        out.complete(c);
                                    }
                                });
                            } catch (Throwable t) {
                                out.completeExceptionally(t);
                            }
                        }
                    });
            // Chunk-system cancellation (unload race): dispose the chain so an unresolved
            // batch request is cancelled and any pending deposit evicted.
            out.whenComplete((r, e) -> {
                if (out.isCancelled()) {
                    try {
                        d.dispose();
                    } catch (Throwable ignored) {
                    }
                }
            });
            return out;
        } catch (Throwable t) {
            // The seam must never break gen: fall back to the raw step.
            LOGGER.warn("[SuperChunk-GPU] [vanilla-seam] bridge threw — vanilla path.", t);
            return step.get();
        }
    }
}
