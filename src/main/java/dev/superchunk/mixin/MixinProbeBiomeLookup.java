package dev.superchunk.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * No-op probe applied after every other mixin on the classes whose code SuperChunk's shortcuts
 * replace or bypass (biome and climate lookups, ore RNG, whole-method replicas, the GPU block
 * writer); {@code SuperChunkMixinPlugin.postApply} scans the finished classes for other mods'
 * mixins ({@link dev.superchunk.MixinTargetScan}).
 */
@Mixin(targets = {
        "net.minecraft.world.level.biome.BiomeManager",
        "net.minecraft.world.level.biome.Climate$ParameterList",
        "net.minecraft.world.level.biome.Climate$RTree",
        "net.minecraft.world.level.biome.Climate$RTree$Node",
        "net.minecraft.world.level.biome.Climate$RTree$SubTree",
        "net.minecraft.world.level.biome.Climate$RTree$Leaf",
        "net.minecraft.world.level.levelgen.XoroshiroRandomSource",
        "net.minecraft.world.level.levelgen.XoroshiroRandomSource$XoroshiroPositionalRandomFactory",
        "net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus",
        // climate search inputs the flat index inlines
        "net.minecraft.world.level.biome.Climate$TargetPoint",
        "net.minecraft.world.level.biome.Climate$Parameter",
        // whole-method replicas (ForeignHooks)
        "net.minecraft.world.level.levelgen.feature.OreFeature",
        "net.minecraft.world.level.levelgen.placement.PlacedFeature",
        "net.minecraft.world.level.levelgen.placement.PlacementFilter",
        "net.minecraft.world.level.levelgen.placement.RepeatingPlacement",
        "net.minecraft.world.level.levelgen.placement.InSquarePlacement",
        "net.minecraft.world.level.levelgen.placement.HeightmapPlacement",
        "net.minecraft.world.level.levelgen.placement.HeightRangePlacement",
        "net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement",
        "net.minecraft.world.level.levelgen.Heightmap",
        "net.minecraft.world.level.chunk.PalettedContainer",
        // the block-fill path the GPU compact-ids consume replaces, and the ore-vein RNG consumer
        "net.minecraft.world.level.levelgen.NoiseChunk",
        "net.minecraft.world.level.levelgen.Beardifier",
        "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer",
        "net.minecraft.world.level.levelgen.material.MaterialRuleList",
        "net.minecraft.world.level.levelgen.OreVeinifier",
        "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator"
}, priority = Integer.MAX_VALUE)
public abstract class MixinProbeBiomeLookup {
}
