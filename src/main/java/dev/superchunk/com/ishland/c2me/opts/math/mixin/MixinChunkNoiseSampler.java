package dev.superchunk.com.ishland.c2me.opts.math.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSamplerDensityInterpolator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Consumer;

@Mixin(NoiseChunk.class)
public class MixinChunkNoiseSampler {

    @Mutable
    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void postInit(CallbackInfo ci) {
        this.interpolators = new ObjectArrayList<>(this.interpolators);
    }

    @Redirect(method = "updateForX", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void replaceIterationX(List<NoiseChunk.NoiseInterpolator> instance, Consumer<NoiseChunk.NoiseInterpolator> consumer, int blockX, double deltaX) {
        if (instance == this.interpolators && instance instanceof ObjectArrayList<NoiseChunk.NoiseInterpolator> list) {
            int size = list.size();
            Object[] elements = list.elements();
            for (int i = 0; i < size; i++) {
                ((IChunkNoiseSamplerDensityInterpolator) elements[i]).invokeInterpolateX(deltaX);
            }
        } else {
            instance.forEach(consumer);
        }
    }

    @Redirect(method = "updateForY", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void replaceIterationY(List<NoiseChunk.NoiseInterpolator> instance, Consumer<NoiseChunk.NoiseInterpolator> consumer, int blockY, double deltaY) {
        if (instance == this.interpolators && instance instanceof ObjectArrayList<NoiseChunk.NoiseInterpolator> list) {
            int size = list.size();
            Object[] elements = list.elements();
            for (int i = 0; i < size; i++) {
                ((IChunkNoiseSamplerDensityInterpolator) elements[i]).invokeInterpolateY(deltaY);
            }
        } else {
            instance.forEach(consumer);
        }
    }

    @Redirect(method = "updateForZ", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void replaceIterationZ(List<NoiseChunk.NoiseInterpolator> instance, Consumer<NoiseChunk.NoiseInterpolator> consumer, int blockZ, double deltaZ) {
        if (instance == this.interpolators && instance instanceof ObjectArrayList<NoiseChunk.NoiseInterpolator> list) {
            int size = list.size();
            Object[] elements = list.elements();
            for (int i = 0; i < size; i++) {
                ((IChunkNoiseSamplerDensityInterpolator) elements[i]).invokeInterpolateZ(deltaZ);
            }
        } else {
            instance.forEach(consumer);
        }
    }

}
