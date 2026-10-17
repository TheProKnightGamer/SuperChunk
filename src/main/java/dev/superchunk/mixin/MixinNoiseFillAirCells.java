package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.AirCells;
import dev.superchunk.worldgen.BlockFillHooks;
import dev.superchunk.worldgen.CellSignSkip;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;

    @WrapOperation(method = "doFill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;selectCellYZ(II)V"), require = 0)
    private void superchunk$selectCell(NoiseChunk nc, int cellY, int cellZ, Operation<Void> original) {
        original.call(nc, cellY, cellZ);
        if (AirCells.ENABLED && nc instanceof AirCells.Chunk chunk) {
            boolean unhooked = BlockFillHooks.unhooked();
            boolean air = unhooked && AirCells.cellIsAir(nc);
            chunk.superchunk$setAirCell(air);
            chunk.superchunk$setSolidCell(!air && unhooked && !CellSignSkip.VERIFY && superchunk$provenSolid(nc));
        }
    }

    /** A proven-solid, vein-free cell whose default block holds no fluid (no fluid-update scheduling). */
    @Unique
    private boolean superchunk$provenSolid(NoiseChunk nc) {
        return nc instanceof CellSignSkip.Chunk signed && signed.superchunk$provenSolid()
                && this.settings.value().defaultBlock().getFluidState().isEmpty();
    }

    @WrapOperation(method = "doFill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;getInterpolatedState()Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private BlockState superchunk$airCellState(NoiseChunk nc, Operation<BlockState> original) {
        if (nc instanceof AirCells.Chunk chunk) {
            if (chunk.superchunk$airCell()) {
                if (AirCells.VERIFY) {
                    BlockState produced = original.call(nc);
                    AirCells.verifyBlock(produced);
                    return produced;
                }
                return Blocks.AIR.defaultBlockState();
            }
            if (chunk.superchunk$solidCell()) {
                return null; // every material rule returns null here: doFill places the default block
            }
            if (CellSignSkip.VERIFY && superchunk$provenSolid(nc)) {
                BlockState produced = original.call(nc);
                CellSignSkip.verifySolidBlock(produced);
                return produced;
            }
        }
        return original.call(nc);
    }

    @WrapOperation(method = "doFill", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;stopInterpolation()V"), require = 0)
    private void superchunk$endFill(NoiseChunk nc, Operation<Void> original) {
        // The NoiseChunk is cached on the ProtoChunk and used again by later steps: leave it clean.
        if (nc instanceof AirCells.Chunk chunk) {
            chunk.superchunk$setAirCell(false);
            chunk.superchunk$setSolidCell(false);
        }
        original.call(nc);
    }
}
