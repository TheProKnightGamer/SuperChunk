package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.mixin.the_end_biome_cache;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(TheEndBiomeSource.class)
public abstract class MixinTheEndBiomeSource {

    @Unique
    private final ThreadLocal<Long2ObjectLinkedOpenHashMap<Holder<Biome>>> c2me$cache =
            ThreadLocal.withInitial(Long2ObjectLinkedOpenHashMap::new);
    @Unique
    private static final int C2ME$CACHE_CAPACITY = 1024;

    /**
     * Upstream C2ME's end-biome cache, converted from {@code @Overwrite} + a
     * hand-maintained VanillaCopy to a wrap of the real method: on a miss the ORIGINAL
     * method computes the biome, so vanilla drift is impossible and other mods'
     * injections into {@code getNoiseBiome} compose (the overwrite silently discarded
     * them). Cache key is (biomeX, biomeZ), same as upstream — the End's erosion density
     * function is y-independent, so the result is too. Values are cached per composed
     * result, so a hit is reference-identical to what the wrapped method returned.
     */
    @WrapMethod(
            method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            require = 0)
    private Holder<Biome> c2me$cachedGetNoiseBiome(int biomeX, int biomeY, int biomeZ,
                                                   Climate.Sampler multiNoiseSampler,
                                                   Operation<Holder<Biome>> original) {
        final long key = ChunkPos.asLong(biomeX, biomeZ);
        final Long2ObjectLinkedOpenHashMap<Holder<Biome>> cacheThreadLocal = this.c2me$cache.get();
        final Holder<Biome> biome = cacheThreadLocal.get(key);
        if (biome != null) {
            return biome;
        }
        final Holder<Biome> gennedBiome = original.call(biomeX, biomeY, biomeZ, multiNoiseSampler);
        cacheThreadLocal.put(key, gennedBiome);
        if (cacheThreadLocal.size() > C2ME$CACHE_CAPACITY) {
            for (int i = 0; i < C2ME$CACHE_CAPACITY / 16; i++) {
                cacheThreadLocal.removeFirst();
            }
        }
        return gennedBiome;
    }
}
