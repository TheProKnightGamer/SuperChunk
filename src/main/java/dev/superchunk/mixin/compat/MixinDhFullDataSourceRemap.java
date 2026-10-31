package dev.superchunk.mixin.compat;

import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Distant Horizons' LOD merge compacts its id map with one map lookup per distinct id, not per
 * data point.
 *
 * <p>Every time DH merges a generated chunk into a section's LOD
 * ({@code FullDataSourceV2.updateFromDataSource}, 16 times per 4x4-chunk section), it rebuilds the
 * section's (biome, block) id map from the ids still in use: it walks all 4,096 columns and, for
 * every data point, looks its pair up in the new map (two concurrent-map lookups), then walks them
 * again to rewrite each id through an {@code Int2IntOpenHashMap}. That was 5% of DH's world-gen CPU.
 *
 * <p>The new map assigns ids in the order pairs are first met, and a repeat lookup of a pair
 * returns its id without changing the map. So it is enough to look each old id up the first time
 * it is met, in the same walk order; later points reuse the answer from an array. The map, and
 * every rewritten point, come out identical. An id outside the old map takes DH's lookup and
 * fails exactly as before.
 *
 * <p>Applied only when the DH methods this relies on hash to the version it was checked against
 * ({@link dev.superchunk.compat.DhFastPaths}). Kill switch: {@code -Dsuperchunk.dh.fastPaths=false}.
 */
// @Pseudo: the target is a Distant Horizons class, absent from the compile classpath.
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2", remap = false)
public abstract class MixinDhFullDataSourceRemap {

    @Shadow
    @Final
    private static ThreadLocal<FullDataPointIdMap> DATA_MAP_FOR_REMAPPING_REF;

    @Shadow
    @Final
    private long pos;

    @Shadow
    @Final
    public FullDataPointIdMap mapping;

    @Shadow
    @Final
    public LongArrayList[] dataPoints;

    @Shadow
    public static int relativePosToIndex(int relX, int relZ) {
        throw new AssertionError();
    }

    /**
     * @author SuperChunk
     * @reason One id-map lookup per distinct old id (see the class comment).
     */
    @Overwrite
    private void removeUnusedIdsAndRemap() {
        FullDataPointIdMap newMap = DATA_MAP_FOR_REMAPPING_REF.get();
        newMap.clear(this.pos);
        int knownIds = this.mapping.size();
        // newId + 1, so 0 means not met yet.
        int[] newIdByOldId = new int[knownIds];

        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                LongArrayList dataColumn = this.dataPoints[relativePosToIndex(x, z)];
                for (int i = 0; i < dataColumn.size(); i++) {
                    int oldId = FullDataPointUtil.getId(dataColumn.getLong(i));
                    if (oldId >= 0 && oldId < knownIds && newIdByOldId[oldId] != 0) {
                        continue;
                    }
                    int newId = newMap.addIfNotPresentAndGetId(this.mapping.getBiomeWrapper(oldId),
                            this.mapping.getBlockStateWrapper(oldId));
                    newIdByOldId[oldId] = newId + 1;
                }
            }
        }

        this.mapping.clear(this.pos);
        this.mapping.addAll(newMap);

        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                LongArrayList dataColumn = this.dataPoints[relativePosToIndex(x, z)];
                for (int i = 0; i < dataColumn.size(); i++) {
                    long oldDataPoint = dataColumn.getLong(i);
                    int oldId = FullDataPointUtil.getId(oldDataPoint);
                    dataColumn.set(i, FullDataPointUtil.setId(oldDataPoint, newIdByOldId[oldId] - 1));
                }
            }
        }
    }
}
