package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.worldgen.BiomeCellLookup;
import dev.superchunk.worldgen.CeilingDepth;
import dev.superchunk.worldgen.DeepSurfacePass;
import dev.superchunk.worldgen.DeepSurfaceRules;
import dev.superchunk.worldgen.ProtoChunkWrites;
import dev.superchunk.worldgen.SurfaceColumn;
import dev.superchunk.worldgen.SurfaceTiming;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * SuperChunk: {@code SurfaceSystem.buildSurface}, statement for statement, on a column held in an array
 * ({@link SurfaceColumn}). Vanilla reads every block of each column through the anonymous {@code BlockColumn}, twice
 * for most (the stone-depth scan reads ahead, then the main loop reads again), and runs the rules for every stone block
 * down to the bottom of the world. Here the reads are array loads, filled a section at a time; below the preliminary
 * surface, a height whose rule answer {@link DeepSurfaceRules} knows takes that answer without {@code updateY} or
 * {@code tryApply}, and a column is walked only as deep as {@link DeepSurfacePass} needs, which does the rest a section at
 * a time. The stone depth below a block is read only as far as {@link CeilingDepth} says the rules can tell apart.
 * Writes, the badlands and frozen-ocean extensions, the rule context and the biome lookups are vanilla's own (with the
 * surface rules' biome getter of {@code MixinSurfaceSystemCellBiome}).
 *
 * <p>The same blocks, writes, heightmap updates, post-processing marks and random draws, in the same order apart from
 * the deep pass's writes, which come after the last column ({@link DeepSurfacePass} says why that changes nothing):
 * {@code -Dsuperchunk.debug.surfaceHash} hashes every chunk's surface for a comparison with the original. Runs for an
 * exact {@code ProtoChunk} without below-zero retrogen whose heightmaps {@link ProtoChunkWrites} can bind, while no
 * other mod's mixin is on {@code SurfaceSystem} or its column, or on the chunk reads it replaces. Kill switch
 * {@code -Dsuperchunk.worldgen.fastSurface=false}.
 */
@Mixin(SurfaceSystem.class)
public abstract class MixinSurfaceSystemFast {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.fastSurface", "true"));

    @Unique
    private static byte superchunk$hooks;

    @Shadow
    @Final
    private BlockState defaultBlock;

    @Shadow
    private boolean isStone(BlockState state) {
        throw new AssertionError();
    }

    @Shadow
    private void erodedBadlandsExtension(BlockColumn blockColumn, int x, int z, int height, LevelHeightAccessor level) {
        throw new AssertionError();
    }

    @Shadow
    private void frozenOceanExtension(int minSurfaceLevel, Biome biome, BlockColumn blockColumn,
                                      BlockPos.MutableBlockPos topWaterPos, int x, int z, int height) {
        throw new AssertionError();
    }

    // past this many blocks, scan to the run's bottom as vanilla does
    @Unique
    private static final int MAX_LIMIT = 32;

