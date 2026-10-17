package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.aquifer;

import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.ScAquiferCellCache;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands each {@code NoiseChunk}'s aquifer its {@code RandomState}'s cell cache ({@link ScAquiferCellCache}). */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunkCellCache implements ScAquiferCellCache.Holder {

    @Unique
    private ScAquiferCellCache superchunk$cellCache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void superchunk$bindCellCache(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) RandomState random) {
        if ((Object) random instanceof ScAquiferCellCache.Owner owner) {
            this.superchunk$cellCache = owner.superchunk$aquiferCellCache();
        }
    }

    @Override
    public ScAquiferCellCache superchunk$aquiferCellCache() {
        return this.superchunk$cellCache;
    }

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.levelgen.NoiseSettings noiseSettings;

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private int cellHeight;

    @org.spongepowered.asm.mixin.Shadow public abstract net.minecraft.world.level.levelgen.blending.Blender getBlender();

    /**
     * Shared preliminary surface levels ({@link dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels}):
     * a column another chunk of this RandomState already scanned is not scanned again.
     */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "computePreliminarySurfaceLevel", require = 0)
    private int superchunk$sharedSurfaceLevel(long packedPos,
                                              com.llamalad7.mixinextras.injector.wrapoperation.Operation<Integer> original) {
        ScAquiferCellCache cache = this.superchunk$cellCache;
        if (cache == null || !dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels.ENABLED
                || this.getBlender() != net.minecraft.world.level.levelgen.blending.Blender.empty()
                || !cache.surfaceLevels.accepts(this.noiseSettings.minY(), this.noiseSettings.height(), this.cellHeight)) {
            return original.call(packedPos);
        }
        int x = net.minecraft.server.level.ColumnPos.getX(packedPos);
        int z = net.minecraft.server.level.ColumnPos.getZ(packedPos);
        int level = cache.surfaceLevels.get(x, z);
        if (level != dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels.MISS) {
            if (dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels.METRICS) {
                dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels.HITS.incrementAndGet();
            }
            if (dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels.VERIFY) {
                int fresh = original.call(packedPos);
                if (fresh != level && dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels
                        .MISMATCHES.incrementAndGet() <= 16) {
                    org.slf4j.LoggerFactory.getLogger("SuperChunk-Aquifer").warn(
                            "[surface-levels] VERIFY MISMATCH at {},{}: shared {} vs computed {}", x, z, level, fresh);
                }
                return fresh;
            }
            return level;
        }
        if (dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels.METRICS) {
            dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels.MISSES.incrementAndGet();
        }
        level = original.call(packedPos);
        cache.surfaceLevels.put(x, z, level);
        return level;
    }

}
