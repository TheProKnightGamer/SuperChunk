package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.AirCells;
import dev.superchunk.worldgen.BlockFillHooks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The air-cell skip in vanilla's {@code doFill} loop ({@link AirCells}): after each
 * {@code selectCellYZ}, decide whether the cell is provably all air; while it is, each block's
 * {@code getInterpolatedState} is answered with that {@code AIR} and the per-block interpolator
 * loops are skipped ({@code MixinChunkNoiseSampler}). The loop structure, the chunk's counters and
 * cell coordinates, and every non-air cell are exactly vanilla's.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseFillAirCells {

    @WrapOperation(method = "doFill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;selectCellYZ(II)V"), require = 0)
    private void superchunk$selectCell(NoiseChunk nc, int cellY, int cellZ, Operation<Void> original) {
        original.call(nc, cellY, cellZ);
        if (AirCells.ENABLED && nc instanceof AirCells.Chunk chunk) {
            boolean air = BlockFillHooks.unhooked() && AirCells.cellIsAir(nc);
            chunk.superchunk$setAirCell(air);
        }
    }

    @WrapOperation(method = "doFill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;getInterpolatedState()Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private BlockState superchunk$airCellState(NoiseChunk nc, Operation<BlockState> original) {
        if (nc instanceof AirCells.Chunk chunk && chunk.superchunk$airCell()) {
            if (AirCells.VERIFY) {
                BlockState produced = original.call(nc);
                AirCells.verifyBlock(produced);
                return produced;
            }
            return Blocks.AIR.defaultBlockState();
        }
        return original.call(nc);
    }

    @WrapOperation(method = "doFill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;stopInterpolation()V"), require = 0)
    private void superchunk$endFill(NoiseChunk nc, Operation<Void> original) {
        // The NoiseChunk is cached on the ProtoChunk and used again by later steps: leave it clean.
        if (nc instanceof AirCells.Chunk chunk) {
            chunk.superchunk$setAirCell(false);
        }
        original.call(nc);
    }
}
