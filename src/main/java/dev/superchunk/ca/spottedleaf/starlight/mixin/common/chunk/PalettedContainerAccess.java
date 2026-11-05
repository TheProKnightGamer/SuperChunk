package dev.superchunk.ca.spottedleaf.starlight.mixin.common.chunk;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SuperChunk (Forge 1.20.1): reads {@code PalettedContainer.get(int)}, which is protected here
 * (public from 1.21). ScalableLux's access widener made it public instead, and then a mod that
 * {@code @Overwrite}s it as protected fails with "cannot reduce visibiliy of PUBLIC target method"
 * (Collections Of Optimizations 5.8, GitHub #31).
 */
@Mixin(PalettedContainer.class)
public interface PalettedContainerAccess {

    @Invoker("get")
    Object superchunk$get(int index);
}
