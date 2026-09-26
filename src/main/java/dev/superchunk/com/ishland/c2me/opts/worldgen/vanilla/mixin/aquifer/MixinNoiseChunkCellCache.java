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
}
