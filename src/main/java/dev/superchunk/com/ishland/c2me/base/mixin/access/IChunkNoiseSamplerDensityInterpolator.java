package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public interface IChunkNoiseSamplerDensityInterpolator {

    @Invoker("updateForX")
    void invokeInterpolateX(double deltaX);

    @Invoker("updateForY")
    void invokeInterpolateY(double deltaY);

    @Invoker("updateForZ")
    void invokeInterpolateZ(double deltaZ);

}
