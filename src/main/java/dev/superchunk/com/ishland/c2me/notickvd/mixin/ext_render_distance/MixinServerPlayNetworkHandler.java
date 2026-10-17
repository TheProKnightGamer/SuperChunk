package dev.superchunk.com.ishland.c2me.notickvd.mixin.ext_render_distance;

import dev.superchunk.com.ishland.c2me.notickvd.common.IRenderDistanceOverride;
import dev.superchunk.com.ishland.vmp.common.chunkwatching.PlayerClientVDTracking;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.fml.loading.FMLLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * REWRITTEN for 1.20.1 (plan §3.3): no {@code ClientInformation}/{@code ISyncedClientOptions}
 * (both 1.20.2+; the accessor was dropped in Wave B) — the per-player VD authority is the VMP
 * {@link PlayerClientVDTracking} duck on {@code ServerPlayer}, which the ext payload writes
 * directly. The {@code handleClientInformation} wrap keeps the override sticky: later vanilla
 * option syncs (whose viewDistance is one byte on the wire) must not clobber it, so the packet
 * record is rebuilt with the override before {@code updateOptions} runs — VMP's HEAD inject sees
 * the rewritten packet regardless of mixin priority (the rewrite happens at the call site).
 *
 * <p>DEPENDENCY: only meaningful with the VMP module applied (per-player VD is a VMP concept on
 * 1.20.1) — {@code notickvd.MixinPlugin} gates this package on the VMP kill-switch too.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerPlayNetworkHandler implements IRenderDistanceOverride {

    @Shadow public ServerPlayer player;

    @Unique
    private int c2me_notickvd$overrideVD = -1;

    @Override
    public void c2me_notickvd$setRenderDistance(int renderDistance) {
        this.c2me_notickvd$overrideVD = renderDistance;
        ((PlayerClientVDTracking) this.player).superchunk$setClientViewDistance(renderDistance);
        if (!FMLLoader.isProduction()) {
            System.out.println(String.format("%s render distance has changed to %d",
                    this.player.getName().getString(), renderDistance));
        }
    }

    // Keep the override sticky: later vanilla option syncs must not clobber it.
    @WrapOperation(method = "handleClientInformation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;updateOptions(Lnet/minecraft/network/protocol/game/ServerboundClientInformationPacket;)V"))
    private void interceptClientOptions(ServerPlayer instance, ServerboundClientInformationPacket packet, Operation<Void> original) {
        if (this.c2me_notickvd$overrideVD > 0) {
            packet = new ServerboundClientInformationPacket(packet.language(), this.c2me_notickvd$overrideVD,
                    packet.chatVisibility(), packet.chatColors(), packet.modelCustomisation(),
                    packet.mainHand(), packet.textFilteringEnabled(), packet.allowsListing());
        }
        original.call(instance, packet);
    }

}
