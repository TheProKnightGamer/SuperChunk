package dev.superchunk.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
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
 * placed even after one succeeds. Disable with {@code -Dsuperchunk.worldgen.placementImperative=false}.
 *
 * <p><b>Per-modifier fast paths</b> ({@code -Dsuperchunk.worldgen.placementFastMods=false} to
 * disable). Beyond removing the {@code flatMap} chain, each recursion stage still called the
 * modifier's {@code getPositions}, which allocates a throwaway {@code Stream} (2-3 pipeline objects)
 * that {@code forEachOrdered} then drains via a {@code ForEachOp} — for the vast majority of vanilla
 * modifiers this stream just carries a deterministic 0/1/N-position result. The fast paths dispatch
 * on the concrete modifier type and inline that result directly into the recursion, removing the
 * stream, the {@code ForEachOp}, and (for single/zero-output modifiers) this stage's
 * {@code boolean[]}+lambda. Each fast path is a <b>statement-exact</b> replica of the vanilla
 * {@code getPositions} body (verified against the 1.21.1 bytecode): the same {@code RandomSource}
 * draws in the same order (InSquare: {@code nextInt(16)} X then Z; RandomOffset: xzSpread, ySpread,
 * xzSpread; HeightRange: one {@code height.sample}; Count/Repeating: {@code count()} evaluated once
 * as the eager range bound; filters: {@code shouldPlace} drawn once) and the same {@code BlockPos}
 * outputs in the same encounter order. Covered: {@link PlacementFilter} (all subclasses),
 * {@link RepeatingPlacement} (Count/Noise counts), {@link InSquarePlacement},
 * {@link HeightmapPlacement}, {@link HeightRangePlacement}, {@link RandomOffsetPlacement}. Any other
 * (incl. modded) modifier falls through to the generic {@code getPositions().forEachOrdered(...)},
 * so behavior is unchanged for them.
 */
@Mixin(PlacedFeature.class)
public abstract class MixinPlacedFeatureImperative {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.placementImperative", "true"));

    @Unique
    private static final boolean SUPERCHUNK$FAST_MODS =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.placementFastMods", "true"));

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
        PlacementModifier mod = mods.get(idx);

        if (SUPERCHUNK$FAST_MODS) {
            // Terminal filters — getPositions = shouldPlace(ctx,src,pos) ? Stream.of(pos) : Stream.of().
            // shouldPlace draws its RNG (e.g. RarityFilter one nextFloat) exactly as vanilla; an empty
            // stream means the next stage is never reached, matching the && short-circuit.
            if (mod instanceof PlacementFilter) {
                return ((IPlacementFilterAccess) mod).superchunk$shouldPlace(ctx, src, pos)
                        && superchunk$stage(ctx, src, pos, idx + 1, cf);
            }
            // RepeatingPlacement — IntStream.range(0, count(src,pos)).mapToObj(p -> pos). count() is the
            // eager range bound (evaluated once, drawing its RNG once); the same pos is emitted N times,
            // all placed (no short-circuit).
            if (mod instanceof RepeatingPlacement) {
                int n = ((IRepeatingPlacementAccess) mod).superchunk$count(src, pos);
                boolean placed = false;
                for (int k = 0; k < n; k++) {
                    if (superchunk$stage(ctx, src, pos, idx + 1, cf)) {
                        placed = true;
                    }
                }
                return placed;
            }
            // InSquarePlacement — nextInt(16)+X then nextInt(16)+Z, new BlockPos(i, y, j).
            if (mod instanceof InSquarePlacement) {
                int i = src.nextInt(16) + pos.getX();
                int j = src.nextInt(16) + pos.getZ();
                return superchunk$stage(ctx, src, new BlockPos(i, pos.getY(), j), idx + 1, cf);
            }
            // HeightmapPlacement — k = getHeight(heightmap,x,z); k > minBuildHeight ? BlockPos(x,k,z) : none. No RNG.
            if (mod instanceof HeightmapPlacement) {
                int i = pos.getX();
                int j = pos.getZ();
                int k = ctx.getHeight(((IHeightmapPlacementAccess) mod).superchunk$heightmap(), i, j);
                return k > ctx.getMinBuildHeight() && superchunk$stage(ctx, src, new BlockPos(i, k, j), idx + 1, cf);
            }
            // HeightRangePlacement — pos.atY(height.sample(src, ctx)). One draw.
            if (mod instanceof HeightRangePlacement) {
                BlockPos p = pos.atY(((IHeightRangePlacementAccess) mod).superchunk$height().sample(src, ctx));
                return superchunk$stage(ctx, src, p, idx + 1, cf);
            }
            // RandomOffsetPlacement — draws xzSpread, ySpread, xzSpread in that order.
            if (mod instanceof RandomOffsetPlacement) {
                IRandomOffsetPlacementAccess acc = (IRandomOffsetPlacementAccess) mod;
                int i = pos.getX() + acc.superchunk$xzSpread().sample(src);
                int j = pos.getY() + acc.superchunk$ySpread().sample(src);
                int k = pos.getZ() + acc.superchunk$xzSpread().sample(src);
                return superchunk$stage(ctx, src, new BlockPos(i, j, k), idx + 1, cf);
            }
        }

        // Generic fallback: any other (incl. modded) modifier is drained exactly as before.
        boolean[] placed = {false};
        mod.getPositions(ctx, src, pos)
                .forEachOrdered(p -> {
                    if (superchunk$stage(ctx, src, p, idx + 1, cf)) {
                        placed[0] = true;
                    }
                });
        return placed[0];
    }
}
