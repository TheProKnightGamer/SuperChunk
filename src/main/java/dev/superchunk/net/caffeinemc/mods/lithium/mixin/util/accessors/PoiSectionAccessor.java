package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.accessors;

import net.minecraft.world.entity.ai.village.poi.PoiSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SuperChunk (Forge 1.20.1): {@code PoiSection.isValid()} is package-private. Lithium widened it with
 * an access transformer, which crashes any mod that {@code @Overwrite}s it at vanilla visibility
 * ("cannot reduce visibiliy", GitHub #31).
 */
@Mixin(PoiSection.class)
public interface PoiSectionAccessor {
    @Invoker("isValid")
    boolean lithium$isValid();
}
