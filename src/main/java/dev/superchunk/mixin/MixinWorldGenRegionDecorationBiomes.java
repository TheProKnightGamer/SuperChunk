package dev.superchunk.mixin;

import dev.superchunk.worldgen.DecorationBiomes;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

/** The decoration state {@link DecorationBiomes} keeps on the region being decorated. */
@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegionDecorationBiomes implements DecorationBiomes.Region {

    @Unique private int superchunk$decorX;
    @Unique private int superchunk$decorZ;
    @Unique private Set<Holder<Biome>> superchunk$collected;
    @Unique private PlacedFeature superchunk$everywhereFeature;
    @Unique private Holder<Biome> superchunk$everywhereBiome;

    @Override
    public void superchunk$decorating(int chunkX, int chunkZ, Set<Holder<Biome>> collected) {
        this.superchunk$decorX = chunkX;
        this.superchunk$decorZ = chunkZ;
        this.superchunk$collected = collected;
        this.superchunk$everywhereFeature = null;
        this.superchunk$everywhereBiome = null;
    }

    @Override
    public Set<Holder<Biome>> superchunk$collectedBiomes() {
        return this.superchunk$collected;
    }

    @Override
    public void superchunk$everywhere(PlacedFeature feature, Holder<Biome> anyBiome) {
        this.superchunk$everywhereFeature = feature;
        this.superchunk$everywhereBiome = anyBiome;
    }

    @Override
    public Holder<Biome> superchunk$everywhereBiome(PlacedFeature feature, int x, int z) {
        if (feature == null || feature != this.superchunk$everywhereFeature) {
            return null;
        }
        // BiomeManager.getBiome reads one of the quarts (q, q + 1) with q = (block - 2) >> 2 on each axis
        final int qx = (x - 2) >> 2;
        final int qz = (z - 2) >> 2;
        final int cx = this.superchunk$decorX;
        final int cz = this.superchunk$decorZ;
        if ((qx >> 2) < cx - 1 || ((qx + 1) >> 2) > cx + 1 || (qz >> 2) < cz - 1 || ((qz + 1) >> 2) > cz + 1) {
            return null;
        }
        return this.superchunk$everywhereBiome;
    }
}
