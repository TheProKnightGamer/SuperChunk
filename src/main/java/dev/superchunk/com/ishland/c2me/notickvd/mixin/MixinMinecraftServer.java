package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IServerChunkManager;
import dev.superchunk.com.ishland.c2me.notickvd.common.ChunkTicketManagerExtension;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Shadow public abstract Iterable<ServerLevel> getAllLevels();

    @Inject(method = "stopServer", at = @At(value = "INVOKE_STRING", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;)V", args = "ldc=Saving worlds"))
    private void stopNoTickVD(CallbackInfo ci) {
        for (ServerLevel world : this.getAllLevels()) {
            ((ChunkTicketManagerExtension) ((IServerChunkManager) world.getChunkSource()).getTicketManager()).c2me$closeNoTickVD();
        }
    }

}
