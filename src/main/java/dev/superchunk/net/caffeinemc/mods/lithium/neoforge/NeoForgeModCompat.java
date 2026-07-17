package dev.superchunk.net.caffeinemc.mods.lithium.neoforge;

import dev.superchunk.net.caffeinemc.mods.lithium.common.services.PlatformModCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.Objects;

public class NeoForgeModCompat implements PlatformModCompat {

    @Override
    public boolean canHopperInteractWithApiBlockInventory(HopperBlockEntity hopperBlockEntity, BlockState hopperState, boolean extracting) {
        Direction direction = extracting ? Direction.UP : hopperState.getValue(HopperBlock.FACING);
        BlockPos targetPos = hopperBlockEntity.getBlockPos().relative(direction);

        IItemHandler target = Objects.requireNonNull(hopperBlockEntity.getLevel()).getCapability(Capabilities.ItemHandler.BLOCK, targetPos, null, null, direction.getOpposite());
        return target != null;
    }
}
