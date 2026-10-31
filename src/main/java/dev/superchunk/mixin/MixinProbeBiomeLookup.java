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
        // per-column climate reuse (ClimateColumns) answers sample() itself
        "net.minecraft.world.level.biome.Climate$Sampler",
        // whole-method replicas (ForeignHooks)
        "net.minecraft.world.level.levelgen.feature.OreFeature",
        // the ore height-scan replica and target memo (OreRuns)
        "net.minecraft.server.level.WorldGenRegion",
        "net.minecraft.world.level.chunk.ChunkAccess",
        "net.minecraft.world.level.chunk.ProtoChunk",
        "net.minecraft.world.level.chunk.LevelChunkSection",
        "net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest",
        "net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest",
        "net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest",
        "net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest",
        // the surface rules DeepSurfaceRules answers for
        "net.minecraft.world.level.levelgen.SurfaceSystem",
        "net.minecraft.world.level.levelgen.SurfaceSystem$1",
        "net.minecraft.world.level.levelgen.SurfaceRules$Context",
        "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRuleSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule",
        "net.minecraft.world.level.levelgen.SurfaceRules$TestRuleSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$TestRule",
        "net.minecraft.world.level.levelgen.SurfaceRules$BlockRuleSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$StateRule",
        "net.minecraft.world.level.levelgen.SurfaceRules$NotConditionSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$NotCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource$1VerticalGradientCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$AbovePreliminarySurface",
        "net.minecraft.world.level.levelgen.SurfaceRules$Context$AbovePreliminarySurfaceCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$LazyYCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$LazyCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$1BiomeCondition",
        // the rest of the vanilla rule tree CeilingDepth vouches for
        "net.minecraft.world.level.levelgen.SurfaceRules$StoneDepthCheck",
        "net.minecraft.world.level.levelgen.SurfaceRules$StoneDepthCheck$1StoneDepthCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$Bandlands",
        "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$YConditionSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$YConditionSource$1YCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$WaterConditionSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$WaterConditionSource$1WaterCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$NoiseThresholdConditionSource",
        "net.minecraft.world.level.levelgen.SurfaceRules$NoiseThresholdConditionSource$1NoiseThresholdCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$Steep",
        "net.minecraft.world.level.levelgen.SurfaceRules$Hole",
        "net.minecraft.world.level.levelgen.SurfaceRules$Temperature",
        "net.minecraft.world.level.levelgen.SurfaceRules$Context$HoleCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$Context$SteepMaterialCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$Context$TemperatureHelperCondition",
        "net.minecraft.world.level.levelgen.SurfaceRules$LazyXZCondition",
        "net.minecraft.core.Holder$Reference",
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
        "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
        // the bundled Lithium's ClassInstanceMultiMap.find replica (collections.entity_filtering)
        "net.minecraft.util.ClassInstanceMultiMap"
}, priority = Integer.MAX_VALUE)
public abstract class MixinProbeBiomeLookup {
}
