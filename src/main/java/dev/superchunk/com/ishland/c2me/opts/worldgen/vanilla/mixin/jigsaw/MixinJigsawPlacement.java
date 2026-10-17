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
 *
 * <p>FORGE 1.20.1 PORT — signature re-derived against the real 1.20.1 mapped sources
 * ({@code forge-1.20.1-47.4.0_mapped_official_1.20.1-sources.jar},
 * {@code net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement.java}). The neo
 * (1.21.1) target carries two extra trailing params that DO NOT EXIST on 1.20.1:
 * {@code pools.alias.PoolAliasLookup} (added 1.20.3) and {@code templatesystem.LiquidSettings}
 * (added 1.21). The 1.20.1 target is the private static overload:
 * <pre>
 * private static void addPieces(RandomState p_227211_, int p_227212_, boolean p_227213_,
 *       ChunkGenerator p_227214_, StructureTemplateManager p_227215_,
 *       LevelHeightAccessor p_227216_, RandomSource p_227217_,
 *       Registry&lt;StructureTemplatePool&gt; p_227218_, PoolElementStructurePiece p_227219_,
 *       List&lt;PoolElementStructurePiece&gt; p_227220_, VoxelShape p_227221_)
 * </pre>
 * Everything else (param order, the free-shape construction
 * {@code Shapes.join(create(bounds), create(AABB.of(startBox)), ONLY_FIRST)} in the caller, and
 * the seeding semantics) is identical to 1.21.1, so dropping the two params preserves the
 * lever's semantics exactly.
 */
@Mixin(JigsawPlacement.class)
public class MixinJigsawPlacement {

    @Inject(
            method = "addPieces(Lnet/minecraft/world/level/levelgen/RandomState;IZLnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/structure/PoolElementStructurePiece;Ljava/util/List;Lnet/minecraft/world/phys/shapes/VoxelShape;)V",
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
            CallbackInfo ci
    ) {
        if (!JigsawShapeTracker.ACTIVE) return;
        JigsawShapeTracker.seedTopLevel(free, AABB.of(startPiece.getBoundingBox()));
    }
}
