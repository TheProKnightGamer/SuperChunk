package dev.superchunk.client;

import dev.superchunk.SuperChunk;
import dev.superchunk.com.ishland.c2me.base.common.network.ExtRenderDistance;
import dev.superchunk.config.SuperChunkConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client half of the C2ME extended-render-distance capability
 * ({@code client.maxRenderDistance} in {@code superchunk.properties}).
 *
 * <p>The server half (payload registration + the sticky per-player view-distance
 * override) has been wired since the merge — see {@link ExtRenderDistance} and
 * {@code notickvd.common.ServerExtNetworking} — but nothing on the client ever raised
 * the vanilla 32-chunk slider cap or sent the request, so the capability was inert.
 * Two pieces complete it:
 *
 * <ul>
 *   <li>{@code MixinOptionsExtendRenderDistance} widens the render-distance option's
 *       {@code IntRange} cap to {@link #maxRenderDistance()} (default 32 = vanilla no-op),
 *       which is what actually lets the renderer draw past 32 — chunk data alone is
 *       invisible while the option clamps.</li>
 *   <li>This class sends {@code c2me:ext_render_distance_v1} on login when the slider
 *       is above 32 and the server speaks the (optional) channel. Values up to 127 also
 *       travel in vanilla's ClientInformation (a byte on the wire — hence 127); the
 *       payload makes the override sticky server-side and is the only carrier above 127.</li>
 * </ul>
 *
 * <p>Ticking stays at simulation distance — the extra ring is C2ME's no-tick view
 * distance, served by the already-enabled notickvd module (integrated servers included).
 */
public final class ExtendedRenderDistance {

    /** Hard sanity ceiling: client chunk storage grows O(r²); 512 is already ~256x vanilla-32 memory. */
    private static final int MAX_CAP = 512;

    private static volatile int maxRenderDistance = -1;

    private ExtendedRenderDistance() {
    }

    /** Configured slider cap from {@code client.maxRenderDistance}; 32 (vanilla) on any parse failure. */
    public static int maxRenderDistance() {
        int v = maxRenderDistance;
        if (v < 0) {
            int parsed = 32;
            try {
                parsed = Integer.parseInt(
                        SuperChunkConfig.get().getProperty("client.maxRenderDistance", "32").trim());
            } catch (Throwable ignored) {
                // unparseable/missing -> vanilla cap
            }
            v = Math.max(32, Math.min(parsed, MAX_CAP));
            maxRenderDistance = v;
        }
        return v;
    }

    /** Called from the mod entry point, client dist only. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ExtendedRenderDistance::onLoggingIn);
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            int desired = Minecraft.getInstance().options.renderDistance().get();
            if (desired > 32 && event.getPlayer().connection.hasChannel(ExtRenderDistance.ID)) {
                PacketDistributor.sendToServer(new ExtRenderDistance(desired));
                SuperChunk.LOGGER.info(
                        "[SuperChunk] requested extended render distance {} from the server (c2me ext_render_distance).",
                        desired);
            }
        } catch (Throwable t) {
            // Never let a view-distance nicety break login.
            SuperChunk.LOGGER.warn("[SuperChunk] extended render distance request failed — vanilla view distance in effect.", t);
        }
    }
}
