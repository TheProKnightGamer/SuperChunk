package dev.superchunk.mixin;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the package-private {@code SurfaceRules.TestRuleSource} (used by {@code DeepSurfaceRules}). */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$TestRuleSource")
public interface ISurfaceTestRuleSourceAccess {

    @Accessor("ifTrue")
    SurfaceRules.ConditionSource superchunk$ifTrue();

    @Accessor("thenRun")
    SurfaceRules.RuleSource superchunk$thenRun();
}
