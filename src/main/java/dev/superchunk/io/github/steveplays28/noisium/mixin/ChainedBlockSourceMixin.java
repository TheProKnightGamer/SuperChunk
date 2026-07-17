package dev.superchunk.io.github.steveplays28.noisium.mixin;

import dev.superchunk.gpu.aquifer.BlockIdCensus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(MaterialRuleList.class)
public abstract class ChainedBlockSourceMixin {
	@Shadow
	@Final
	private List<NoiseChunk.BlockStateFiller> materialRuleList;

	// SuperChunk STAGE-3 block-id census (default OFF; -Dsuperchunk.gpu.blockIdVerify): this
	// seam sees BOTH the block position and the FINAL shipped decision (null -> default stone,
	// vein states resolved by the ore-vein filler after the aquifer returned null), so it is
	// the CPU oracle's finalize point. armBlock/finalizeCpuBlock bracket the filler chain: the
	// aquifer hook only records blocks armed here, which structurally excludes carver-stage
	// computeSubstance reuse (carvers never call MaterialRuleList). Static-final gated:
	// zero behavior/overhead when the flag is off.
	@Unique
	private static final boolean SUPERCHUNK$CENSUS = Boolean.getBoolean("superchunk.gpu.blockIdVerify");

	/**
	 * @author Steveplays28
	 * @reason Micro-optimisation
	 */
	@Overwrite
	@Nullable
	@SuppressWarnings("ForLoopReplaceableByForEach")
	public BlockState calculate(DensityFunction.FunctionContext pos) {
		if (SUPERCHUNK$CENSUS) {
			return superchunk$calculateCensus(pos);
		}
		for (int i = 0; i < this.materialRuleList.size(); i++) {
			BlockState blockState = this.materialRuleList.get(i).calculate(pos);
			if (blockState == null) {
				continue;
			}

			return blockState;
		}

		return null;
	}

	/** Census wrapper: identical result to the loop above, plus the arm/finalize handshake. */
	@Unique
	@Nullable
	private BlockState superchunk$calculateCensus(DensityFunction.FunctionContext pos) {
		int x = pos.blockX();
		int y = pos.blockY();
		int z = pos.blockZ();
		boolean armed = BlockIdCensus.armBlock(x, y, z);
		BlockState result = null;
		for (int i = 0; i < this.materialRuleList.size(); i++) {
			BlockState blockState = this.materialRuleList.get(i).calculate(pos);
			if (blockState != null) {
				result = blockState;
				break;
			}
		}
		if (armed) {
			BlockIdCensus.finalizeCpuBlock(x, y, z, result);
		}
		return result;
	}
}
