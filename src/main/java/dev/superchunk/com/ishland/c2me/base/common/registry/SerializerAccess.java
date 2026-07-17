package dev.superchunk.com.ishland.c2me.base.common.registry;

import com.ibm.asyncutil.util.Either;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/**
 * Only used for c2me-threading-chunkio
 */
public class SerializerAccess {

    public final static Serializer VANILLA = (world, chunk, preferNbtCompound) -> Either.left(ChunkSerializer.write(world, chunk));

    private static Serializer activeSerializer = null;

    public static void registerSerializer(Serializer serializer) {
        if (serializer == null) {
            throw new NullPointerException("serializer");
        } else if (activeSerializer != null) {
            throw new IllegalStateException("Serializer already registered");
        } else {
            activeSerializer = serializer;
        }
    }

    public static Serializer getSerializer() {
        return activeSerializer == null ? VANILLA : activeSerializer;
    }

    public interface Serializer {

        com.ibm.asyncutil.util.Either<CompoundTag, byte[]> serialize(ServerLevel world, ChunkAccess chunk, boolean preferNbtCompound);

    }

}
