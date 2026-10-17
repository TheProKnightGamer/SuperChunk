package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.equipment_tracking.equipment_changes;

import dev.superchunk.net.caffeinemc.mods.lithium.common.entity.EquipmentEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Predicate;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity implements EquipmentEntity.EquipmentTrackingEntity {

    // PORT-1.20.1 (radium skip_equipment_change_check blueprint): without the 1.21 ItemStack
    // change-subscription layer, in-place NBT mutations (crossbow charging) never call back.
    // Radium's guard: never clear the dirty flag while holding a crossbow.
    @Unique
    private static final Predicate<ItemStack> DYNAMIC_EQUIPMENT = (stack) -> stack.is(Items.CROSSBOW);

    @Unique
    private boolean equipmentChanged = true;

    @org.spongepowered.asm.mixin.Shadow
    public abstract boolean isHolding(Predicate<ItemStack> predicate);

    public LivingEntityMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Override
    public void lithium$onEquipmentChanged() {
        this.equipmentChanged = true;
    }

    @Inject(
            method = "collectEquipmentChanges()Ljava/util/Map;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void skipSentEquipmentComparison(CallbackInfoReturnable<@Nullable Map<EquipmentSlot, ItemStack>> cir) {
        if (!this.equipmentChanged) {
            cir.setReturnValue(null);
        }
    }

    @Inject(
            method = "detectEquipmentUpdates()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;handleHandSwap(Ljava/util/Map;)V"
            )
    )
    private void resetEquipmentChanged(CallbackInfo ci) {
        //Not implemented for player entities. PORT-1.20.1: keep the flag dirty while holding a
        //crossbow (in-place charge-state changes are otherwise invisible, see DYNAMIC_EQUIPMENT).
        //noinspection ConstantValue
        if (!((Object) this instanceof Player) && !this.isHolding(DYNAMIC_EQUIPMENT)) {
            this.equipmentChanged = false;
        }
    }
}
