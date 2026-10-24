package dev.superchunk.mixin;

import dev.superchunk.worldgen.DeepSurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** One chunk's {@link DeepSurfaceRules} state, kept on the chunk's rule context. */
@Mixin(SurfaceRules.Context.class)
public abstract class MixinSurfaceContextDeep implements DeepSurfaceRules.ContextState {

    @Unique
    private DeepSurfaceRules.Table superchunk$deepTable;
    @Unique
    private int superchunk$skippedY = Integer.MIN_VALUE;

    @Override
    public DeepSurfaceRules.Table superchunk$deepTable() {
        return this.superchunk$deepTable;
    }

    @Override
    public void superchunk$setDeepTable(DeepSurfaceRules.Table table) {
        this.superchunk$deepTable = table;
    }

    @Override
    public int superchunk$skippedY() {
        return this.superchunk$skippedY;
    }

    @Override
    public void superchunk$setSkippedY(int y) {
        this.superchunk$skippedY = y;
    }
}
