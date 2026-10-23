package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.Beardifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Beardifier.class)
public interface IStructureWeightSampler {

    /** Vanilla's 24 x 24 x 24 beard kernel table (SuperChunk: uploaded once for the GPU decide kernel). */
    @Accessor("BEARD_KERNEL")
    static float[] superchunk$beardKernel() {
        throw new AssertionError();
    }
}
