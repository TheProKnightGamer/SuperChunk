package dev.superchunk.com.ishland.c2me.notickvd.mixin.client;

import com.mojang.datafixers.DataFixer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.Proxy;

@Mixin(IntegratedServer.class)
public abstract class MixinIntegratedServer extends MinecraftServer {

    public MixinIntegratedServer(Thread serverThread, LevelStorageSource.LevelStorageAccess session, PackRepository dataPackManager, WorldStem saveLoader, Proxy proxy, DataFixer dataFixer, Services apiServices, ChunkProgressListenerFactory worldGenerationProgressListenerFactory) {
        super(serverThread, session, dataPackManager, saveLoader, proxy, dataFixer, apiServices, worldGenerationProgressListenerFactory);
    }

    @Inject(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V", shift = At.Shift.AFTER), require = 0)
    private void afterPauseLoop(CallbackInfo ci) {
        for (ServerPlayer serverPlayerEntity : this.getPlayerList().getPlayers()) {
            serverPlayerEntity.connection.suspendFlushing();
            serverPlayerEntity.connection.chunkSender.sendNextChunks(serverPlayerEntity);
            serverPlayerEntity.connection.resumeFlushing();
        }
    }

}
