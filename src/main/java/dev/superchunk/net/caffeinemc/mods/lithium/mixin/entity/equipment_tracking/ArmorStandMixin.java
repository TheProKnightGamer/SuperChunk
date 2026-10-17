package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.equipment_tracking;

import dev.superchunk.net.caffeinemc.mods.lithium.common.entity.EquipmentEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PORT-1.20.1 (RE-DERIVE, radium ArmorStandEntityMixin blueprint): the armor stand writes its
 * equipment lists directly on NBT read, setItemSlot and brokenByAnything - mark dirty on all
 * three (radium: readCustomDataFromNbt/equipStack/onBreak RETURN hooks).
 */
@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin extends Entity {

    public ArmorStandMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Inject(
            method = "readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At("RETURN")
    )
    private void trackEquipChange(CompoundTag nbt, CallbackInfo ci) {
        this.lithium$markEquipmentChanged();
    }

    @Inject(
            method = "setItemSlot(Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("RETURN")
    )
    private void trackEquipChange(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        this.lithium$markEquipmentChanged();
    }

    @Inject(
            method = "brokenByAnything(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At("RETURN")
    )
    private void trackEquipChange(DamageSource damageSource, CallbackInfo ci) {
        this.lithium$markEquipmentChanged();
    }

    @Unique
    private void lithium$markEquipmentChanged() {
        if (!this.level().isClientSide() && this instanceof EquipmentEntity.EquipmentTrackingEntity equipmentTrackingEntity) {
            equipmentTrackingEntity.lithium$onEquipmentChanged();
        }
    }
}
