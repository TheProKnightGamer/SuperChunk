package dev.superchunk.com.ishland.c2me.fixes.general.threading_issues.mixin.asynccatchers;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.superchunk.compat.SkeinCompat;

import java.util.ConcurrentModificationException;

@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    @Shadow @Final private Thread serverThread;

    @Inject(method = "tickChildren", at = @At("HEAD"), require = 1)
    private void superchunk$configureSkein(CallbackInfo ci) {
        SkeinCompat.beforeTickChildren(this.serverThread);
    }

    @Inject(method = "saveAllChunks", at = @At("HEAD"))
    private void preventAsyncSave(CallbackInfoReturnable<Boolean> cir) {
        if (Thread.currentThread() != this.serverThread || SkeinCompat.isDimensionPhaseActive()) {
            final ConcurrentModificationException exception = new ConcurrentModificationException("Attempted to call MinecraftServer#saveAllChunks outside the exclusive server-thread phase");
            exception.printStackTrace();
            throw exception;
        }
    }

    @Inject(method = "saveEverything", at = @At("HEAD"))
    private void preventAsyncSaveAll(CallbackInfoReturnable<Boolean> cir) {
        if (Thread.currentThread() != this.serverThread || SkeinCompat.isDimensionPhaseActive()) {
            final ConcurrentModificationException exception = new ConcurrentModificationException("Attempted to call MinecraftServer#saveEverything outside the exclusive server-thread phase");
            exception.printStackTrace();
            throw exception;
        }
    }

}
