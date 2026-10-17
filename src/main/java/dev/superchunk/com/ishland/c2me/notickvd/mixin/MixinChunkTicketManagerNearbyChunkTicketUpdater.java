package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkTicketManagerDistanceFromNearestPlayerTracker;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(DistanceManager.PlayerTicketTracker.class)
public abstract class MixinChunkTicketManagerNearbyChunkTicketUpdater {

    @ModifyVariable(method = "updateViewDistance", at = @At("HEAD"), argsOnly = true)
    private int clampViewDistance(int watchDistance) {
        return Mth.clamp(watchDistance, 0, ((IChunkTicketManagerDistanceFromNearestPlayerTracker) this).getMaxDistance());
    }

}
