package dev.superchunk.net.caffeinemc.mods.lithium.common.ai.pathing;

import net.minecraft.world.level.pathfinder.BlockPathTypes;

// PORT-1.20.1: PathType -> BlockPathTypes (the 1.21 rename); neo's 3-method shape is kept
// (BootstrapMixin-driven cache init, plan §6.2 pathing table).
public interface BlockStatePathingCache {
    BlockPathTypes lithium$getPathNodeType();

    BlockPathTypes lithium$getNeighborPathNodeType();

    void lithium$initializePathNodeTypeCache();
}
