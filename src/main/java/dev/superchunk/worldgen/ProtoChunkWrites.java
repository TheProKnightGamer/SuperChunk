package dev.superchunk.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * {@code ProtoChunk.setBlockState} for the surface pass, without its per-call status and heightmap
 * lookups ({@code MixinSurfaceColumnMemo}).
 *
 * <p>Before {@code INITIALIZE_LIGHT}, vanilla's method writes the section and updates the heightmaps
 * of the chunk's persisted status, priming any that are missing; the light work is skipped. The
 * surface pass writes about 16,000 blocks a chunk (all the deepslate below y = 0) into one chunk at
 * one status, so the method's own self time, looking up the status's heightmap set and each
 * heightmap twice per write, was 2.6% of worldgen CPU. {@link #bind} resolves the heightmaps once;
 * a write then does exactly the section write and the heightmap updates, in vanilla's order. A chunk
 * with a missing heightmap, a lit status, another class, or another mod hooking the method keeps the
 * original. Kill switch {@code -Dsuperchunk.worldgen.leanSurfaceWrites=false}.
 */
public final class ProtoChunkWrites {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.leanSurfaceWrites", "true"));

    private static volatile byte hooks;

    private ProtoChunkWrites() {
    }

    private static boolean unhooked() {
        byte state = hooks;
        if (state == ForeignHooks.UNKNOWN) {
            state = ForeignHooks.state("Lean surface writes (ProtoChunk.setBlockState)",
                    "net.minecraft.world.level.chunk.ProtoChunk#setBlockState");
            if (state != ForeignHooks.UNKNOWN) {
                hooks = state;
            }
        }
        return state == ForeignHooks.CLEAR;
    }

    /** The heightmaps a write must update, in vanilla's order, or {@code null} to keep the original. */
    public static Heightmap[] bind(ProtoChunk chunk) {
        if (!ENABLED || chunk.getClass() != ProtoChunk.class || !unhooked()) {
            return null;
        }
        ChunkStatus status = chunk.getStatus();
        if (status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
            return null;
        }
        EnumSet<Heightmap.Types> types = status.heightmapsAfter();
        Heightmap[] maps = new Heightmap[types.size()];
        int i = 0;
        for (Heightmap.Types type : types) {
            if (!chunk.hasPrimedHeightmap(type)) {
                return null; // vanilla primes it inside the write: leave that to the original
            }
            maps[i++] = chunk.getOrCreateHeightmapUnprimed(type);
        }
        return maps;
    }

    /**
     * {@link #set} for one column at a time: {@code firstAvailable[m]} is heightmap {@code m}'s current value for the
     * column (or {@code Integer.MIN_VALUE} when not known yet). {@code Heightmap.update} returns at once, changing
     * nothing, for a block at or below two under that value, which is where nearly every surface write lands (the
     * deepslate); the value is read again whenever an update changes it.
     */
    public static BlockState setInColumn(ProtoChunk chunk, Heightmap[] maps, BlockPos pos, BlockState state, int[] firstAvailable) {
        int y = pos.getY();
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        if (section.hasOnlyAir() && state.is(Blocks.AIR)) {
            return state;
        }
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        BlockState old = section.setBlockState(x, y & 15, z, state);
        for (int m = 0; m < maps.length; m++) {
            Heightmap map = maps[m];
            if (firstAvailable[m] == Integer.MIN_VALUE) {
                firstAvailable[m] = map.getFirstAvailable(x, z);
            }
            if (y > firstAvailable[m] - 2 && map.update(x, y, z, state)) {
                firstAvailable[m] = map.getFirstAvailable(x, z);
            }
        }
        return old;
    }

    /** Vanilla's pre-light {@code setBlockState} with the heightmaps resolved; y must be in range. */
    public static BlockState set(ProtoChunk chunk, Heightmap[] maps, BlockPos pos, BlockState state) {
        int y = pos.getY();
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        if (section.hasOnlyAir() && state.is(Blocks.AIR)) {
            return state;
        }
        int x = pos.getX() & 15;
        int z = pos.getZ() & 15;
        BlockState old = section.setBlockState(x, y & 15, z, state);
        for (Heightmap map : maps) {
            map.update(x, y, z, state);
        }
        return old;
    }
}
