package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.fluid_postprocessing;

import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelChunk.class)
public class MixinWorldChunk {

    @Redirect(method = "postProcessGeneration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private void redirectFluidScheduledTick(FluidState instance, Level world, BlockPos pos) {
        world.scheduleTick(pos, instance.getType(), 1);
    }

}
