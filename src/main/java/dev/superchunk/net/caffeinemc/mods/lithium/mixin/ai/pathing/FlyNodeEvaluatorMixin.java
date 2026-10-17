package dev.superchunk.net.caffeinemc.mods.lithium.mixin.ai.pathing;

import dev.superchunk.net.caffeinemc.mods.lithium.common.ai.pathing.PathNodeCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * PORT-1.20.1 (radium BirdPathNodeMakerMixin blueprint, plan 6.2): the redirect target is the
 * inherited static checkNeighbourBlocks(BlockGetter, MutableBlockPos, BlockPathTypes) invoked
 * from the 4-arg getBlockPathType overload (owner compiles as FlyNodeEvaluator).
 */
@Mixin(FlyNodeEvaluator.class)
public class FlyNodeEvaluatorMixin {

    /**
     * @reason Use optimized implementation which avoids scanning blocks for dangers where possible
     * @author JellySquid, 2No2Name
     */
    @Redirect(
            method = "getBlockPathType(Lnet/minecraft/world/level/BlockGetter;III)Lnet/minecraft/world/level/pathfinder/BlockPathTypes;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/pathfinder/FlyNodeEvaluator;checkNeighbourBlocks(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos$MutableBlockPos;Lnet/minecraft/world/level/pathfinder/BlockPathTypes;)Lnet/minecraft/world/level/pathfinder/BlockPathTypes;")
    )
    private BlockPathTypes getNodeTypeFromNeighbors(BlockGetter world, BlockPos.MutableBlockPos pos, BlockPathTypes fallback) {
        return PathNodeCache.getNodeTypeFromNeighbors(world, pos, fallback);
    }
}
