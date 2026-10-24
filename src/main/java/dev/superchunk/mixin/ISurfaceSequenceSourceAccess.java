package dev.superchunk.mixin;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Accessor for {@code SurfaceRules.SequenceRuleSource} (used by {@code DeepSurfaceRules}; the record is public here but
 * package-private in 1.20.1, so both ports read it the same way).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRuleSource")
public interface ISurfaceSequenceSourceAccess {

    @Accessor("sequence")
    List<SurfaceRules.RuleSource> superchunk$sequence();
}
