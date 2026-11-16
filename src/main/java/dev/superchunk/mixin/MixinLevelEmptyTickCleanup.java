package dev.superchunk.mixin;

import dev.superchunk.worldgen.EmptyLevelCleanup;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops block entity tickers and pending {@code onLoad} entries of block entities that are already
 * removed, for the ticks on which the level does not tick block entities at all.
 *
 * <p>{@code Level.tickBlockEntities} is the only place that prunes {@code blockEntityTickers} and
 * empties {@code freshBlockEntities}, and {@code ServerLevel.tick} stops calling it once the level
 * has had no players and no forced chunks for 300 ticks. A pre-generation with nobody online then
 * adds to both lists for every chunk it loads and never removes anything: one wrapper per ticking
 * block entity and every block entity of every loaded chunk. (On 1.20.1 it is much worse, because
 * there each stale wrapper also keeps its whole {@code LevelChunk} alive.) Vanilla has the same
 * leak; at vanilla speed it takes hours to matter.
 *
 * <p>Only entries of removed block entities are dropped. Nothing is ticked and no {@code onLoad}
 * is called here, so block entities in loaded chunks behave exactly as before.
 */
@Mixin(Level.class)
public abstract class MixinLevelEmptyTickCleanup implements EmptyLevelCleanup {

    @Shadow @Final protected List<TickingBlockEntity> blockEntityTickers;
    @Shadow @Final private List<TickingBlockEntity> pendingBlockEntityTickers;
    @Shadow private boolean tickingBlockEntities;
    @Shadow(remap = false) @Final private ArrayList<BlockEntity> freshBlockEntities;
    @Shadow(remap = false) @Final private ArrayList<BlockEntity> pendingFreshBlockEntities;

    @Override
    public void superchunk$dropRemovedBlockEntities() {
        if (this.tickingBlockEntities) {
            return; // not expected: the caller runs after the level's own tick
        }
        if (!this.pendingBlockEntityTickers.isEmpty()) {
            this.pendingBlockEntityTickers.removeIf(TickingBlockEntity::isRemoved);
        }
        if (!this.blockEntityTickers.isEmpty()) {
            this.blockEntityTickers.removeIf(TickingBlockEntity::isRemoved);
        }
        if (!this.pendingFreshBlockEntities.isEmpty()) {
            this.pendingFreshBlockEntities.removeIf(BlockEntity::isRemoved);
        }
        if (!this.freshBlockEntities.isEmpty()) {
            this.freshBlockEntities.removeIf(BlockEntity::isRemoved);
        }
    }
}
