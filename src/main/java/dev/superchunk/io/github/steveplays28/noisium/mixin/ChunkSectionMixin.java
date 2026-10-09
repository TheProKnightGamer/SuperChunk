package dev.superchunk.io.github.steveplays28.noisium.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * SuperChunk: NOT REGISTERED (removed from noisium.mixins.json 2026-10-06) — do not re-add.
 * Biome lookup order is part of vanilla's output: {@code Climate.RTree.search} starts from the
 * thread's previous result and keeps it on an exact distance tie, so visiting the cells y,z,x
 * instead of vanilla's x,y,z changed tie-broken biomes (~3% of chunks, e.g. plains vs forest
 * columns, river vs dripstone_caves cells) versus vanilla on the same seed. Its aim, a faster biome
 * fill, is met exactly by {@link dev.superchunk.worldgen.ClimateColumns} (per-column climate reuse,
 * vanilla lookup order). The reordering only changed the palette-write order, and palette and loop
 * work is 2.3% of biome-fill time (~0.1% of worldgen CPU, JFR 2026-10-07), so it had nothing to win.
 */
@Mixin(LevelChunkSection.class)
public class ChunkSectionMixin {
	@Unique
	private static final int noisium$sliceSize = 4;

	@Shadow
	private PalettedContainerRO<Holder<Biome>> biomes;

	/**
	 * @author Steveplays28
	 * @reason Axis order micro-optimisation
	 */
	@Overwrite
	public void fillBiomesFromNoise(BiomeResolver biomeResolver, Climate.Sampler climateSampler, int x, int y, int z) {
		PalettedContainer<Holder<Biome>> palettedContainer = this.biomes.recreate();

		for (int posY = 0; posY < noisium$sliceSize; ++posY) {
			for (int posZ = 0; posZ < noisium$sliceSize; ++posZ) {
				for (int posX = 0; posX < noisium$sliceSize; ++posX) {
					palettedContainer.getAndSetUnchecked(posX, posY, posZ, biomeResolver.getNoiseBiome(x + posX, y + posY, z + posZ, climateSampler));
				}
			}
		}

		this.biomes = palettedContainer;
	}
}
