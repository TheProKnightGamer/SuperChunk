package dev.superchunk.mixin;

import dev.superchunk.worldgen.EmptyLevelCleanup;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Runs {@link EmptyLevelCleanup} once a second while the level skips its block entity tick. */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevelEmptyTickCleanup {

    @Shadow private int emptyTime;

    @Unique
    private int superchunk$emptyCleanupCountdown;

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void superchunk$cleanupWhileEmpty(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        // emptyTime only passes 300 on ticks where tickBlockEntities was skipped
        if (this.emptyTime > 300 && --this.superchunk$emptyCleanupCountdown <= 0) {
            this.superchunk$emptyCleanupCountdown = 20;
            ((EmptyLevelCleanup) this).superchunk$dropRemovedBlockEntities();
        }
    }
}
