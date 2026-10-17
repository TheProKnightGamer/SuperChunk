package dev.superchunk.net.caffeinemc.mods.lithium.mixin.ai.raid;

import dev.superchunk.net.caffeinemc.mods.lithium.common.world.LithiumData;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Raider.ObtainRaidLeaderBannerGoal.class)
public class Raider$ObtainRaidLeaderBannerGoalMixin<T extends Raider> {
    @Shadow
    @Final
    private T mob;

    // The call to Raid#getOminousBanner() is very expensive, use a cached banner during AI ticking
    @Redirect(
            method = "canUse()Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/raid/Raid;getLeaderBannerInstance()Lnet/minecraft/world/item/ItemStack;"
            )
    )
    // PORT-1.20.1: Raid.getLeaderBannerInstance() is NO-ARG (plan 6.2 raid).
    private ItemStack getOminousBanner() {
        ItemStack ominousBanner = ((LithiumData) this.mob.level()).lithium$getData().ominousBanner();
        if (ominousBanner == null) {
            ominousBanner = Raid.getLeaderBannerInstance();
        }
        return ominousBanner;
    }
}