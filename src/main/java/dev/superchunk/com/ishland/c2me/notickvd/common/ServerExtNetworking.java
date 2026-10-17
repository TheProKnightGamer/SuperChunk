package dev.superchunk.com.ishland.c2me.notickvd.common;

import dev.superchunk.com.ishland.c2me.base.common.network.ExtRenderDistance;
import net.minecraft.server.level.ServerPlayer;

/**
 * Forge 1.20.1 port of the upstream {@code ServerExtNetworking} (plan §4). The handler runs on
 * the server main thread (SimpleChannel {@code enqueueWork}); 1.20.1 has no configuration phase,
 * so the play-phase listener ({@code ServerGamePacketListenerImpl}, made to implement
 * {@link IRenderDistanceOverride} by the {@code ext_render_distance} mixin) is the only target —
 * reached through {@code context.getSender().connection}.
 */
public class ServerExtNetworking {

    public static void registerListeners() {
        ExtRenderDistance.registerServerHandler((payload, context) -> {
            ServerPlayer sender = context.getSender(); // play phase only on 1.20.1
            if (sender != null && sender.connection instanceof IRenderDistanceOverride override) {
                override.c2me_notickvd$setRenderDistance(payload.renderDistance());
            }
        });
    }

}
