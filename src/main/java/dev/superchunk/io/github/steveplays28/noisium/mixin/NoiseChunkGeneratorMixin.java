package dev.superchunk.io.github.steveplays28.noisium.mixin;

import dev.superchunk.compat.LithiumBlockTracking;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin extends ChunkGenerator {
	public NoiseChunkGeneratorMixin(BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Redirect(method = "doFill(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState noisium$populateNoiseWrapSetBlockStateOperation(@NotNull LevelChunkSection chunkSection, int chunkSectionBlockPosX, int chunkSectionBlockPosY, int chunkSectionBlockPosZ, @NotNull BlockState blockState, boolean lock) {
		// A standalone Lithium's counting mixin is live but could not be bound (see
		// LithiumBlockTracking): the direct palette write below would leave ITS counters
		// stale, so take the real setBlockState path — the foreign hooks run inside it.
		if (LithiumBlockTracking.directWritesUnsafe()) {
			return chunkSection.setBlockState(chunkSectionBlockPosX, chunkSectionBlockPosY, chunkSectionBlockPosZ, blockState, lock);
		}

		// Update the non empty block count to avoid issues with MC's lighting engine and other systems not recognising the direct palette storage set
		// See LevelChunkSection#setBlockState
		chunkSection.nonEmptyBlockCount += 1;

		if (!blockState.getFluidState().isEmpty()) {
			chunkSection.tickingFluidCount += 1;
		}

		if (blockState.isRandomlyTicking()) {
			chunkSection.tickingBlockCount += 1;
		}

		// Capture the previous block state at this position before the direct palette write below.
		// Lithium's LevelChunkSection#setBlockState @Inject (see LevelChunkSectionMixin#updateFlagCounters)
		// maintains the per-section block-counting flags (WATER / LAVA / PATH_NOT_OPEN / ...). The direct
		// palette write bypasses that @Inject, leaving the flag counts stale for freshly generated,
		// not-yet-reloaded chunks. LithiumBlockTracking binds whichever Lithium is live (bundled or
		// standalone), so this stays correct with an installed Lithium of any bindable version.
		final BlockState noisium$previousBlockState = LithiumBlockTracking.active()
				? chunkSection.getBlockState(chunkSectionBlockPosX, chunkSectionBlockPosY, chunkSectionBlockPosZ)
				: null;

		// Set the blockstate in the palette storage directly to improve performance
		var blockStateId = chunkSection.states.data.palette().idFor(blockState);
		chunkSection.states.data.storage().set(
				chunkSection.states.strategy.getIndex(chunkSectionBlockPosX, chunkSectionBlockPosY,
						chunkSectionBlockPosZ
				), blockStateId);

		// Maintain Lithium's section block-counting flags that the direct palette write above bypasses.
		// Mirrors LevelChunkSectionMixin#updateFlagCounters: lithium$trackBlockStateChange(newState, oldState).
		if (LithiumBlockTracking.active()) {
			LithiumBlockTracking.track(chunkSection, blockState, noisium$previousBlockState);
		}

		return blockState;
	}
}
