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
import net.minecraft.world.level.block.state.BlockBehaviour;
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
 * {@code updateShape} (verified against the 1.20.1 source: src1201 Block:139-149, unpatched by
 * Forge).
 *
 * <p>This class provides two tiers, selected per call by two static-final flags (JIT-folded):
 *
 * <h2>Tier 1 — proven-safe subset (default on)</h2>
 * {@code BlockBehaviour.updateShape}'s default body is {@code return state} (src1201
 * BlockBehaviour:140-142 — {@code return p_60541_;}). So for any block whose class hierarchy does
 * <b>not</b> override {@code updateShape}, all six iterations are pure no-ops and the method provably
 * returns {@code state} unchanged. {@link #updateFromNeighbourShapes} returns {@code state} directly
 * for those blocks — <b>bit-exact by construction</b> ({@link #hasCustomUpdateShape} detects
 * overrides, memoised per {@link Class}; any reflective/link failure is treated conservatively as
 * "has an override" → full vanilla path). The hierarchy walk stops at {@link Block} exclusive, which
 * is still correct on 1.20.1: {@code Block} itself declares no {@code updateShape}, so the walk
 * never sees anything but real overrides and {@code BlockBehaviour}'s identity default.
 *
 * <p><b>Forge 1.20.1 — the reflective probe MUST be name-mapped.</b> NeoForge 1.21's runtime is
 * mojmap, so the neo original could hand the literal {@code "updateShape"} to
 * {@code getDeclaredMethod}. Forge 1.20.1 production is <b>SRG-named</b> ({@code m_7417_}); the
 * literal would miss on every class, the walk would fall off the top, and Tier 1 would then claim
 * "no override" for blocks that <i>do</i> override it — silent worldgen divergence, exactly the
 * failure mode this class exists to avoid. The name is therefore resolved the way the rest of this
 * tree resolves reflective MC members (see
 * {@code net.caffeinemc.mods.lithium.forge.ForgeMappingInformation}):
 * {@code ObfuscationReflectionHelper.remapName(METHOD, "m_7417_")} — SRG→mojmap in dev, identity in
 * production. {@code m_7417_} is tsrg-verified ({@code srg_to_official_1.20.1.tsrg}: it is the id on
 * {@code BlockBehaviour.updateShape} — the entry under the {@code BlockBehaviour} class block — and
 * on every vanilla override). The resolution is then <b>self-checked</b> against
 * {@code BlockBehaviour}, which is known to declare the method, and backed by a mapping-agnostic
 * signature scan if the name misses; see {@link #resolveUpdateShapeName()}. If nothing resolves,
 * {@link #hasCustomUpdateShape} answers {@code true} for everything, i.e. the whole Tier-1 skip
 * stands down to the verbatim vanilla fold — a mapping surprise can only cost the optimisation,
 * never correctness.
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
 * <b>Forge 1.20.1</b>: every whitelisted class exists on 1.20.1 with the same role (incl.
 * {@code BrushableBlock}, added in 1.20), but the Tier-2 claims were argued from 1.21.1 block
 * behaviour and this build has NOT re-run the census on 1.20.1 — the default-off posture carries the
 * divergence.
 *
 * <p>Technique adapted from <b>Bye?Pregen!</b> ({@code byepregen}, MoePus, LGPL-3.0) —
 * {@code com.moepus.byepregen.PostProcess.PostProcessGenerationOptimizer}. SuperChunk defers to Bye
 * Pregen's own implementation when it is installed (see {@code SuperChunkMixinPlugin} /
 * {@code ByePregenCompat}); this class is the standalone-SuperChunk equivalent.
 */
public final class PostProcessNeighbourShapes {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("SuperChunk-PostProcess");

    /** Tier 2 opt-in: skip/reduce blocks that override updateShape but are hand-verified inert here. */
    private static final boolean WHITELIST =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.postProcessSkipWhitelist", "false"));

    private static final Direction[] UP_DOWN = {Direction.UP, Direction.DOWN};

    /**
     * SRG id of {@code BlockBehaviour.updateShape(BlockState, Direction, BlockState, LevelAccessor,
     * BlockPos, BlockPos)} on 1.20.1 (tsrg-verified). Forge 1.20.1 only.
     */
    private static final String UPDATE_SHAPE_SRG = "m_7417_";

    /**
     * The runtime name of {@code updateShape}: mojmap in dev runs, {@code m_7417_} in production, or
     * {@code null} if it could not be resolved at all (see {@link #resolveUpdateShapeName()}).
     */
    private static final String UPDATE_SHAPE_NAME = resolveUpdateShapeName();

    /**
     * {@code false} disables the whole Tier-1 skip (every class is then reported as overriding), so a
     * mapping surprise degrades to verbatim vanilla instead of to silently-skipped
     * {@code updateShape} folds.
     */
    private static final boolean PROBE_USABLE = UPDATE_SHAPE_NAME != null;

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

    /**
     * Resolves the runtime name of {@code updateShape}, or {@code null} if it cannot be established.
     *
     * <p>Two stages, both <b>self-checked against {@link BlockBehaviour}</b> — the class that declares
     * the identity default, and therefore the one class that is guaranteed to have the method under
     * every mapping:
     * <ol>
     *   <li>the tree's normal mapping idiom,
     *       {@code ObfuscationReflectionHelper.remapName(METHOD, "m_7417_")} (SRG&rarr;mojmap in dev,
     *       identity in production);</li>
     *   <li>if that name does not resolve, a <b>mapping-agnostic</b> scan of
     *       {@code BlockBehaviour.getDeclaredMethods()} for the unique non-synthetic method with the
     *       exact {@code (BlockState, Direction, BlockState, LevelAccessor, BlockPos, BlockPos) ->
     *       BlockState} signature. On 1.20.1 that descriptor is unique on {@code BlockBehaviour}
     *       (recomp-jar verified), so the match is unambiguous whatever the members are named.</li>
     * </ol>
     * Stage 2 exists because stage 1 is a <b>runtime-only</b> contract: a wrong or stale SRG id is
     * invisible to the compiler, and the failure it produces is a silently INERT optimisation
     * (correct output, no speedup) that only a production boot would reveal. With the backstop, the
     * feature survives a mapping surprise instead of needing a production-boot QA gate to detect one.
     * If BOTH stages fail the whole Tier-1 skip stands down to the verbatim vanilla fold.
     */
    private static String resolveUpdateShapeName() {
        String mapped = null;
        try {
            mapped = net.minecraftforge.fml.util.ObfuscationReflectionHelper.remapName(
                    cpw.mods.modlauncher.api.INameMappingService.Domain.METHOD, UPDATE_SHAPE_SRG);
            BlockBehaviour.class.getDeclaredMethod(mapped,
                    BlockState.class, Direction.class, BlockState.class,
                    LevelAccessor.class, BlockPos.class, BlockPos.class);
            return mapped;
        } catch (Throwable ignored) {
            // Fall through to the descriptor scan.
        }
        try {
            String found = null;
            for (java.lang.reflect.Method m : BlockBehaviour.class.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()
                        || m.getReturnType() != BlockState.class
                        || !java.util.Arrays.equals(m.getParameterTypes(), new Class<?>[]{
                                BlockState.class, Direction.class, BlockState.class,
                                LevelAccessor.class, BlockPos.class, BlockPos.class})) {
                    continue;
                }
                if (found != null) {
                    // Ambiguous: refuse rather than guess which one is updateShape.
                    found = null;
                    break;
                }
                found = m.getName();
            }
            if (found != null) {
                LOGGER.warn("[post-process] '{}' (SRG {}) did not resolve on BlockBehaviour; fell back to the "
                                + "signature scan, which found '{}'. The no-op skip stays active, but the SRG id "
                                + "in this class is stale for this runtime and should be re-checked.",
                        mapped, UPDATE_SHAPE_SRG, found);
                return found;
            }
        } catch (Throwable ignored) {
            // Fall through to the stand-down below.
        }
        LOGGER.warn("[post-process] could not resolve BlockBehaviour.updateShape (SRG {}, mapped '{}') by name or "
                        + "by signature — the no-op skip stands down to the verbatim vanilla neighbour-shape fold.",
                UPDATE_SHAPE_SRG, mapped);
        return null;
    }

    private static boolean hasCustomUpdateShape(Class<?> type) {
        if (!PROBE_USABLE) {
            // Mapping resolution failed: treat every block as overriding -> full vanilla path.
            return true;
        }
        for (Class<?> current = type; current != null && current != Block.class; current = current.getSuperclass()) {
            try {
                current.getDeclaredMethod(UPDATE_SHAPE_NAME,
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
