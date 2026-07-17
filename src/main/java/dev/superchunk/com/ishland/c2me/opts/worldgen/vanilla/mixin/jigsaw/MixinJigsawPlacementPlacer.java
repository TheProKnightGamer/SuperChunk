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
