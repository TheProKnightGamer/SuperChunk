package dev.superchunk.mixin;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SurfaceRules.Context.class)
public interface ISurfaceRulesContextAccess {
    @Accessor("blockX")
    int superchunk$blockX();

    @Accessor("blockY")
    int superchunk$blockY();

    @Accessor("blockZ")
    int superchunk$blockZ();
}
