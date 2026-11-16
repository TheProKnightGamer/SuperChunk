package dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks;

import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * On {@code NoiseRouter}: the vanilla router a compiled one was made from.
 *
 * <p>The density function compiler replaces every function of a level's router with a generated
 * class. A mod that reads the router's structure (to find the terrain function, to rebuild it its
 * own way) cannot read those. This keeps the router as vanilla built it and hands it out under the
 * method name such mods already look for ({@code NoiseRouter.c2me$getOriginalNoiseRouter()}, found
 * by reflection; Voxy SeedGen is one). It computes the same values as the compiled one.
 */
public interface IOriginalNoiseRouter {

    /** The vanilla router this one was compiled from, or {@code null} if this router is not a compiled one. */
    NoiseRouter c2me$getOriginalNoiseRouter();

    void c2me$setOriginalNoiseRouter(NoiseRouter original);
}
