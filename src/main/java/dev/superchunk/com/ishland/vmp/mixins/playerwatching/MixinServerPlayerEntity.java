package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import dev.superchunk.com.ishland.vmp.common.chunkwatching.PlayerClientVDTracking;
import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ported from VMP playerwatching.MixinServerPlayerEntity, re-based for Forge 1.20.1.
 *
 * <p>1.20.1 deltas (portplans/vmp-notickvd-player.md §2.3): no {@code ClientInformation} — client
 * options arrive as the record {@code ServerboundClientInformationPacket}; its viewDistance is ONE
 * BYTE on the wire, so slider values &gt; 127 wrap negative — the clamp runs BEFORE the compare
 * (neo compared unclamped, which on 1.20.1 would re-trigger on every options packet for wrapped
 * values). The duck gains the raw read + ext-protocol setter (per-player VD authority — vanilla
 * 1.20.1 has none).
 */
@Mixin(ServerPlayer.class)
public class MixinServerPlayerEntity implements PlayerClientVDTracking {

    @Unique
    private boolean vdChanged = false;

    @Unique
    private int clientVD = 2;

    @Inject(method = "updateOptions", at = @At("HEAD"))
    private void onClientSettingsChanged(ServerboundClientInformationPacket packet, CallbackInfo ci) {
        final int currentVD = Math.max(2, packet.viewDistance()); // byte on the wire: >127 wraps negative -> clamp first
        if (currentVD != this.clientVD) this.vdChanged = true;
        this.clientVD = currentVD;
    }

    @Inject(method = "restoreFrom", at = @At("RETURN"))
    private void onPlayerCopy(ServerPlayer oldPlayer, boolean alive, CallbackInfo ci) {
        this.clientVD = ((PlayerClientVDTracking) oldPlayer).superchunk$getClientViewDistanceRaw();
        this.vdChanged = true;
    }

    @Unique
    @Override
    public boolean isClientViewDistanceChanged() {
        return this.vdChanged;
    }

    @Unique
    @Override
    public int getClientViewDistance() {
        this.vdChanged = false;
        return this.clientVD;
    }

    @Unique
    @Override
    public int superchunk$getClientViewDistanceRaw() {
        return this.clientVD;
    }

    @Unique
    @Override
    public void superchunk$setClientViewDistance(int viewDistance) {
        final int vd = Math.max(2, viewDistance);
        if (vd != this.clientVD) this.vdChanged = true;
        this.clientVD = vd;
    }
}
