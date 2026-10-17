package dev.superchunk.mixin;

import dev.superchunk.gpu.dfc.BiomeClimateCache;
import dev.superchunk.gpu.dfc.ChunkGridCache;
import dev.superchunk.gpu.dfc.VanillaGenSeam;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * FORGE 1.20.1 PORT — the VANILLA-chunk-system replacement for the two GPU batch seams
 * that neo hooks inside C2ME's rewritten chunk system ({@code VanillaWorldGenerationDelegate}):
 *
 * <ul>
 *   <li><b>BIOMES seam</b> ({@code createBiomes}): batched climate quart-grid prefetch —
 *       runs BEFORE the biome fill so {@code BiomeClimateCache} serves every quart sample
 *       from the deposited grids with no per-chunk dispatch.</li>
 *   <li><b>NOISE seam</b> ({@code fillFromNoise}): batched density corner-grid prefetch —
 *       runs BEFORE the noise fill so {@code ChunkGridCache} serves the fill from the
 *       {@code GpuBatchStore} deposit.</li>
 * </ul>
 *
 * <p>Both injections are HEAD-cancellable wrappers around the ORIGINAL method: the wrapped
 * step re-enters this same method with the {@link Unique} thread-local guard set, so the
 * inner call falls through to vanilla untouched. Flags off / wrap declined → the guard path
 * is never even constructed (zero overhead beyond two flag reads). 1.20.1 signatures carry
 * the leading {@code Executor} that 1.21.x dropped.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseBasedChunkGeneratorGpuSeam {

    /**
     * Wave C seam guard (c2me-core plan, GPU-integration gate): when the C2ME chunk-system
     * rewrite is active, {@code VanillaWorldGenerationDelegate} wraps the whole BIOMES/NOISE
     * status step in the SAME {@code GpuClimatePrefetch}/{@code GpuBatchPrefetch} seams at the
     * delegate level — this mixin must stand down or the wrap would fire twice per step
     * (once outer at the delegate, once here inside {@code createBiomes}/{@code fillFromNoise}).
     * Reads the module's master-switch-aware enabled flag; with
     * {@code -Dsuperchunk.chunksystem.disable=true} (or the module unavailable) this seam owns
     * the wrapping exactly as in stage 2.
     */
    @Unique
    private static final boolean superchunk$delegateOwnsSeams = superchunk$resolveDelegateOwnsSeams();

    @Unique
    private static boolean superchunk$resolveDelegateOwnsSeams() {
        try {
            return dev.superchunk.com.ishland.c2me.rewrites.chunksystem.ModuleEntryPoint.isEnabled();
        } catch (Throwable t) {
            return false; // chunk system unavailable -> vanilla seam owns the wraps
        }
    }

    @Unique
    private static final ThreadLocal<Boolean> superchunk$inBiomesWrap = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<Boolean> superchunk$inNoiseWrap = new ThreadLocal<>();

    @Inject(method = "createBiomes", at = @At("HEAD"), cancellable = true)
    private void superchunk$wrapBiomesSeam(Executor executor, RandomState randomState, Blender blender,
                                           StructureManager structureManager, ChunkAccess chunk,
                                           CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (superchunk$delegateOwnsSeams || superchunk$inBiomesWrap.get() != null || !BiomeClimateCache.isBatchClimateActive()) {
            return;
        }
        final NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        cir.setReturnValue(VanillaGenSeam.wrapBiomes(
                ((ChunkGenerator) self).getBiomeSource(), randomState, chunk,
                () -> {
                    superchunk$inBiomesWrap.set(Boolean.TRUE);
                    try {
                        return self.createBiomes(executor, randomState, blender, structureManager, chunk);
                    } finally {
                        superchunk$inBiomesWrap.remove();
                    }
                }));
    }

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void superchunk$wrapNoiseSeam(Executor executor, Blender blender, RandomState randomState,
                                          StructureManager structureManager, ChunkAccess chunk,
                                          CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (superchunk$delegateOwnsSeams || superchunk$inNoiseWrap.get() != null || !ChunkGridCache.isBatchEnabled()) {
            return;
        }
        final NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        cir.setReturnValue(VanillaGenSeam.wrapNoise(chunk,
                () -> {
                    superchunk$inNoiseWrap.set(Boolean.TRUE);
                    try {
                        return self.fillFromNoise(executor, blender, randomState, structureManager, chunk);
                    } finally {
                        superchunk$inNoiseWrap.remove();
                    }
                }));
    }
}
