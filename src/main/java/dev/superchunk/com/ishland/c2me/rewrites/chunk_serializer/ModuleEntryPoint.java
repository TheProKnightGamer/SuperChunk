package dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer;

import com.ibm.asyncutil.util.Either;
import dev.superchunk.com.ishland.c2me.base.common.config.ConfigSystem;
import dev.superchunk.com.ishland.c2me.base.common.registry.SerializerAccess;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.ChunkDataSerializer;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.NbtWriter;
import net.minecraft.nbt.Tag;

public final class ModuleEntryPoint {

    @SuppressWarnings("unused")
    public static final boolean enabled = new ConfigSystem.ConfigAccessor()
            .key("ioSystem.gcFreeChunkSerializer")
            .comment("""
                    EXPERIMENTAL FEATURE
                    This replaces the way your chunks are saved.
                    Please keep regular backups of your world if you are using this feature,
                    and report any world issues you encounter with this feature to our GitHub.

                    Whether to use the fast reduced allocation chunk serializer
                    (may cause incompatibility with other mods)
                    """)
            .incompatibleMod("architectury", "*")
            .getBoolean(false, false);

    private static boolean registered = false;

    /**
     * Registers the gc-free chunk serializer with {@link SerializerAccess} when enabled.
     * Replaces the upstream Fabric {@code TheMod} ModInitializer entry point.
     */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        if (enabled) {
            SerializerAccess.registerSerializer((world, chunk, preferNbtCompound) -> {
                if (preferNbtCompound) {
                    return SerializerAccess.VANILLA.serialize(world, chunk, true);
                }
                NbtWriter nbtWriter = new NbtWriter();
                try {
                    nbtWriter.start(Tag.TAG_COMPOUND);
                    ChunkDataSerializer.write(world, chunk, nbtWriter);
                    nbtWriter.finishCompound();
                    final byte[] data = nbtWriter.toByteArray();
                    return Either.right(data);
                } finally {
                    // toByteArray() copied to the heap, so freeing the off-heap buffer here is safe
                    // even on the exception path.
                    nbtWriter.release();
                }
            });
        }
    }

}
