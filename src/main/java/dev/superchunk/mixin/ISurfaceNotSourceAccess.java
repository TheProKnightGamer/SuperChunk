package dev.superchunk.mixin;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the package-private {@code SurfaceRules.NotConditionSource} (used by {@code DeepSurfaceRules}). */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$NotConditionSource")
public interface ISurfaceNotSourceAccess {

    @Accessor("target")
    SurfaceRules.ConditionSource superchunk$target();
}
