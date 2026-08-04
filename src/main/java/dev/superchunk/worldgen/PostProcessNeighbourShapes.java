package dev.superchunk.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * SuperChunk: <b>no-op-skip of {@code Block.updateFromNeighbourShapes} during chunk
 * post-processing</b> — removes the single biggest per-block main-thread cost in
 * {@code LevelChunk.postProcessGeneration}.
 *
 * <p><b>What vanilla does.</b> For every block enqueued for post-processing,
 * {@code postProcessGeneration()} calls {@code Block.updateFromNeighbourShapes(state, level, pos)},
 * which loops all six {@code UPDATE_SHAPE_ORDER} directions, reads each neighbour's
 * {@code BlockState}, and folds {@code state = state.updateShape(dir, neighbour, level, pos,
 * neighbourPos)}. That static method has <b>no side effects</b> — its result is purely the fold of
 * {@code updateShape} (verified against the 1.21.1 bytecode).
 *
 * <p>This class provides two tiers, selected per call by two static-final flags (JIT-folded):
 *
 * <h2>Tier 1 — proven-safe subset (default on)</h2>
 * {@code BlockBehaviour.updateShape}'s default body is {@code return state} (bytecode:
 * {@code aload_1; areturn}). So for any block whose class hierarchy does <b>not</b> override
 * {@code updateShape}, all six iterations are pure no-ops and the method provably returns
 * {@code state} unchanged. {@link #updateFromNeighbourShapes} returns {@code state} directly for
 * those blocks — <b>bit-exact by construction</b> ({@link #hasCustomUpdateShape} detects overrides,
 * memoised per {@link Class}; any reflective/link failure is treated conservatively as "has an
 * override" → full vanilla path).
 *
 * <h2>Tier 2 — census-gated whitelist ({@code -Dsuperchunk.worldgen.postProcessSkipWhitelist=true}, default off)</h2>
 * Blocks that <i>do</i> override {@code updateShape} but are hand-verified to be inert (or to depend
 * on a single neighbour) during worldgen post-processing:
 * <ul>
 *   <li><b>Direction-reduced</b> — {@code SnowLayerBlock}/{@code BushBlock}/{@code CarpetBlock}/
 *       {@code CactusBlock} depend only on DOWN; {@code SnowyDirtBlock}/{@code MagmaBlock} only on
 *       UP; {@code CocoaBlock} on its FACING; cave vines on UP+DOWN. Evaluating just that
 *       neighbour matches vanilla's six-direction fold when the block's {@code updateShape} is
 *       direction-independent (e.g. {@code BushBlock} keys off world {@code canSurvive}, not the
 *       passed direction/neighbour).</li>
 *   <li><b>Full-skip</b> — {@code LeavesBlock}, {@code SlabBlock}, {@code TrapDoorBlock},
 *       {@code LightBlock}, {@code BrushableBlock}, {@code BeehiveBlock}, {@code FallingBlock}
 *       already hold their post-processed state at generation time; returning {@code state} is
 *       output-equivalent (the subsequent {@code setBlock} of an <i>identical</i> state early-outs
 *       in {@code LevelChunk.setBlockState}, so no listeners/heightmaps fire).</li>
 * </ul>
 * These are context claims, <b>not</b> provable from the method structure — the leaf case in
 * particular can differ at chunk borders. So Tier 2 is <b>off by default</b> and must clear a
 * <b>zero-divergence world-hash census</b> ({@code tools/worldhash.py}, same seed, flag on vs off)
 * before it is trusted / shipped on. Any block that fails the census is removed from the list.
 *
 * <p>Technique adapted from <b>Bye?Pregen!</b> ({@code byepregen}, MoePus, LGPL-3.0) —
 * {@code com.moepus.byepregen.PostProcess.PostProcessGenerationOptimizer}. SuperChunk defers to Bye
 * Pregen's own implementation when it is installed (see {@code SuperChunkMixinPlugin} /
 * {@code ByePregenCompat}); this class is the standalone-SuperChunk equivalent.
 */
public final class PostProcessNeighbourShapes {

    /** Tier 2 opt-in: skip/reduce blocks that override updateShape but are hand-verified inert here. */
    private static final boolean WHITELIST =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.postProcessSkipWhitelist", "false"));

    private static final Direction[] UP_DOWN = {Direction.UP, Direction.DOWN};

    /** {@code true} iff the class overrides {@code updateShape} somewhere below {@link Block}. Memoised. */
    private static final ClassValue<Boolean> HAS_CUSTOM_UPDATE_SHAPE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return hasCustomUpdateShape(type);
        }
    };

    private PostProcessNeighbourShapes() {
    }

    /**
     * Drop-in for {@code Block.updateFromNeighbourShapes(state, level, pos)}: returns the exact
     * vanilla result, skipping the neighbour loop entirely for the Tier-1 subset (and, when the
     * Tier-2 whitelist is enabled, for the hand-verified whitelist too).
     */
    public static BlockState updateFromNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos) {
        final Block block = state.getBlock();

        // Tier 1: no updateShape override -> provably the identity, no side effects.
        if (!HAS_CUSTOM_UPDATE_SHAPE.get(block.getClass())) {
            return state;
        }

        // Tier 2: census-gated whitelist (off by default).
        if (WHITELIST) {
            if (block instanceof SnowLayerBlock || block instanceof BushBlock
                    || block instanceof CarpetBlock || block instanceof CactusBlock) {
                return updateShapeOnce(state, level, pos, Direction.DOWN);
            }
            if (block instanceof SnowyDirtBlock || block instanceof MagmaBlock) {
                return updateShapeOnce(state, level, pos, Direction.UP);
            }
            if (block instanceof CocoaBlock) {
                return updateShapeOnce(state, level, pos, state.getValue(CocoaBlock.FACING));
            }
            if (block instanceof CaveVinesBlock || block instanceof CaveVinesPlantBlock) {
                return updateShapeFold(state, level, pos, UP_DOWN);
            }
            if (block instanceof LeavesBlock || block instanceof SlabBlock || block instanceof TrapDoorBlock
                    || block instanceof LightBlock || block instanceof BrushableBlock
                    || block instanceof BeehiveBlock || block instanceof FallingBlock) {
                return state;
            }
        }

        // Everything else: verbatim vanilla six-direction fold.
        return Block.updateFromNeighbourShapes(state, level, pos);
    }

    /** One {@code updateShape} evaluation against a single neighbour direction. */
    private static BlockState updateShapeOnce(BlockState state, LevelAccessor level, BlockPos pos, Direction direction) {
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        neighbour.setWithOffset(pos, direction);
        return state.updateShape(direction, level.getBlockState(neighbour), level, pos, neighbour);
    }

    /** Vanilla-order fold of {@code updateShape} over a reduced set of neighbour directions. */
    private static BlockState updateShapeFold(BlockState state, LevelAccessor level, BlockPos pos, Direction[] directions) {
        BlockState result = state;
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (Direction direction : directions) {
            neighbour.setWithOffset(pos, direction);
            result = result.updateShape(direction, level.getBlockState(neighbour), level, pos, neighbour);
        }
        return result;
    }

    private static boolean hasCustomUpdateShape(Class<?> type) {
        for (Class<?> current = type; current != null && current != Block.class; current = current.getSuperclass()) {
            try {
                current.getDeclaredMethod("updateShape",
                        BlockState.class, Direction.class, BlockState.class,
                        LevelAccessor.class, BlockPos.class, BlockPos.class);
                return true;
            } catch (NoSuchMethodException ignored) {
                // keep walking up the hierarchy
            } catch (RuntimeException | LinkageError ignored) {
                // can't introspect this class safely — assume a real override and take the full path
                return true;
            }
        }
        // No override between the block's class and Block: it inherits BlockBehaviour's default
        // no-op updateShape, so updateFromNeighbourShapes provably returns the state unchanged.
        return false;
    }
}
