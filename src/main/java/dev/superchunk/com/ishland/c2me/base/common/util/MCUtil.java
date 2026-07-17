package dev.superchunk.com.ishland.c2me.base.common.util;

import net.minecraft.world.level.ChunkPos;

public class MCUtil {

    private MCUtil() {
    }

    public static long toLong(ChunkPos pos) {
        return ((long)pos.x) | ((long)pos.z) << 32;
    }

}
