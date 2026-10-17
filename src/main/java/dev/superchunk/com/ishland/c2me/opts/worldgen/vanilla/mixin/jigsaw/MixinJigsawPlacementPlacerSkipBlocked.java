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
 * entirely when Structure Layout Optimizer is installed (which does this itself).
 *
 * <p>FORGE 1.20.1 PORT — target and {@code @Local} ordinals RE-DERIVED against the real 1.20.1
 * mapped sources ({@code forge-1.20.1-47.4.0_mapped_official_1.20.1-sources.jar},
 * {@code net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement.java}); the neo javadoc's
 * "ordinals match Structure Layout Optimizer's, validated against Mojmap 1.21.1" claim is NOT taken
 * on faith here. The 1.20.1 target method (instance, so the handler is an instance method — mixin
 * 0.8.5 static-ness rule):
 * <pre>
 * void tryPlacingChildren(PoolElementStructurePiece p_227265_, MutableObject&lt;VoxelShape&gt; p_227266_,
 *       int p_227267_, boolean p_227268_, LevelHeightAccessor p_227269_, RandomState p_227270_)
 * </pre>
 * in {@code static final class JigsawPlacement$Placer}. Findings:
 * <ul>
 *   <li>{@code StructurePoolElement.getShuffledJigsawBlocks(StructureTemplateManager, BlockPos,
 *       Rotation, RandomSource)} is signature-IDENTICAL on 1.20.1 (unlike the outer {@code addPieces},
 *       which gained PoolAliasLookup/LiquidSettings params in 1.20.3/1.21) — the {@code @At} target
 *       descriptor ports unchanged.</li>
 *   <li>There are exactly TWO {@code getShuffledJigsawBlocks} call sites in the method, in source
 *       order: the parent piece's (the outer for-each header) = ordinal 0, and the candidate's
 *       ({@code structurepoolelement1.getShuffledJigsawBlocks(..., BlockPos.ZERO, rotation1, ...)}
 *       inside the rotation loop) = ordinal 1. Ordinal 1 is the one we want, as on 1.21.1.</li>
 *   <li>{@code @Local(ordinal = 2) MutableObject} = {@code mutableobject1}, the CURRENT free region
 *       (the piece-local {@code mutableobject} when the connector is inside this piece's box,
 *       otherwise the inherited {@code p_227266_}). The three {@code MutableObject} locals live at
 *       the injection point in LVT order: {@code p_227266_}(0), {@code mutableobject}(1),
 *       {@code mutableobject1}(2).</li>
 *   <li>{@code @Local(ordinal = 1) StructurePoolElement} = {@code structurepoolelement1}, the
 *       candidate ({@code structurepoolelement}, the parent's element, is ordinal 0).</li>
 *   <li>{@code @Local(ordinal = 2) BlockPos} = {@code blockpos2}, the connector position
 *       ({@code blockpos1.relative(direction)} — {@code BlockPos.relative(Direction)} returns
 *       {@code BlockPos} on 1.20.1, so the frame type is BlockPos and the ordinal counts).
 *       {@code blockpos}(0) is the piece position, {@code blockpos1}(1) the jigsaw-block position;
 *       {@code blockpos3..5} are scoped to the inner attach loop, i.e. not yet live here.</li>
 * </ul>
 * So all three ordinals carry over unchanged from neo.
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
