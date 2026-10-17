package dev.superchunk.net.caffeinemc.mods.lithium.forge;

import dev.superchunk.net.caffeinemc.mods.lithium.common.services.PlatformModCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

public class ForgeModCompat implements PlatformModCompat {

    /**
     * Forge 1.20.1 has NO level-scoped block capabilities (that is a NeoForge-1.20.5+ API), so the
     * probe resolves the capability off the target {@link BlockEntity} — mirroring
     * {@code VanillaInventoryCodeHooks.getItemHandler}, which only ever resolves caps off a
     * BlockEntity on 1.20.1. A target BE exposing {@code ITEM_HANDLER} keeps the hopper on the
     * awake/vanilla path (Forge's insertHook/extractHook handle it).
     */
    @Override
    public boolean canHopperInteractWithApiBlockInventory(HopperBlockEntity hopperBlockEntity, BlockState hopperState, boolean extracting) {
        Direction direction = extracting ? Direction.UP : hopperState.getValue(HopperBlock.FACING);
        BlockPos targetPos = hopperBlockEntity.getBlockPos().relative(direction);

        Level level = hopperBlockEntity.getLevel();
        if (level == null) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(targetPos);
        return be != null && be.getCapability(ForgeCapabilities.ITEM_HANDLER, direction.getOpposite()).isPresent();
    }
}
