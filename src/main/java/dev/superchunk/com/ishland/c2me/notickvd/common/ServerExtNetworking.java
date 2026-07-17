package dev.superchunk.com.ishland.c2me.notickvd.common;

import dev.superchunk.com.ishland.c2me.base.common.network.ExtRenderDistance;

/**
 * NeoForge port of the upstream Fabric {@code ServerExtNetworking}.
 *
 * <p>Upstream registered separate Fabric {@code ServerPlayNetworking} and
 * {@code ServerConfigurationNetworking} global receivers. On NeoForge the payload
 * is registered once via {@link ExtRenderDistance} (see {@code RegisterPayloadHandlersEvent}),
 * and handlers are attached through {@link ExtRenderDistance#registerServerHandler}.
 * The packet listener obtained from {@code IPayloadContext.listener()} is the
 * play/config network handler, both of which are made to implement
 * {@link IRenderDistanceOverride} by the {@code ext_render_distance} mixins.
 */
public class ServerExtNetworking {

    public static void registerListeners() {
        ExtRenderDistance.registerServerHandler((payload, context) -> {
            if (context.listener() instanceof IRenderDistanceOverride override) {
                override.c2me_notickvd$setRenderDistance(payload.renderDistance());
            }
        });
    }

}
