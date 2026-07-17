package dev.superchunk.com.ishland.c2me.notickvd.mixin.ext_render_distance;

import dev.superchunk.com.ishland.c2me.base.mixin.access.ISyncedClientOptions;
import dev.superchunk.com.ishland.c2me.notickvd.common.IRenderDistanceOverride;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.fml.loading.FMLLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerPlayNetworkHandler implements IRenderDistanceOverride {

    @Shadow public ServerPlayer player;
    @Unique
    private boolean c2me_notickvd$hasRenderDistanceOverride = false;

    @Override
    public void c2me_notickvd$setRenderDistance(int renderDistance) {
        this.c2me_notickvd$hasRenderDistanceOverride = true;
        final ClientInformation clientOptions = this.player.clientInformation();
        if (clientOptions != null) {
            ((ISyncedClientOptions) (Object) clientOptions).setViewDistance(renderDistance);
            this.player.updateOptions(clientOptions);
        }
        if (!FMLLoader.isProduction()) {
            System.out.println(String.format("%s render distance has changed to %d", this.player.getName().getString(), clientOptions.viewDistance()));
        }
    }

    @WrapOperation(method = "handleClientInformation", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;updateOptions(Lnet/minecraft/server/level/ClientInformation;)V"))
    private void interceptClientOptions(ServerPlayer instance, ClientInformation clientOptions, Operation<Void> original) {
        if (c2me_notickvd$hasRenderDistanceOverride) {
            ((ISyncedClientOptions) (Object) clientOptions).setViewDistance(instance.clientInformation().viewDistance()); // keep the original view distance
        }
        if (!FMLLoader.isProduction()) {
            System.out.println(String.format("%s render distance has changed to %d", instance.getName().getString(), clientOptions.viewDistance()));
        }
        original.call(instance, clientOptions);
    }

}
