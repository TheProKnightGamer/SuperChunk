package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IArrayCacheCapable;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.ICoordinatesFilling;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.DelegatingBlendingAwareVisitor;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseChunk.class)
public abstract class MixinChunkNoiseSampler implements IArrayCacheCapable, ICoordinatesFilling {

    @Shadow @Final int cellHeight;
    @Shadow @Final int cellWidth;
    @Shadow int cellStartBlockY;
    @Shadow private int cellStartBlockX;
    @Shadow private int cellStartBlockZ;
    @Shadow boolean interpolating;

    @Shadow public abstract Blender getBlender();

    private final ArrayCache c2me$arrayCache = new ArrayCache();

    @Override
    public ArrayCache c2me$getArrayCache() {
        return this.c2me$arrayCache != null ? this.c2me$arrayCache : new ArrayCache();
    }

    @Override
    public void c2me$fillCoordinates(int[] x, int[] y, int[] z) {
        int index = 0;
        for (int i = this.cellHeight - 1; i >= 0; i--) {
            int blockY = this.cellStartBlockY + i;
            for (int j = 0; j < this.cellWidth; j++) {
                int blockX = this.cellStartBlockX + j;
                for (int k = 0; k < this.cellWidth; k++) {
                    int blockZ = this.cellStartBlockZ + k;

                    x[index] = blockX;
                    y[index] = blockY;
                    z[index] = blockZ;

                    index++;
                }
            }
        }
    }

    @Inject(method = "wrapNew", at = @At("HEAD"))
    private void protectInterpolationLoop(DensityFunction function, CallbackInfoReturnable<DensityFunction> cir) {
        if (this.interpolating && function instanceof DensityFunctions.MarkerOrMarked) {
            throw new IllegalStateException("Cannot create more wrapping during interpolation loop");
        }
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private DensityFunction.Visitor modifyVisitor1(DensityFunction.Visitor visitor) {
        return c2me$getDelegatingBlendingAwareVisitor(visitor);
    }

    @Unique
    private @NotNull DelegatingBlendingAwareVisitor c2me$getDelegatingBlendingAwareVisitor(DensityFunction.Visitor visitor) {
        return new DelegatingBlendingAwareVisitor(visitor, this.getBlender() != Blender.empty());
    }

    @ModifyArg(method = {"<init>", "cachedClimateSampler"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"), require = 7, expect = 7)
    private DensityFunction.Visitor modifyVisitor2(DensityFunction.Visitor visitor) {
        return c2me$getDelegatingBlendingAwareVisitor(visitor);
    }

}
