package dev.superchunk.com.ishland.c2me.notickvd.common;

import dev.superchunk.com.ishland.c2me.base.common.config.ModStatuses;
import dev.superchunk.com.ishland.c2me.notickvd.ModuleEntryPoint;

/**
 * NeoForge port of the upstream Fabric {@code NoTickVDInitializer} ({@code ModInitializer}).
 *
 * <p>Instead of being discovered as a Fabric entrypoint, {@link #init()} is invoked from
 * the SuperChunk mod entry point. It attaches the server-side render-distance-override
 * handler when the extended render distance protocol is enabled.
 */
public class NoTickVDInitializer {

    public static void init() {
        if (ModuleEntryPoint.enabled && Config.enableExtRenderDistanceProtocol && ModStatuses.fabric_networking_api_v1) {
            ServerExtNetworking.registerListeners();
        }
    }
}
