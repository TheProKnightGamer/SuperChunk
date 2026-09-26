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
public abstract class MixinRandomStateCellCache implements ScAquiferCellCache.Owner {

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
}
