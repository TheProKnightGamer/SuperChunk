package dev.superchunk.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

import java.util.function.Function;

/**
 * Implemented on {@code BiomeManager}: the biome cell path ({@code MixinBiomeManagerQuartCache})
 * as a plain method. {@code getBiome} reaches the same path through a {@code @WrapMethod}, whose
 * generated body allocates its {@code Operation} on every call (3.6 GB per radius-1024 pregen from
 * the surface rules alone); calling this first skips that wrapper.
 */
public interface BiomeCellLookup {

    /** The biome {@code getBiome(pos)} returns, or {@code null} when the cell path does not apply. */
    Holder<Biome> superchunk$cellBiome(BlockPos pos);

    /** {@code getBiome} through the cell path first; the original method answers whatever it declines. */
    static Function<BlockPos, Holder<Biome>> of(BiomeManager manager, Function<BlockPos, Holder<Biome>> fallback) {
        if (!(manager instanceof BiomeCellLookup cells)) {
            return fallback;
        }
        return pos -> {
            Holder<Biome> biome = cells.superchunk$cellBiome(pos);
            return biome != null ? biome : fallback.apply(pos);
        };
    }
}
