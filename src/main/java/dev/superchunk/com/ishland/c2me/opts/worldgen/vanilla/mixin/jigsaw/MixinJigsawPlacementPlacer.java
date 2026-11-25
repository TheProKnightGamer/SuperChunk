package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.jigsaw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.jigsaw.JigsawShapeTracker;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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
 * <p>The three are {@code @WrapOperation}s, not {@code @Redirect}s, so another mod's {@code @Redirect}
 * of the same call still applies, and they pass straight through while another mod hooks
 * {@code tryPlacingChildren} ({@link JigsawShapeTracker#placerUnhooked}): Villager Guard Gunners
 * redirects the collision test and reads the real free shape (GitHub #35).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public class MixinJigsawPlacementPlacer {

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
                    ordinal = 0
            )
    )
    private VoxelShape superchunk$initLocalFree(AABB box, Operation<VoxelShape> original) {
        return JigsawShapeTracker.placerUnhooked() ? JigsawShapeTracker.initLocal(box) : original.call(box);
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"
            )
    )
    private boolean superchunk$testFree(VoxelShape free, VoxelShape candidate, BooleanOp op,
                                        Operation<Boolean> original) {
        return JigsawShapeTracker.placerUnhooked()
                ? JigsawShapeTracker.testFree(free, candidate, op) : original.call(free, candidate, op);
    }

    @WrapOperation(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinUnoptimized(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private VoxelShape superchunk$subtractFree(VoxelShape free, VoxelShape childBox, BooleanOp op,
                                               Operation<VoxelShape> original) {
        return JigsawShapeTracker.placerUnhooked()
                ? JigsawShapeTracker.subtractFree(free, childBox, op) : original.call(free, childBox, op);
    }
}
