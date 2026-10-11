package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer;

import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.AquiferCellSharing;
import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.ScAquiferCellCache;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Each {@code RandomState} owns the aquifer cell cache of its dimension ({@link ScAquiferCellCache}),
 * made on first use if its router can share ({@link AquiferCellSharing}). DFC decides that from the
 * vanilla router before compiling it; without DFC the router is still vanilla on first use.
 */
@Mixin(RandomState.class)
public abstract class MixinRandomStateCellCache implements ScAquiferCellCache.Owner, dev.superchunk.worldgen.CornerColumns.Owner {

    @Shadow
    public abstract NoiseRouter router();

    @Unique
    private volatile Boolean superchunk$cellSharing;
    @Unique
    private volatile ScAquiferCellCache superchunk$cellCache;
    @Unique
    private volatile boolean superchunk$cellCacheResolved;

    @Override
    public void superchunk$decideCellSharing(NoiseRouter vanillaRouter) {
        if (this.superchunk$cellSharing == null) {
            this.superchunk$cellSharing = AquiferCellSharing.decide(vanillaRouter);
        }
    }

    @Override
    public ScAquiferCellCache superchunk$aquiferCellCache() {
        if (!this.superchunk$cellCacheResolved) {
            this.superchunk$resolveCellCache();
        }
        return this.superchunk$cellCache;
    }

    @Unique
    private synchronized void superchunk$resolveCellCache() {
        if (!this.superchunk$cellCacheResolved) {
            if (this.superchunk$cellSharing == null) {
                this.superchunk$cellSharing = AquiferCellSharing.decide(this.router());
            }
            this.superchunk$cellCache = this.superchunk$cellSharing ? new ScAquiferCellCache() : null;
            this.superchunk$cellCacheResolved = true;
        }
    }

    // ---- SuperChunk: shared cell-corner columns (dev.superchunk.worldgen.CornerColumns) ----

    @Unique
    private volatile Boolean superchunk$cornerSharing;
    @Unique
    private volatile dev.superchunk.worldgen.CornerColumns superchunk$cornerColumns;
    @Unique
    private volatile boolean superchunk$cornerColumnsResolved;

    @Override
    public void superchunk$decideCornerSharing(NoiseRouter vanillaRouter) {
        if (this.superchunk$cornerSharing == null) {
            String why = AquiferCellSharing.cornerReason(vanillaRouter);
            if (why != null) {
                org.slf4j.LoggerFactory.getLogger("SuperChunk-Worldgen").info(
                        "[SuperChunk] Corner column sharing is off for a dimension: its {}", why);
            }
            this.superchunk$cornerSharing = why == null;
        }
    }

    @Override
    public dev.superchunk.worldgen.CornerColumns superchunk$cornerColumns() {
        if (!this.superchunk$cornerColumnsResolved) {
            synchronized (this) {
                if (!this.superchunk$cornerColumnsResolved) {
                    // Decided from the vanilla router before DFC compiled it; never decided means unknown.
                    this.superchunk$cornerColumns = Boolean.TRUE.equals(this.superchunk$cornerSharing)
                            && dev.superchunk.worldgen.CornerColumns.ENABLED
                            ? new dev.superchunk.worldgen.CornerColumns() : null;
                    this.superchunk$cornerColumnsResolved = true;
                }
            }
        }
        return this.superchunk$cornerColumns;
    }
}
