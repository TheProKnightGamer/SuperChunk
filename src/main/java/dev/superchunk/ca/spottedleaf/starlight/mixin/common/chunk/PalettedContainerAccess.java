package dev.superchunk.ca.spottedleaf.starlight.mixin.common.chunk;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SuperChunk: reads {@code PalettedContainer.get(int)}, which is protected in vanilla (NeoForge 21.1
 * does not widen it). ScalableLux's access widener made it public instead, and then a mod that
 * {@code @Overwrite}s it as protected fails with "cannot reduce visibiliy of PUBLIC target method"
 * (as Collections Of Optimizations 5.8 did on Forge, GitHub #31).
 */
@Mixin(PalettedContainer.class)
public interface PalettedContainerAccess {

    @Invoker("get")
    Object superchunk$get(int index);
}
