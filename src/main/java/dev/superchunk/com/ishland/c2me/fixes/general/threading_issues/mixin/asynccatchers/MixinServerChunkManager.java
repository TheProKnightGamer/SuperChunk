package dev.superchunk.com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.superchunk.compat.SkeinCompat;

import java.util.ConcurrentModificationException;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkManager {

    @Shadow @Final Thread mainThread;

    @Shadow @Final public ServerLevel level;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (Thread.currentThread() != this.mainThread && !SkeinCompat.isDimensionTicker(this.level)) {
            final ConcurrentModificationException e = new ConcurrentModificationException("Async ticking server chunk manager");
            e.printStackTrace();
            throw e;
        }
    }

}
