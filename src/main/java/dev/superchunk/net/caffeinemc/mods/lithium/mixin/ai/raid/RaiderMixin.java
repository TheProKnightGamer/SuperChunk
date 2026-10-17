package dev.superchunk.net.caffeinemc.mods.lithium.mixin.ai.raid;

import dev.superchunk.net.caffeinemc.mods.lithium.common.world.LithiumData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

@Mixin(Raider.class)
public abstract class RaiderMixin extends Entity {
    @Mutable
    @Shadow
    @Final
    static Predicate<ItemEntity> ALLOWED_ITEMS;

    static {
        // The call to Raid#getOminousBanner() is very expensive, use a cached banner during AI ticking
        ALLOWED_ITEMS = (itemEntity) -> {
            ItemStack ominousBanner = ((LithiumData) itemEntity.level()).lithium$getData().ominousBanner();
            if (ominousBanner == null) {
                ominousBanner = Raid.getLeaderBannerInstance();
            }

            return !itemEntity.hasPickUpDelay() && itemEntity.isAlive() &&
                    ItemStack.matches(itemEntity.getItem(), ominousBanner);
        };
    }

    public RaiderMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Redirect(
            // PORT-1.20.1: no isCaptain() on 1.20.1; the die-time captaincy check carries the
            // banner call instead (radium redirects onDeath only; pickUpItem also has it).
            method = { "pickUpItem(Lnet/minecraft/world/entity/item/ItemEntity;)V", "die(Lnet/minecraft/world/damagesource/DamageSource;)V" },
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/raid/Raid;getLeaderBannerInstance()Lnet/minecraft/world/item/ItemStack;")
    )
    private ItemStack getOminousBanner() {
        ItemStack ominousBanner = ((LithiumData) this.level()).lithium$getData().ominousBanner();
        if (ominousBanner == null) {
            ominousBanner = Raid.getLeaderBannerInstance();
        }
        return ominousBanner;
    }
}
