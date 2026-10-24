package dev.superchunk.mixin;

import dev.superchunk.worldgen.OreRuns;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps {@link OreRuns#highest}'s answer on the heightmap it describes, so it goes when the heightmap does. */
@Mixin(Heightmap.class)
public abstract class MixinHeightmapTop implements OreRuns.TopHolder {

    @Unique
    private OreRuns.Top superchunk$top;

    @Override
    public OreRuns.Top superchunk$top() {
        return this.superchunk$top;
    }

    @Override
    public void superchunk$setTop(OreRuns.Top top) {
        this.superchunk$top = top;
    }
}
