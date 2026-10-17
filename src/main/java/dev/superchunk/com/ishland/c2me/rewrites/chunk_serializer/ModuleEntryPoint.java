package dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer;

import com.ibm.asyncutil.util.Either;
import dev.superchunk.com.ishland.c2me.base.common.config.ConfigSystem;
import dev.superchunk.com.ishland.c2me.base.common.registry.SerializerAccess;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.ChunkDataSerializer;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.NbtWriter;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModuleEntryPoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME gc-free chunk serializer");

    /**
     * Forge 1.20.1 port kill switches (port plan §1.5/§5):
     * <ul>
     *   <li>{@code -Dsuperchunk.chunkSerializer.enable=false} — reverts to vanilla serialization
     *       (default ON, i.e. the module is governed by the config key alone).</li>
     *   <li>{@code -Dsuperchunk.chunksystem.disable=true} — master switch shared by the three
     *       rewrites modules (chunk-serializer / chunkio / chunk-system): yields a pure
     *       Waves-A+B server without editing c2me.toml.</li>
     * </ul>
     */
    private static final boolean CONFIGURED = new ConfigSystem.ConfigAccessor()
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
            .getBoolean(false, false)
            && Boolean.parseBoolean(System.getProperty("superchunk.chunkSerializer.enable", "true"))
            && !Boolean.getBoolean("superchunk.chunksystem.disable");

    /**
     * SuperChunk: always off. This serializer never writes Forge's chunk capabilities (the
     * {@code ForgeCaps} tag Forge's {@code ChunkSerializer.write} adds), and its
     * {@code ChunkMap.save} path skips {@code ChunkDataEvent.Save}. Enabled, every save would drop
     * other mods' per-chunk data for good. Setting it only logs a warning.
     */
    @SuppressWarnings("unused")
    public static final boolean enabled = false;

    static {
        if (CONFIGURED) {
            LOGGER.warn("[SuperChunk-Config] c2me.ioSystem.gcFreeChunkSerializer=true is ignored: that chunk saver "
                    + "does not write Forge's per-chunk capability data, so it would lose other mods' data on every save.");
        }
    }

    private static boolean registered = false;

    /**
     * Registers the gc-free chunk serializer with {@link SerializerAccess} when enabled.
     * Replaces the upstream Fabric {@code TheMod} ModInitializer entry point.
     */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        if (enabled) {
            // Loud, once-per-boot: the gc-free path serializes straight to raw bytes, so no
            // CompoundTag ever exists and Forge's ChunkDataEvent.Save (ChunkMap.save patch,
            // src ChunkMap:830) CANNOT be fired faithfully on this path. Same behavior as the
            // neo build. Disable via ioSystem.gcFreeChunkSerializer=false or
            // -Dsuperchunk.chunkSerializer.enable=false if a mod relies on that event.
            LOGGER.info("gc-free chunk serializer ENABLED: Forge ChunkDataEvent.Save is NOT fired on the "
                    + "gc-free save path (no CompoundTag is materialized). Kill switches: "
                    + "ioSystem.gcFreeChunkSerializer=false (c2me.toml) or -Dsuperchunk.chunkSerializer.enable=false.");
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
