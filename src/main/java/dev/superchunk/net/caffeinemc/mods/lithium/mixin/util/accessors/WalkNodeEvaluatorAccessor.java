package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.accessors;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SuperChunk (Forge 1.20.1): {@code WalkNodeEvaluator.getBlockPathTypeRaw} is protected static.
 * Reached through an invoker instead of an access transformer, for the reason in
 * {@link PoiSectionAccessor}.
 */
@Mixin(WalkNodeEvaluator.class)
public interface WalkNodeEvaluatorAccessor {
    @Invoker("getBlockPathTypeRaw")
    static BlockPathTypes lithium$getBlockPathTypeRaw(BlockGetter world, BlockPos pos) {
        throw new AssertionError("mixin not applied");
    }
}
