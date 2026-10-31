package dev.superchunk.mixin.compat;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Distant Horizons' LOD builder looks a column's biome up once per 4-block cell, not once per block.
 *
 * <p>{@code LodDataBuilder.createFromChunk} walks every column of a generated chunk from the
 * surface down and asks the chunk wrapper for the biome at each block. DH's wrapper answers from
 * the chunk's biome at the block's quart (its 4x4x4 cell), then maps it to DH's wrapper object
 * through a concurrent map: two lookups per block, about 4.5% of DH's world-gen CPU. Walking down,
 * four blocks in a row share a cell, so the answer for the previous block's cell is reused while
 * the cell is the same. Only DH's own wrapper class is answered this way (its biome depends on the
 * cell alone); any other passes through.
 *
 * <p>Applied only when the DH methods this relies on hash to the version it was checked against
 * ({@link dev.superchunk.compat.DhFastPaths}). Kill switch: {@code -Dsuperchunk.dh.fastPaths=false}.
 */
// @Pseudo: the target is a Distant Horizons class, absent from the compile classpath.
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder", remap = false)
public abstract class MixinDhLodBuilderBiome {

    @Unique
    private static final String SUPERCHUNK$WRAPPER = "com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_forge";

    /** DH's wrapper class, once seen; avoids a name compare per block. */
    @Unique
    private static volatile Class<?> superchunk$wrapperClass;

    @Redirect(method = "createFromChunk",
            at = @At(value = "INVOKE",
                    target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/chunk/IChunkWrapper;getBiome(III)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;",
                    ordinal = 1))
    private static IBiomeWrapper superchunk$cellBiome(IChunkWrapper chunk, int relX, int y, int relZ,
                                                      @Share("superchunk$cell") LocalIntRef cell,
                                                      @Share("superchunk$biome") LocalRef<IBiomeWrapper> biome) {
        Class<?> type = chunk.getClass();
        if (type != superchunk$wrapperClass) {
            if (!type.getName().equals(SUPERCHUNK$WRAPPER)) {
                return chunk.getBiome(relX, y, relZ);
            }
            superchunk$wrapperClass = type;
        }
        // Bit 30 marks a filled memo; x and z cells are 0..3, the y cell is masked to 16 bits.
        int key = 1 << 30 | (relX >> 2) << 24 | (relZ >> 2) << 16 | ((y >> 2) & 0xFFFF);
        if (cell.get() == key) {
            return biome.get();
        }
        IBiomeWrapper answer = chunk.getBiome(relX, y, relZ);
        cell.set(key);
        biome.set(answer);
        return answer;
    }
}
