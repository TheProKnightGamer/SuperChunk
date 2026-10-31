package dev.superchunk.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import dev.superchunk.compat.DhCullTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Marks a Distant Horizons section while it merges an update, for the occlusion culler's opacity
 * table ({@link MixinDhOcclusionCuller}).
 *
 * <p>{@code FullDataSourceV2.updateFromDataSource} merges the input, compacts the id map, and then
 * culls hidden data points in all 4,096 columns; nothing changes the id map once culling starts. So
 * the culler may answer "is this id translucent" from a table built on its first question and
 * dropped when the merge returns.
 *
 * <p>Applied only when the DH methods this relies on hash to the version it was checked against
 * ({@link dev.superchunk.compat.DhFastPaths}). Kill switch: {@code -Dsuperchunk.dh.fastPaths=false}.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2", remap = false)
public abstract class MixinDhCullScope implements DhCullTable {

    @Unique
    private boolean superchunk$merging;

    @Unique
    private boolean[] superchunk$translucent;

    @WrapMethod(method = "updateFromDataSource")
    private boolean superchunk$cullScope(FullDataSourceV2 input, Operation<Boolean> original) {
        boolean outer = this.superchunk$merging;
        this.superchunk$merging = true;
        this.superchunk$translucent = null;
        try {
            return original.call(input);
        } finally {
            this.superchunk$merging = outer;
            this.superchunk$translucent = null;
        }
    }

    @Override
    public boolean superchunk$merging() {
        return this.superchunk$merging;
    }

    @Override
    public boolean[] superchunk$translucent() {
        return this.superchunk$translucent;
    }

    @Override
    public void superchunk$setTranslucent(boolean[] translucent) {
        this.superchunk$translucent = translucent;
    }
}
