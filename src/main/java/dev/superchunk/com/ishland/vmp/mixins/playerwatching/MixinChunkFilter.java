package dev.superchunk.com.ishland.vmp.mixins.playerwatching;

import net.minecraft.server.level.ChunkTrackingView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Ported from VMP playerwatching.MixinChunkFilter.
 *
 * Yarn ChunkFilter -> Mojmap ChunkTrackingView. The Mojmap static
 * {@code isWithinDistance(centerX, centerZ, viewDistance, x, z, includeEdge)} has the
 * same parameter order as Yarn's, so the chebyshev rewrite carries over directly.
 */
@Mixin(ChunkTrackingView.class)
public interface MixinChunkFilter {

    /**
     * @author ishland
     * @reason use chebyshev distance
     */
    @Overwrite
    static boolean isWithinDistance(int centerX, int centerZ, int viewDistance, int x, int z, boolean includeEdge) {
        int actualViewDistance = viewDistance + (includeEdge ? 1 : 0);
        int xDistance = Math.abs(centerX - x);
        int zDistance = Math.abs(centerZ - z);
        return xDistance <= actualViewDistance && zDistance <= actualViewDistance;
    }

}
