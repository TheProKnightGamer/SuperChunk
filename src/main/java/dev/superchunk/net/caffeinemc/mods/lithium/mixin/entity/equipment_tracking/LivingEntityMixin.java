package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.equipment_tracking;


import dev.superchunk.net.caffeinemc.mods.lithium.common.entity.EquipmentEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PORT-1.20.1 (RE-DERIVE, plan 6.2 equipment_tracking / radium skip_equipment_change_check
 * blueprint): the ItemStack change-subscription layer does not exist on 1.20.1 - the dirty
 * flag is driven purely by equip events. The onEquipItem FIELD-firstTick anchor is shared
 * with 1.20.1 (single firstTick access, short-circuited past no-op equips); radium's
 * eat(Level, ItemStack) RETURN hook is adopted because eating mutates the stack in place
 * without an equip event.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity implements EquipmentEntity {

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Inject(
            method = "onEquipItem(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V", require = 1, allow = 1,
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;firstTick:Z")
    )
    private void handleStackEquip(EquipmentSlot slot, ItemStack oldStack, ItemStack newStack, CallbackInfo ci) {
        if (!this.level().isClientSide()) {
            this.lithium$onEquipmentReplaced(oldStack, newStack);
        }
    }

    @Inject(
            method = "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN")
    )
    private void trackEatingEquipmentChange(Level world, ItemStack stack, CallbackInfoReturnable<ItemStack> cir) {
        if (!world.isClientSide() && this instanceof EquipmentEntity.EquipmentTrackingEntity equipmentTrackingEntity) {
            equipmentTrackingEntity.lithium$onEquipmentChanged();
        }
    }

    @Override
    public void lithium$onEquipmentReplaced(ItemStack oldStack, ItemStack newStack) {
        if (this instanceof EquipmentEntity.EquipmentTrackingEntity equipmentTrackingEntity) {
            equipmentTrackingEntity.lithium$onEquipmentChanged();
        }
    }
}
