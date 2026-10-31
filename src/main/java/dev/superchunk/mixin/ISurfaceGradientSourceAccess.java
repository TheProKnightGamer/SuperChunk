package dev.superchunk.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the package-private {@code SurfaceRules.VerticalGradientConditionSource} (used by {@code DeepSurfaceRules}). */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource")
public interface ISurfaceGradientSourceAccess {

    @Accessor("randomName")
    ResourceLocation superchunk$randomName();

    @Accessor("trueAtAndBelow")
    VerticalAnchor superchunk$trueAtAndBelow();

    @Accessor("falseAtAndAbove")
    VerticalAnchor superchunk$falseAtAndAbove();
}
