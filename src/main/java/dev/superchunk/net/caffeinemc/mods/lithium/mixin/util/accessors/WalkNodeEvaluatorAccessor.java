package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.accessors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SuperChunk: {@code WalkNodeEvaluator.getPathTypeFromState} is protected static. Lithium widened it
 * with an access transformer, which crashes any mod that {@code @Overwrite}s it at vanilla
 * visibility ("cannot reduce visibiliy", GitHub #31), so it is reached through an invoker.
 */
@Mixin(WalkNodeEvaluator.class)
public interface WalkNodeEvaluatorAccessor {
    @Invoker("getPathTypeFromState")
    static PathType lithium$getPathTypeFromState(BlockGetter world, BlockPos pos) {
        throw new AssertionError("mixin not applied");
    }
}
