package dev.superchunk.net.caffeinemc.mods.lithium.common.block;

import net.minecraft.world.level.block.state.BlockState;

public interface BlockCountingSection {
    boolean lithium$mayContainAny(TrackedBlockStatePredicate trackedBlockStatePredicate);

    void lithium$trackBlockStateChange(BlockState newState, BlockState oldState);

    /**
     * SuperChunk compact-ids bulk write support: exactly equivalent to calling
     * {@link #lithium$trackBlockStateChange(BlockState, BlockState)} {@code count}
     * times with the same {@code (newState, oldState)} pair. The mixin implementation
     * applies the count arithmetically (one pass over the changed flag bits) instead
     * of per-block; this default is the semantic reference/fallback.
     */
    default void lithium$trackBlockStateChangeBulk(BlockState newState, BlockState oldState, int count) {
        for (int i = 0; i < count; i++) {
            lithium$trackBlockStateChange(newState, oldState);
        }
    }
}
