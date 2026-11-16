package dev.superchunk.compat;

import dev.superchunk.worldgen.ForeignHooks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Shared by the Distant Horizons patches that read a chunk by runs ({@code MixinDhLodBuilderRuns},
 * {@code MixinDhChunkHeightMaps}): which chunks of the world they may read that way.
 *
 * <p>DH builds LODs from three kinds of chunk: the {@code ProtoChunk}s its own generator makes, the
 * {@code LevelChunk}s it reads from an existing world's region files, and the {@code LevelChunk}s
 * of the running world (its INTERNAL_SERVER generation, and every loaded chunk that changes). The
 * last two take the same path here. Kill switch: {@code -Dsuperchunk.dh.levelChunks=false}.
 */
public final class DhLiveChunks {

    public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("superchunk.dh.levelChunks", "true"));

    private static volatile byte hooks;

    private DhLiveChunks() {
    }

    /**
     * The vanilla {@code LevelChunk} behind a wrapper's chunk, or {@code null} when it is anything
     * else: another mod's chunk class, the empty chunk, or a chunk of the debug world (whose block
     * reads do not come from its sections).
     */
    public static ChunkAccess levelChunk(ChunkAccess chunk) {
        if (!ENABLED || chunk == null) {
            return null;
        }
        if (chunk.getClass() == ImposterProtoChunk.class) {
            chunk = ((ImposterProtoChunk) chunk).getWrapped();
        }
        if (chunk == null || chunk.getClass() != LevelChunk.class) {
            return null;
        }
        Level level = ((LevelChunk) chunk).getLevel();
        return level == null || level.isDebug() ? null : chunk;
    }

    /** Whether no other mod hooks the vanilla block reads of a {@code LevelChunk} that the patches leave out. */
    public static boolean unhooked() {
        byte state = hooks;
        if (state == ForeignHooks.UNKNOWN) {
            state = ForeignHooks.state("Distant Horizons' LOD builder and height maps by runs (LevelChunk.getBlockState)",
                    "net.minecraft.world.level.chunk.LevelChunk#getBlockState",
                    "net.minecraft.world.level.chunk.ImposterProtoChunk#getBlockState",
                    "net.minecraft.world.level.chunk.ChunkAccess#getSection",
                    "net.minecraft.world.level.chunk.LevelChunkSection#hasOnlyAir",
                    "net.minecraft.world.level.chunk.LevelChunkSection#getBlockState",
                    "net.minecraft.world.level.chunk.PalettedContainer#get");
            if (state != ForeignHooks.UNKNOWN) {
                hooks = state;
            }
        }
        return state == ForeignHooks.CLEAR;
    }
}