    /** The stone depth below y if the solid run under it ends within {@code limit} blocks, else {@code limit + 1}. */
    @Unique
    private int superchunk$depthBelow(SurfaceColumn column, int y, int limit) {
        for (int d = 1; d <= limit; d++) {
            if (!this.isStone(column.getBlock(y - d))) {
                return d;
            }
        }
        return limit + 1;
    }

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            String foreign = dev.superchunk.MixinTargetScan.foreignMixin("net.minecraft.world.level.levelgen.SurfaceSystem",
                    "net.minecraft.world.level.levelgen.SurfaceSystem$1");
            state = foreign != null && foreign.startsWith("<") ? dev.superchunk.worldgen.ForeignHooks.UNKNOWN
                    : foreign != null ? dev.superchunk.worldgen.ForeignHooks.HOOKED
                    : dev.superchunk.worldgen.ForeignHooks.state("The surface pass copy (SurfaceSystem.buildSurface)",
                            "net.minecraft.world.level.chunk.ProtoChunk#getBlockState",
                            "net.minecraft.world.level.chunk.ChunkAccess#getSection",
                            "net.minecraft.world.level.chunk.LevelChunkSection#hasOnlyAir");
            if (foreign != null && !foreign.startsWith("<")) {
                org.slf4j.LoggerFactory.getLogger("SuperChunk-Compat").info(
                        "[SuperChunk] The surface pass copy (SurfaceSystem.buildSurface) stands down: {} also changes it.", foreign);
            }
            superchunk$hooks = state;
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    @WrapMethod(method = "buildSurface", require = 0)
    private void superchunk$fastSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes,
                                        boolean useLegacyRandomSource, WorldGenerationContext context, ChunkAccess chunk,
                                        NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource, Operation<Void> original) {
        Heightmap[] heightmaps;
        if (!SUPERCHUNK$ENABLED || (SurfaceTiming.off() & SurfaceTiming.FAST_SURFACE) != 0
                || chunk.getClass() != ProtoChunk.class || ((ProtoChunk) chunk).getBelowZeroRetrogen() != null
                || !superchunk$unhooked() || (heightmaps = ProtoChunkWrites.bind((ProtoChunk) chunk)) == null) {
            original.call(randomState, biomeManager, biomes, useLegacyRandomSource, context, chunk, noiseChunk, ruleSource);
            return;
        }
        superchunk$buildSurface(randomState, biomeManager, biomes, useLegacyRandomSource, context, (ProtoChunk) chunk, heightmaps,
                noiseChunk, ruleSource);
    }

    @Unique
    private void superchunk$buildSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes,
                                         boolean useLegacyRandomSource, WorldGenerationContext context, ProtoChunk chunk,
                                         Heightmap[] heightmaps, NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource) {
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
        ChunkPos chunkpos = chunk.getPos();
        int i = chunkpos.getMinBlockX();
        int j = chunkpos.getMinBlockZ();
        SurfaceColumn blockcolumn = new SurfaceColumn(chunk, heightmaps, blockpos$mutableblockpos);
        SurfaceRules.Context surfacerules$context = ISurfaceRulesContextAccess.superchunk$create(
                (SurfaceSystem) (Object) this, randomState, chunk, noiseChunk,
                BiomeCellLookup.of(biomeManager, biomeManager::getBiome), biomes, context);
        ISurfaceRulesContextAccess access = (ISurfaceRulesContextAccess) (Object) surfacerules$context;
        SurfaceRules.SurfaceRule surfacerules$surfacerule = ruleSource.apply(surfacerules$context);
        BlockPos.MutableBlockPos blockpos$mutableblockpos1 = new BlockPos.MutableBlockPos();
        DeepSurfaceRules.Table deep = DeepSurfaceRules.ENABLED && (SurfaceTiming.off() & SurfaceTiming.DEEP_RULES) == 0
                ? DeepSurfaceRules.table(ruleSource, context, surfacerules$context) : null;
        DeepSurfaceRules.Randoms randoms = deep != null ? new DeepSurfaceRules.Randoms(randomState) : null;
        DeepSurfacePass pass = deep != null ? DeepSurfacePass.begin(chunk, heightmaps, deep, this.defaultBlock, randoms) : null;
        CeilingDepth.Limits ceiling = (SurfaceTiming.off() & SurfaceTiming.CEILING_DEPTH) == 0 ? CeilingDepth.of(ruleSource) : null;

        for (int k = 0; k < 16; k++) {
            for (int l = 0; l < 16; l++) {
                int i1 = i + k;
                int j1 = j + l;
                int k1 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, k, l) + 1;
                blockcolumn.moveTo(i1, j1);
                Holder<Biome> holder = biomeManager.getBiome(blockpos$mutableblockpos1.set(i1, useLegacyRandomSource ? 0 : k1, j1));
                if (holder.is(Biomes.ERODED_BADLANDS)) {
                    this.erodedBadlandsExtension(blockcolumn, i1, j1, k1, chunk);
                }

                int l1 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, k, l) + 1;
                access.superchunk$updateXZ(i1, j1);
                // how many blocks under a block the rules can tell apart; past it, the scan to the run's bottom is skipped
                int limit = ceiling != null ? ceiling.limit(access.superchunk$surfaceDepth()) : Integer.MAX_VALUE;
                boolean capped = limit <= MAX_LIMIT;
                int i2 = 0;
                int j2 = Integer.MIN_VALUE;
                int k2 = Integer.MAX_VALUE;
                int l2 = chunk.getMinBuildHeight();
                // the rules' answer is the table's below this (abovePreliminarySurface() is false there)
                int deepBelow = deep != null ? access.superchunk$getMinSurfaceLevel() : Integer.MIN_VALUE;
                // the pass does the heights below this after the last column
                int walkLow = pass != null ? pass.walkLow(k, l, deepBelow, l2) : l2;

                for (int i3 = l1; i3 >= walkLow; i3--) {
                    BlockState blockstate = blockcolumn.getBlock(i3);
                    if (blockstate.isAir()) {
                        i2 = 0;
                        j2 = Integer.MIN_VALUE;
                    } else if (!blockstate.getFluidState().isEmpty()) {
                        if (j2 == Integer.MIN_VALUE) {
                            j2 = i3 + 1;
                        }
                    } else {
                        if (!capped && k2 >= i3) {
                            k2 = DimensionType.WAY_BELOW_MIN_Y;

                            for (int j3 = i3 - 1; j3 >= l2 - 1; j3--) {
                                BlockState blockstate1 = blockcolumn.getBlock(j3);
                                if (!this.isStone(blockstate1)) {
                                    k2 = j3 + 1;
                                    break;
                                }
                            }
                        }

                        i2++;
                        int k3 = capped ? 0 : i3 - k2 + 1;
                        Object answer = i3 < deepBelow ? deep.at(i3) : DeepSurfaceRules.UNKNOWN;
                        if (answer != DeepSurfaceRules.UNKNOWN) {
                            if (blockstate == this.defaultBlock && answer != DeepSurfaceRules.NONE) {
                                answer = DeepSurfaceRules.resolve(answer, i1, i3, j1, randoms);
                                if (answer != DeepSurfaceRules.NONE) {
                                    blockcolumn.setBlock(i3, (BlockState) answer);
                                }
                            }
                        } else {
                            if (capped) {
                                k3 = this.superchunk$depthBelow(blockcolumn, i3, limit);
                            }
                            access.superchunk$updateY(i2, k3, j2, i1, i3, j1);
                            if (blockstate == this.defaultBlock) {
                                BlockState blockstate2 = surfacerules$surfacerule.tryApply(i1, i3, j1);
                                if (blockstate2 != null) {
                                    blockcolumn.setBlock(i3, blockstate2);
                                }
                            }
                        }
                    }
                }

                if (holder.is(Biomes.FROZEN_OCEAN) || holder.is(Biomes.DEEP_FROZEN_OCEAN)) {
                    this.frozenOceanExtension(access.superchunk$getMinSurfaceLevel(), holder.value(), blockcolumn,
                            blockpos$mutableblockpos1, i1, j1, k1);
                }
            }
        }

        if (pass != null) {
            pass.run();
        }
    }
}
