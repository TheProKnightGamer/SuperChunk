package dev.superchunk.io.github.steveplays28.noisium.mixin;

import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.NoiseSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caches the cell width and cell height, so it doesn't have to convert from biome coordinates to block coordinates every time.
 */
@Mixin(NoiseSettings.class)
public abstract class GenerationShapeConfigMixin {
	@Unique
	private int noisium$horizontalCellBlockCount;
	@Unique
	private int noisium$verticalCellBlockCount;

	@Inject(method = "<init>", at = @At(value = "TAIL"))
	private void noisium$createCacheHorizontalAndVerticalCellBlockCountInject(int minimumY, int height, int horizontalSize, int verticalSize, CallbackInfo ci) {
		noisium$horizontalCellBlockCount = QuartPos.toBlock(horizontalSize);
		noisium$verticalCellBlockCount = QuartPos.toBlock(verticalSize);
	}

	@Inject(method = "getCellWidth", at = @At(value = "HEAD"), cancellable = true)
	private void noisium$horizontalCellBlockCountGetFromCacheInject(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(noisium$horizontalCellBlockCount);
	}

	@Inject(method = "getCellHeight", at = @At(value = "HEAD"), cancellable = true)
	private void noisium$verticalCellBlockCountGetFromCacheInject(CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(noisium$verticalCellBlockCount);
	}
}
