package dev.superchunk.com.ishland.c2me.base.common.network;

import dev.superchunk.com.ishland.c2me.base.common.C2MEConstants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public record ExtRenderDistance(int renderDistance) implements CustomPacketPayload {

    private static final AtomicBoolean registered = new AtomicBoolean(false);
    private static final CopyOnWriteArrayList<IPayloadHandler<ExtRenderDistance>> serverHandlers = new CopyOnWriteArrayList<>();

    public static final StreamCodec<RegistryFriendlyByteBuf, ExtRenderDistance> CODEC = StreamCodec.ofMember(ExtRenderDistance::write, ExtRenderDistance::new);
    public static final Type<ExtRenderDistance> ID = new Type<>(ResourceLocation.fromNamespaceAndPath(C2MEConstants.MODID, C2MEConstants.EXT_RENDER_DISTANCE_ID));

    public static void register(final RegisterPayloadHandlersEvent event) {
        if (registered.compareAndSet(false, true)) {
            event.registrar("1").optional().playToServer(
                    ID,
                    CODEC,
                    (arg, iPayloadContext) -> {
                        for (IPayloadHandler<ExtRenderDistance> handler : serverHandlers) {
                            handler.handle(arg, iPayloadContext);
                        }
                    }
            );
        }
    }

    public static void registerServerHandler(final IPayloadHandler<ExtRenderDistance> handler) {
        serverHandlers.add(handler);
    }

    public ExtRenderDistance(RegistryFriendlyByteBuf buf) {
        this(buf.readVarInt());
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(renderDistance);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
