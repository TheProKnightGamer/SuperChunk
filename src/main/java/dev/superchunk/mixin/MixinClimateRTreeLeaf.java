package dev.superchunk.mixin;

import dev.superchunk.worldgen.FlatClimateIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Lets {@link FlatClimateIndex} map vanilla's warm-start leaf back to its array index. */
@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree$Leaf")
public abstract class MixinClimateRTreeLeaf implements FlatClimateIndex.LeafSlot {
    /** Index + 1, so the default 0 means "not indexed" without a field initializer. */
    @Unique
    private int superchunk$flatIndexPlusOne;

    @Override
    public int superchunk$flatIndex() {
        return this.superchunk$flatIndexPlusOne - 1;
    }

    @Override
    public void superchunk$setFlatIndex(int index) {
        this.superchunk$flatIndexPlusOne = index + 1;
    }
}
