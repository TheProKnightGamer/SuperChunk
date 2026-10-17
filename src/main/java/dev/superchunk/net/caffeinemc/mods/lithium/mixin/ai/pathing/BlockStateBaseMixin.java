package dev.superchunk.net.caffeinemc.mods.lithium.mixin.ai.pathing;

import dev.superchunk.net.caffeinemc.mods.lithium.common.ai.pathing.BlockStatePathingCache;
import dev.superchunk.net.caffeinemc.mods.lithium.common.world.blockview.SingleBlockBlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.apache.commons.lang3.Validate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * PORT-1.20.1 (RE-DERIVE, plan 6.2 ai.pathing / radium AbstractBlockStateMixin blueprint):
 * PathType -> BlockPathTypes; the 1.21 PathfindingContext does not exist on 1.20.1, so the
 * single-position neighbor probe calls the 1.20.1 static
 * WalkNodeEvaluator.checkNeighbourBlocks(BlockGetter, MutableBlockPos, BlockPathTypes) with a
 * null fallback on BlockPos.ZERO (radium bytecode: m_77643_ + m_77607_). The
 * WalkNodeEvaluatorMixin single-pos-call inject makes the null-fallback call examine ONLY the
 * origin position. getBlockPathTypeRaw (m_77643_, protected static) is widened by the AT.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin implements BlockStatePathingCache {
    private BlockPathTypes pathNodeType = null;
    private BlockPathTypes pathNodeTypeNeighbor = null;

    @Override
    public void lithium$initializePathNodeTypeCache() {
        // Reset the cached path node types, to ensure they are re-calculated.
        this.pathNodeType = null;
        this.pathNodeTypeNeighbor = null;

        BlockState state = this.asState();

        SingleBlockBlockView singleBlockBlockView = SingleBlockBlockView.of(state, BlockPos.ZERO);
        try {
            this.pathNodeType = Validate.notNull(WalkNodeEvaluator.getBlockPathTypeRaw(singleBlockBlockView, BlockPos.ZERO), "Block has no common path node type!");
        } catch (SingleBlockBlockView.SingleBlockViewException | ClassCastException e) {
            //This is usually hit by shulker boxes, as their hitbox depends on the block entity, and the node type depends on the hitbox
            //Also catch ClassCastException in case some modded code casts BlockView to ChunkCache
            this.pathNodeType = null;
        }
        try {
            //Passing null as previous node type to the method signals to other lithium mixins that we only want the neighbor behavior of this block and not its neighbors
            //Using exceptions for control flow, but this way we do not need to copy the code for the cache initialization, reducing required maintenance and improving mod compatibility
            //noinspection DataFlowIssue
            this.pathNodeTypeNeighbor = WalkNodeEvaluator.checkNeighbourBlocks(singleBlockBlockView, BlockPos.ZERO.mutable(), null);
            //noinspection ConstantValue
            if (this.pathNodeTypeNeighbor == null) {
                this.pathNodeTypeNeighbor = BlockPathTypes.OPEN;
            }
        } catch (SingleBlockBlockView.SingleBlockViewException | NullPointerException | ClassCastException e) {
            this.pathNodeTypeNeighbor = null;
        }
    }

    @Override
    public BlockPathTypes lithium$getPathNodeType() {
        return this.pathNodeType;
    }

    @Override
    public BlockPathTypes lithium$getNeighborPathNodeType() {
        return this.pathNodeTypeNeighbor;
    }

    @Shadow
    protected abstract BlockState asState();

    @Shadow
    public abstract Block getBlock();
}
