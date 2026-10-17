package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Ported from VMP playerwatching.MixinChunkFilter, RETARGETED for 1.20.1.
 *
 * <p>1.20.1 has no {@code ChunkTrackingView} (1.20.2+); the range predicate lives on
 * {@code ChunkMap.isChunkInRange(chunkX, chunkZ, sectionX, sectionZ, maxDistance)} (vanilla body
 * is a euclidean-ish disc test). Rewritten to chebyshev squares — the upstream-VMP-1.19/1.20
 * shape. {@code isChunkOnRangeBorder} (private static, calls this four times) self-consistently
 * becomes a chebyshev ring and is left alone.
 */
@Mixin(ChunkMap.class)
public class MixinChunkFilter {

    /**
     * @author ishland
     * @reason use chebyshev distance (squares — cylinders are expensive)
     */
    @Overwrite
    public static boolean isChunkInRange(int x1, int z1, int x2, int z2, int maxDist) {
        final int xDistance = Math.abs(x1 - x2);
        final int zDistance = Math.abs(z1 - z2);
        return Math.max(xDistance, zDistance) <= maxDist;
    }

}
