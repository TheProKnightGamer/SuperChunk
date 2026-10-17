package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * SuperChunk GPU (cross-chunk batching): read-only PEEK of the {@code NoiseChunk}
 * cached on a {@link ChunkAccess}.
 *
 * <p>The {@code NoiseChunk} is created lazily at the BIOMES stage
 * ({@code NoiseBasedChunkGenerator.createBiomes} -&gt; {@code getOrCreateNoiseChunk})
 * and reused by the NOISE stage. At the noise status boundary the cross-chunk batch
 * prefetch needs that already-created sampler (to derive the chunk's cell-corner
 * lattice extent) WITHOUT creating one — this {@link Accessor} returns the cached
 * field, or {@code null} if none was created (e.g. biomes loaded from disk), in which
 * case the prefetch is simply skipped and the per-chunk path runs.
 */
@Mixin(ChunkAccess.class)
public interface IChunkAccessNoiseChunk {

    @Accessor("noiseChunk")
    NoiseChunk superchunk$getNoiseChunk();
}
