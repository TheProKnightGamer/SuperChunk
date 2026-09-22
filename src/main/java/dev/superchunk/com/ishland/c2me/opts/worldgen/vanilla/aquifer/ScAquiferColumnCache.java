package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;

/** Small dense memo for the candidate XZ column pairs in one aquifer's lattice. */
public final class ScAquiferColumnCache {
    public static final int ABSENT = Integer.MIN_VALUE;

    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int[] ceilings;
    private Long2IntOpenHashMap outside;

    public ScAquiferColumnCache(int minX, int minZ, int gridSizeX, int gridSizeZ) {
        this.minX = minX;
        this.minZ = minZ;
        // A candidate pair uses (gx,gz), (gx+1,gz), (gx,gz+1), (gx+1,gz+1).
        this.width = Math.max(0, gridSizeX - 1);
        this.depth = Math.max(0, gridSizeZ - 1);
        this.ceilings = new int[this.width * this.depth];
        Arrays.fill(this.ceilings, ABSENT);
    }

    public int get(int x, int z) {
        int rx = x - this.minX;
        int rz = z - this.minZ;
        if (rx >= 0 && rx < this.width && rz >= 0 && rz < this.depth) {
            return this.ceilings[rz * this.width + rx];
        }
        return this.outside == null ? ABSENT : this.outside.get(key(x, z));
    }

    public void put(int x, int z, int ceiling) {
        int rx = x - this.minX;
        int rz = z - this.minZ;
        if (rx >= 0 && rx < this.width && rz >= 0 && rz < this.depth) {
            this.ceilings[rz * this.width + rx] = ceiling;
            return;
        }
        // Preserve the former map's behavior for callers outside the usual lattice.
        if (this.outside == null) {
            this.outside = new Long2IntOpenHashMap();
            this.outside.defaultReturnValue(ABSENT);
        }
        this.outside.put(key(x, z), ceiling);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
