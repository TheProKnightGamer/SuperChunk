package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NoiseChunk.class)
public interface IChunkNoiseSampler {

    @Accessor("cellStartBlockX")
    int getStartBlockX();

    @Accessor("cellStartBlockY")
    int getStartBlockY();

    @Accessor("cellStartBlockZ")
    int getStartBlockZ();

    @Accessor("cellWidth")
    int getHorizontalCellBlockCount();

    @Accessor("cellHeight")
    int getVerticalCellBlockCount();

    @Accessor("interpolating")
    boolean getIsInInterpolationLoop();

    @Accessor("fillingCell")
    boolean getIsSamplingForCaches();

    @Accessor("firstNoiseX")
    int getStartBiomeX();

    @Accessor("firstNoiseZ")
    int getStartBiomeZ();

    @Accessor("cellCountXZ")
    int getHorizontalCellCount();

    @Accessor("cellCountY")
    int getVerticalCellCount();

    @Accessor("cellNoiseMinY")
    int getMinimumCellY();

    // SuperChunk GPU: first cell X/Z (block-cell index of the chunk's first cell),
    // needed to reconstruct the whole-chunk cell-corner lattice for dispatch coalescing.
    @Accessor("firstCellX")
    int getFirstCellX();

    @Accessor("firstCellZ")
    int getFirstCellZ();

    @Accessor("inCellX")
    int getCellBlockX();

    @Accessor("inCellY")
    int getCellBlockY();

    @Accessor("inCellZ")
    int getCellBlockZ();

    // SuperChunk COMPACT-IDS (Stage 5): the noise-stage block-state filler chain
    // (a MaterialRuleList; size >= 2 <=> the dimension's OreVeinifier filler exists,
    // i.e. oreVeinsEnabled) and the chunk's beardifier (its beardSkip mixin exposes
    // the proven-exact per-chunk beard AABB). Both read WORKER-side at the batch
    // prefetch seam for the decide-kernel aux capture.
    @Accessor("blockStateRule")
    NoiseChunk.BlockStateFiller getBlockStateRule();

    @Accessor("beardifier")
    net.minecraft.world.level.levelgen.DensityFunctions.BeardifierOrMarker getBeardifier();

}
