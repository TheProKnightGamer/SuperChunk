package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.jigsaw;

import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.jigsaw.JigsawShapeTracker;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * SuperChunk jigsaw-octree lever (default ON; disable with {@code -Dsuperchunk.worldgen.jigsawOctree=false})
 * — replaces the three {@code Shapes} operations the
 * jigsaw assembler uses to track "free space" with the {@link JigsawShapeTracker} boundary+octree
 * model:
 * <ul>
 *   <li>the first {@code Shapes.create(AABB)} (the piece-local free region init) is registered,</li>
 *   <li>{@code Shapes.joinIsNotEmpty(free, candidate, ONLY_SECOND)} (the collision test) is answered
 *       by the octree,</li>
 *   <li>{@code Shapes.joinUnoptimized(free, childBox, ONLY_FIRST)} (subtract a placed piece) adds the
 *       box to the octree.</li>
 * </ul>
 * The two later {@code Shapes.create(AABB)} calls (candidate and child boxes) are left intact; their
 * {@code .bounds()} feed the model. When both flags are off every redirect is a straight vanilla
 * pass-through.
 *
 * <p>FORGE 1.20.1 PORT — targets re-verified against the real 1.20.1 mapped sources
 * ({@code forge-1.20.1-47.4.0_mapped_official_1.20.1-sources.jar},
 * {@code net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement.java}). The 1.20.1
 * target method (instance, so the redirect handlers are instance — mixin 0.8.5 static-ness rule):
 * <pre>
 * void tryPlacingChildren(PoolElementStructurePiece p_227265_, MutableObject&lt;VoxelShape&gt; p_227266_,
 *       int p_227267_, boolean p_227268_, LevelHeightAccessor p_227269_, RandomState p_227270_)
 * </pre>
 * in {@code static final class JigsawPlacement$Placer}. Unlike the outer {@code addPieces} (whose
 * 1.21 signature grew PoolAliasLookup/LiquidSettings params), this method's {@code Shapes} call
 * sites are structurally IDENTICAL to 1.21.1 — exactly three {@code Shapes.create(AABB)} calls
 * ({@code mutableobject.setValue(Shapes.create(AABB.of(boundingbox)))} = ordinal 0 local-free init,
 * then the deflated candidate and the child box), exactly one
 * {@code Shapes.joinIsNotEmpty(..., BooleanOp.ONLY_SECOND)} and exactly one
 * {@code Shapes.joinUnoptimized(..., BooleanOp.ONLY_FIRST)} — so the redirects port unchanged.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public class MixinJigsawPlacementPlacer {

    @Redirect(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                    ordinal = 0
            )
    )
    private VoxelShape superchunk$initLocalFree(AABB box) {
        return JigsawShapeTracker.initLocal(box);
    }

    @Redirect(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"
            )
    )
    private boolean superchunk$testFree(VoxelShape free, VoxelShape candidate, BooleanOp op) {
        return JigsawShapeTracker.testFree(free, candidate, op);
    }

    @Redirect(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinUnoptimized(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape superchunk$subtractFree(VoxelShape free, VoxelShape childBox, BooleanOp op) {
        return JigsawShapeTracker.subtractFree(free, childBox, op);
    }
}
