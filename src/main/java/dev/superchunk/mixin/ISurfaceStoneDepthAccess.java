package dev.superchunk.mixin;

import net.minecraft.world.level.levelgen.placement.CaveSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the package-private {@code SurfaceRules.StoneDepthCheck} (used by {@code CeilingDepth}). */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$StoneDepthCheck")
public interface ISurfaceStoneDepthAccess {

    @Accessor("offset")
    int superchunk$offset();

    @Accessor("addSurfaceDepth")
    boolean superchunk$addSurfaceDepth();

    @Accessor("secondaryDepthRange")
    int superchunk$secondaryDepthRange();

    @Accessor("surfaceType")
    CaveSurface superchunk$surfaceType();
}
