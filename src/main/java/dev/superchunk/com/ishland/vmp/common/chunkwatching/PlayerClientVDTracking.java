package dev.superchunk.com.ishland.vmp.common.chunkwatching;

/**
 * Ported from VMP (chunkwatching.PlayerClientVDTracking), EXTENDED for the Forge 1.20.1 port.
 *
 * <p>1.20.1 has no vanilla per-player view distance ({@code ServerPlayer.requestedViewDistance()}
 * and {@code ClientInformation} are 1.20.2+; vanilla {@code ServerPlayer.updateOptions} ignores
 * the packet's viewDistance entirely). This duck — implemented on {@code ServerPlayer} by
 * {@code MixinServerPlayerEntity} — is therefore the SINGLE per-player-VD authority on this port:
 * written by the vanilla options packet (clamped: the wire value is one byte, &gt;127 wraps
 * negative) and overridden by the {@code c2me:ext_render_distance_v1} payload.
 */
public interface PlayerClientVDTracking {

    boolean isClientViewDistanceChanged();

    /** Returns the client view distance AND consumes the changed flag (parity with neo/upstream VMP). */
    int getClientViewDistance();

    /** Side-effect-free read (for {@code AreaPlayerChunkWatchingManager.getViewDistance}). */
    int superchunk$getClientViewDistanceRaw();

    /** Ext-render-distance protocol entry: overrides the tracked VD (clamped to &ge; 2). */
    void superchunk$setClientViewDistance(int viewDistance);

}
