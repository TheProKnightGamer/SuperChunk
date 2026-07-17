package dev.superchunk.net.caffeinemc.mods.lithium.common.services;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public interface PlatformModCompat {
    PlatformModCompat INSTANCE = Services.load(PlatformModCompat.class);

    boolean canHopperInteractWithApiBlockInventory(HopperBlockEntity hopperBlockEntity, BlockState hopperState, boolean extracting);
}
