/*
 * Licensed under https://github.com/PaperMC/Paper/blob/master/licenses/MIT.md
 *
 * Ported into SuperChunk from VMP (Very Many Players) for the area-based
 * player-watching / chunk-sending optimization. Yarn -> Mojmap: ChunkPos field
 * accessors (pair.x / pair.z) are identical in Mojmap so this is unchanged.
 */

package dev.superchunk.io.papermc.paper.util;

import net.minecraft.world.level.ChunkPos;

public class MCUtil {

    public static long getCoordinateKey(final int x, final int z) {
        return ((long)z << 32) | (x & 0xFFFFFFFFL);
    }

    public static long getCoordinateKey(final ChunkPos pair) {
        return ((long)pair.z << 32) | (pair.x & 0xFFFFFFFFL);
    }

    public static int getCoordinateX(final long key) {
        return (int)key;
    }

    public static int getCoordinateZ(final long key) {
        return (int)(key >>> 32);
    }

}
