package dev.superchunk.mixin.compat;

import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import dev.superchunk.compat.DhCullTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Distant Horizons' occlusion culler reads each id's opacity from a table while a section merges.
 *
 * <p>After every merge of a chunk into its section ({@code updateFromDataSource}, 16 times per
 * 4x4-chunk section) DH culls hidden data points in all 4,096 columns, asking for each point, and
 * its neighbours' points, whether its block is translucent: an id-map entry and a wrapper call each
 * time, about a third of the culler's cost. Inside a merge the id map no longer changes
 * ({@link MixinDhCullScope}), so the answers come from a table of the map's ids, built from DH's own
 * wrappers on the first question. Outside a merge, or for an id outside the map (where DH throws),
 * DH's lookup runs as before.
 *
 * <p>Applied only when the DH methods this relies on hash to the version it was checked against
 * ({@link dev.superchunk.compat.DhFastPaths}). Kill switch: {@code -Dsuperchunk.dh.fastPaths=false}.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.transformers.FullDataOcclusionCuller", remap = false)
public abstract class MixinDhOcclusionCuller {

    /**
     * @author SuperChunk
     * @reason Opacity per id from a table during a merge (see the class comment).
     */
    @Overwrite
    private static boolean isTranslucent(FullDataSourceV2 source, long point) {
        int id = FullDataPointUtil.getId(point);
        if (source instanceof DhCullTable table && table.superchunk$merging()) {
            boolean[] translucent = table.superchunk$translucent();
            if (translucent == null) {
                FullDataPointIdMap mapping = source.mapping;
                translucent = new boolean[mapping.size()];
                for (int i = 0; i < translucent.length; i++) {
                    translucent[i] = mapping.getBlockStateWrapper(i).getOpacity() < 16;
                }
                table.superchunk$setTranslucent(translucent);
            }
            if (id >= 0 && id < translucent.length) {
                return translucent[id];
            }
        }
        int opacity = source.mapping.getBlockStateWrapper(id).getOpacity();
        return opacity < 16;
    }
}
