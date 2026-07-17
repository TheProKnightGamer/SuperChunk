package dev.superchunk.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * SuperChunk: imperative (stream-free) {@code PlacedFeature.placeWithContext}.
 *
 * <p>Vanilla builds {@code Stream.of(pos)} + one {@code flatMap} PER placement modifier
 * PER placement attempt — the single largest allocation source in a pregen
 * ({@code ReferencePipeline$Head} = 4.4% of all allocation pressure; a count-90 ore =
 * hundreds of pipelines per feature per chunk).
 *
 * <p>Replaced by depth-first recursion: {@code stage(i)} pulls each position from
 * {@code modifier[i].getPositions(...)} via {@code forEachOrdered} and feeds it to
 * {@code stage(i+1)}. This is EXACTLY the evaluation order of the lazy flatMap chain
 * (flatMap fully drains each inner stream before requesting the next outer element,
 * and a sequential stream's forEach is encounter-ordered), so every
 * {@code getPositions} call, every RandomSource draw, and every {@code place} happens
 * in the identical order — including for lazily-generating modifiers like
 * {@code CountPlacement}. No short-circuit: like vanilla, ALL final positions are
 * placed even after one succeeds. The modifiers' own streams are still consumed as-is
 * (no per-modifier fast paths), so arbitrary modded {@link PlacementModifier}s behave
 * identically. Disable with {@code -Dsuperchunk.worldgen.placementImperative=false}.
 */
@Mixin(PlacedFeature.class)
public abstract class MixinPlacedFeatureImperative {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.placementImperative", "true"));

    @Shadow
    @Final
    private Holder<ConfiguredFeature<?, ?>> feature;

    @Shadow
    @Final
    private List<PlacementModifier> placement;

    // @WrapMethod rather than a HEAD-cancel @Inject: same fast-path effect, but the
    // disabled path composes with other mods' wraps/overwrites of placeWithContext
    // instead of pre-empting them at HEAD.
    @WrapMethod(method = "placeWithContext", require = 0)
    private boolean superchunk$imperativePlace(PlacementContext context, RandomSource source, BlockPos pos,
                                               Operation<Boolean> original) {
        if (!SUPERCHUNK$ENABLED) {
            return original.call(context, source, pos);
        }
        ConfiguredFeature<?, ?> cf = this.feature.value();
        return superchunk$stage(context, source, pos, 0, cf);
    }

    @Unique
    private boolean superchunk$stage(PlacementContext ctx, RandomSource src, BlockPos pos, int idx,
                                     ConfiguredFeature<?, ?> cf) {
        List<PlacementModifier> mods = this.placement;
        if (idx >= mods.size()) {
            return cf.place(ctx.getLevel(), ctx.generator(), src, pos);
        }
        boolean[] placed = {false};
        mods.get(idx).getPositions(ctx, src, pos)
                .forEachOrdered(p -> {
                    if (superchunk$stage(ctx, src, p, idx + 1, cf)) {
                        placed[0] = true;
                    }
                });
        return placed[0];
    }
}
