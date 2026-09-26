package dev.superchunk.mixin;

import dev.superchunk.compat.SkeinCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.MinecartCommandBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps command-minecart activation and its cooldown update in the same deferred transaction. */
@Mixin(MinecartCommandBlock.class)
public abstract class MixinSkeinCommandMinecart {
    @Inject(method = "activateMinecart", at = @At("HEAD"), cancellable = true, require = 1)
    private void superchunk$deferCommandActivation(int x, int y, int z, boolean receivingPower, CallbackInfo ci) {
        if (!receivingPower || !SkeinCompat.isParallelPhaseActive()) {
            return;
        }
        MinecartCommandBlock self = (MinecartCommandBlock) (Object) this;
        if (self.level() instanceof ServerLevel level
                && SkeinCompat.deferCommand(level, () -> self.activateMinecart(x, y, z, receivingPower))) {
            ci.cancel();
        }
    }
}
