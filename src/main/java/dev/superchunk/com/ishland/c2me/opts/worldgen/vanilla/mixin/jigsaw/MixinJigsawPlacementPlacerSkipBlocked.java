package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.jigsaw;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.jigsaw.JigsawShapeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * SuperChunk skip-blocked-jigsaw lever (default OFF; enable with
 * {@code -Dsuperchunk.worldgen.jigsawSkipBlocked=true}) — a clean-room adaptation of the
 * blocked-jigsaw skip from TelepathicGrunt's Structure Layout Optimizer (MIT), layered on
 * SuperChunk's existing jigsaw octree ({@link JigsawShapeTracker} / {@code JigsawFreeShape}).
 *
 * <p>Modifies the RETURN value of the CANDIDATE piece's
 * {@code StructurePoolElement.getShuffledJigsawBlocks} (ordinal 1) inside
 * {@code JigsawPlacement$Placer.tryPlacingChildren}: when that candidate is RIGID and its connector
 * position is already outside the placement boundary or inside a placed piece, the list is replaced
 * with an empty one so the subsequent {@code canAttach}/free-space loop does nothing — the candidate
 * could never have been placed there. All the decision / verify / fallback logic lives in
 * {@link JigsawShapeTracker#maybeSkipBlockedCandidate} (one place, mirroring the octree redirects).
 *
 * <p>Because this is a {@code @ModifyExpressionValue} it runs AFTER the vanilla RNG-consuming shuffle,
 * so the random stream is byte-for-byte unchanged. Gated at LOAD on the flag (this class is only
 * applied when the lever is enabled), and — like the other {@code jigsaw.*} mixins — stood down
 * entirely when Structure Layout Optimizer is installed (which does this itself). The {@code @Local}
 * ordinals match Structure Layout Optimizer's, validated against the same Mojmap 1.21.1 mappings.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
public class MixinJigsawPlacementPlacerSkipBlocked {

    @ModifyExpressionValue(
            method = "tryPlacingChildren",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/pools/StructurePoolElement;getShuffledJigsawBlocks(Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/util/RandomSource;)Ljava/util/List;",
                    ordinal = 1
            )
    )
    private List<StructureTemplate.StructureBlockInfo> superchunk$skipBlockedCandidate(
            List<StructureTemplate.StructureBlockInfo> original,
            @Local(ordinal = 2) MutableObject<VoxelShape> free,
            @Local(ordinal = 1) StructurePoolElement candidate,
            @Local(ordinal = 2) BlockPos connectorPos
    ) {
        return JigsawShapeTracker.maybeSkipBlockedCandidate(original, free.getValue(), candidate, connectorPos);
    }
}
