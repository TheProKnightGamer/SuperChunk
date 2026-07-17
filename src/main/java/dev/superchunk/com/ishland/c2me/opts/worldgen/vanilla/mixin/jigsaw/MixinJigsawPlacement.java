package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.jigsaw;

import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.jigsaw.JigsawShapeTracker;
import net.minecraft.core.Registry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * SuperChunk jigsaw-octree lever — seeds the top-level free region (placement bounds minus the
 * start piece) for the per-structure thread-local model at the head of {@code addPieces}, before
 * the Placer loop runs. See {@link JigsawShapeTracker} / {@link
 * dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.jigsaw.JigsawFreeShape}. Default ON
 * (disable with {@code -Dsuperchunk.worldgen.jigsawOctree=false}).
 */
@Mixin(JigsawPlacement.class)
public class MixinJigsawPlacement {

    @Inject(
            method = "addPieces(Lnet/minecraft/world/level/levelgen/RandomState;IZLnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/structure/PoolElementStructurePiece;Ljava/util/List;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/level/levelgen/structure/pools/alias/PoolAliasLookup;Lnet/minecraft/world/level/levelgen/structure/templatesystem/LiquidSettings;)V",
            at = @At("HEAD")
    )
    private static void superchunk$seedTopLevelFree(
            RandomState randomState,
            int maxDepth,
            boolean useExpansionHack,
            ChunkGenerator chunkGenerator,
            StructureTemplateManager structureTemplateManager,
            LevelHeightAccessor heightAccessor,
            RandomSource random,
            Registry<StructureTemplatePool> pools,
            PoolElementStructurePiece startPiece,
            List<PoolElementStructurePiece> pieces,
            VoxelShape free,
            PoolAliasLookup aliasLookup,
            LiquidSettings liquidSettings,
            CallbackInfo ci
    ) {
        if (!JigsawShapeTracker.ACTIVE) return;
        JigsawShapeTracker.seedTopLevel(free, AABB.of(startPiece.getBoundingBox()));
    }
}
