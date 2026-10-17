package dev.superchunk.com.ishland.c2me.base.common.network;

import dev.superchunk.com.ishland.c2me.base.common.C2MEConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Forge 1.20.1 rewrite (plan §4). No {@code PayloadRegistrar}/{@code CustomPacketPayload} on
 * 1.20.1 — and no 1.20.1 upstream peer speaks this protocol (C2ME's ext protocol is 1.20.2+), so
 * cross-loader wire compatibility is moot: idiomatic Forge {@code SimpleChannel}, play-phase
 * only, both-optional (vanilla/absent peers accepted). Wire note: SimpleChannel prepends a
 * one-byte message index (0) before the VarInt — fine, we own both ends.
 */
public record ExtRenderDistance(int renderDistance) {

    public static final ResourceLocation CHANNEL_NAME =
            new ResourceLocation(C2MEConstants.MODID, C2MEConstants.EXT_RENDER_DISTANCE_ID); // c2me:ext_render_distance_v1
    private static final String PROTOCOL_VERSION = "1";

    private static final CopyOnWriteArrayList<BiConsumer<ExtRenderDistance, NetworkEvent.Context>> serverHandlers =
            new CopyOnWriteArrayList<>();

    /** Assigned once in {@link #register()} (mod-construction time, both dists). */
    public static SimpleChannel CHANNEL;

    public static void register() {
        if (CHANNEL != null) return;
        CHANNEL = NetworkRegistry.ChannelBuilder.named(CHANNEL_NAME)
                .networkProtocolVersion(() -> PROTOCOL_VERSION)
                .clientAcceptedVersions(v -> true)   // optional: absent server / vanilla OK
                .serverAcceptedVersions(v -> true)   // optional: absent client / vanilla OK
                .simpleChannel();
        CHANNEL.registerMessage(0, ExtRenderDistance.class,
                (msg, buf) -> buf.writeVarInt(msg.renderDistance()),
                buf -> new ExtRenderDistance(buf.readVarInt()),
                (msg, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ctx.enqueueWork(() -> { // hop to the server main thread
                        for (BiConsumer<ExtRenderDistance, NetworkEvent.Context> handler : serverHandlers) {
                            handler.accept(msg, ctx);
                        }
                    });
                    ctx.setPacketHandled(true);
                },
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void registerServerHandler(BiConsumer<ExtRenderDistance, NetworkEvent.Context> handler) {
        serverHandlers.add(handler);
    }
}
