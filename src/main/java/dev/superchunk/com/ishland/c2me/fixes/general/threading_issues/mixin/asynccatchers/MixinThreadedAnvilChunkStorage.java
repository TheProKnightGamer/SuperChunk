package dev.superchunk.com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ConcurrentModificationException;

@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage {

    @Shadow @Final public BlockableEventLoop<Runnable> mainThreadExecutor;

    @Inject(method = "addEntity", at = @At("HEAD"))
    private void preventAsyncEntityLoad(CallbackInfo ci) {
        if (!this.mainThreadExecutor.isSameThread()) {
            final ConcurrentModificationException e = new ConcurrentModificationException("Async entity load");
            e.printStackTrace();
            throw e;
        }
    }

    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void preventAsyncEntityUnload(CallbackInfo ci) {
        if (!this.mainThreadExecutor.isSameThread()) {
            final ConcurrentModificationException e = new ConcurrentModificationException("Async entity unload");
            e.printStackTrace();
            throw e;
        }
    }

}
