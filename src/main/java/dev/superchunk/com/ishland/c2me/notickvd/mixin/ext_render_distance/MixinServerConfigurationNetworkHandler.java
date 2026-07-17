package dev.superchunk.com.ishland.c2me.notickvd.mixin.ext_render_distance;

import dev.superchunk.com.ishland.c2me.base.mixin.access.ISyncedClientOptions;
import dev.superchunk.com.ishland.c2me.notickvd.common.IRenderDistanceOverride;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public class MixinServerConfigurationNetworkHandler implements IRenderDistanceOverride {

    @Shadow private ClientInformation clientInformation;
    @Unique
    private boolean c2me_notickvd$hasRenderDistanceOverride = false;

    @Override
    public void c2me_notickvd$setRenderDistance(int renderDistance) {
        this.c2me_notickvd$hasRenderDistanceOverride = true;
        ((ISyncedClientOptions) (Object) this.clientInformation).setViewDistance(renderDistance);
    }

    @WrapOperation(method = "handleClientInformation", at = @At(value = "FIELD", target = "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;clientInformation:Lnet/minecraft/server/level/ClientInformation;", opcode = Opcodes.PUTFIELD))
    private void interceptClientOptions(ServerConfigurationPacketListenerImpl instance, ClientInformation value, Operation<Void> original) {
        if (c2me_notickvd$hasRenderDistanceOverride) {
            ((ISyncedClientOptions) (Object) value).setViewDistance(this.clientInformation.viewDistance()); // keep the original view distance
        }
        original.call(instance, value);
    }

}
