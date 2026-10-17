package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Blender.class)
public interface IBlender {

    // Yarn Blender.BLENDING_CHUNK_DISTANCE_THRESHOLD has no 1:1 Mojmap name; the
    // chunk-distance blending threshold maps to HEIGHT_BLENDING_RANGE_CHUNKS in 1.21.1.
    @Accessor("HEIGHT_BLENDING_RANGE_CHUNKS")
    static int getBLENDING_CHUNK_DISTANCE_THRESHOLD() {
        throw new AbstractMethodError();
    }

}
