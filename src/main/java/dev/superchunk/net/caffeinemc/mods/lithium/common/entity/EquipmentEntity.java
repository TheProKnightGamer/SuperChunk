package dev.superchunk.net.caffeinemc.mods.lithium.common.entity;

import net.minecraft.world.item.ItemStack;

/**
 * PORT-1.20.1 (RE-DERIVE, plan 6.2 equipment_tracking): reduced to the radium
 * skip_equipment_change_check shape - the 1.21 ItemStack change-subscription layer and
 * TickableEnchantmentTrackingEntity (tickable enchantments are a 1.21 feature) are dropped.
 */
public interface EquipmentEntity {
    void lithium$onEquipmentReplaced(ItemStack oldStack, ItemStack newStack);

    interface EquipmentTrackingEntity {
        void lithium$onEquipmentChanged();
    }
}
