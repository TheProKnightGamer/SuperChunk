package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla;

import dev.superchunk.com.ishland.c2me.base.common.ModuleMixinPlugin;
import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.common.Config;

public class MixinPlugin extends ModuleMixinPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!super.shouldApplyMixin(targetClassName, mixinClassName)) return false;

        // Stand down SuperChunk's own jigsaw-octree mixins when Structure Layout Optimizer is
        // installed: both mods redirect the same Shapes.joinUnoptimized/joinIsNotEmpty calls in
        // JigsawPlacement$Placer.tryPlacingChildren, a fatal apply-time conflict. SLO's octree is
        // equivalent (and it brings extra structure optimizations we don't have). See
        // dev.superchunk.compat.StructureLayoutOptimizerCompat.
        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.jigsaw.")) {
            if (!dev.superchunk.compat.StructureLayoutOptimizerCompat.applyOwnJigsawOctree()) return false;
            // The skip-blocked early-out is an opt-in add-on (default OFF): only apply its mixin when
            // the lever is enabled, so default boots carry no extra injection. Its behaviour still
            // requires the octree (it reads the seeded region) — inert if jigsawOctree is disabled.
            if (mixinClassName.endsWith("SkipBlocked")) {
                return Boolean.parseBoolean(System.getProperty("superchunk.worldgen.jigsawSkipBlocked", "false"))
                        || Boolean.getBoolean("superchunk.worldgen.jigsawSkipBlocked.verify");
            }
            return true;
        }

        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer."))
            return Config.optimizeAquifer;

        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.the_end_biome_cache."))
            return Config.useEndBiomeCache;

        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.structure_weight_sampler."))
            return Config.optimizeStructureWeightSampler;

        return true;
    }
}
