package dev.superchunk.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkAccessNoiseChunk;
import dev.superchunk.gpu.dfc.CompactConsume;
import dev.superchunk.gpu.dfc.CompactIds;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * STAGE-6 COMPACT-IDS CONSUME seam ({@code -Dsuperchunk.gpu.compactIds=on|verify}).
 *
 * <p><b>{@code on}</b>: at {@code doFill} HEAD, when {@link GpuBatchStore} holds this
 * chunk's complete Stage-5 decide-kernel ids, {@link CompactConsume#consumeFill}
 * performs the whole fill from the id array (bulk palette writes, histogram counters,
 * Lithium flags, WG heightmaps, bit7 postprocessing marks; beard-sentinel cells run the
 * ORIGINAL per-block machinery inside the vanilla loop skeleton) and the original loop
 * is cancelled. Any guard/consistency failure simply lets the original loop run.
 *
 * <p><b>{@code verify}</b>: the original loop runs UNTOUCHED and ships; the HEAD hook
 * only pins this chunk's ids + a postprocessing snapshot, and the RETURN hook compares
 * every consume-path side effect against the shipped chunk
 * ({@code [compact-consume-verify]} — zero divergence required).
 *
 * <p>The NoiseChunk is PEEKED (C2ME cache accessor), never created here: if no cached
 * sampler exists, no prefetch ever ran for this chunk, so there are no ids and nothing
 * to do. Flag off (default) or probe: both injections are no-ops.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseChunkGeneratorCompactIds {

    @Inject(
            method = "doFill(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void superchunk$compactConsumeHead(Blender blender, StructureManager structureManager,
                                               RandomState random, ChunkAccess chunk,
                                               int minCellY, int cellCountY,
                                               CallbackInfoReturnable<ChunkAccess> cir) {
        if (!CompactIds.CONSUME && !CompactIds.VERIFY) {
            return;
        }
        try {
            NoiseChunk nc = ((IChunkAccessNoiseChunk) chunk).superchunk$getNoiseChunk();
            if (CompactIds.VERIFY) {
                CompactConsume.verifyArm(chunk, nc, minCellY, cellCountY);
                return;
            }
            if (CompactConsume.consumeFill((NoiseBasedChunkGenerator) (Object) this,
                    chunk, nc, minCellY, cellCountY)) {
                cir.setReturnValue(chunk);
            }
        } catch (Throwable ignored) {
            // Never break gen — faults are counted/logged inside CompactConsume.
        }
    }

    @Inject(
            method = "doFill(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("RETURN"),
            require = 0)
    private void superchunk$compactConsumeVerifyTail(Blender blender, StructureManager structureManager,
                                                     RandomState random, ChunkAccess chunk,
                                                     int minCellY, int cellCountY,
                                                     CallbackInfoReturnable<ChunkAccess> cir) {
        if (!CompactIds.VERIFY) {
            return;
        }
        try {
            CompactConsume.verifyCompare((NoiseBasedChunkGenerator) (Object) this, chunk);
        } catch (Throwable ignored) {
        }
    }
}
