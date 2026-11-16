package dev.superchunk.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.superchunk.chunkio.FastChunkPaletteRead;
import dev.superchunk.compat.DhFastPaths;
import dev.superchunk.compat.DhSelfCheck;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Distant Horizons reads the block states of a saved chunk without the DataFixerUpper codec.
 *
 * <p>For a world that already exists, DH builds its LODs from the region files: its
 * {@code ChunkCompoundTagParser} turns each saved chunk into a {@code LevelChunk}, decoding every
 * section's block states with the same codec vanilla's chunk loader uses
 * ({@code PalettedContainer.codecRW(BLOCK_STATE_REGISTRY, BlockState.CODEC, SECTION_STATES, AIR)}).
 * That codec decodes each palette entry through {@code BlockState.CODEC}: a name parse, a registry
 * lookup and a property map, boxed through {@code DataResult}. SuperChunk already reads vanilla's
 * chunk loads directly ({@link FastChunkPaletteRead}); this gives DH's parser the same read. The
 * palette list and the packed data go to vanilla's own {@code unpack}, so the container is the one
 * the codec builds; an entry the direct read does not understand returns DH's codec result,
 * warnings included.
 *
 * <p>Applied when DH's two methods hash to a checked version, under self-check when they differ
 * only in how they compute ({@link dev.superchunk.compat.DhFastPathTable}). Kill switches:
 * {@code -Dsuperchunk.dh.fastPaths=false}, {@code -Dsuperchunk.io.fastPaletteRead=false};
 * {@code -Dsuperchunk.io.fastPaletteRead.verify=true} decodes both ways and compares every section.
 */
// @Pseudo: the target is a Distant Horizons class, absent from the compile classpath.
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.chunkFileHandling.ChunkCompoundTagParser_forge", remap = false)
public abstract class MixinDhChunkTagPalette {

    /** Not null when this patch runs under self-check on the installed DH. */
    @Unique
    private static final DhSelfCheck SUPERCHUNK$PALETTE_CHECK = DhFastPaths.selfCheck("MixinDhChunkTagPalette", "sections");

    // ordinal 0: the block states; the biomes' parse is the second.
    @WrapOperation(
            method = "readAndPopulateSections",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
                    ordinal = 0, remap = false),
            remap = false)
    private static DataResult<PalettedContainer<BlockState>> superchunk$directBlockStates(
            Codec<PalettedContainer<BlockState>> codec, DynamicOps<?> ops, Object input,
            Operation<DataResult<PalettedContainer<BlockState>>> original) {
        DhSelfCheck check = SUPERCHUNK$PALETTE_CHECK;
        if (check != null && check.failed()) {
            return original.call(codec, ops, input);
        }
        if (FastChunkPaletteRead.VERIFY) {
            DataResult<PalettedContainer<BlockState>> own = original.call(codec, ops, input);
            FastChunkPaletteRead.verifyAgainst(input, own);
            return own;
        }
        DataResult<PalettedContainer<BlockState>> direct;
        if (check == null) {
            direct = FastChunkPaletteRead.readBlockStates(input);
        } else {
            try {
                direct = FastChunkPaletteRead.readBlockStates(input);
            } catch (Throwable t) {
                check.fail("its code threw " + t, t);
                return original.call(codec, ops, input);
            }
        }
        if (direct == null) {
            return original.call(codec, ops, input);
        }
        if (check != null && check.sample()) {
            // Under self-check: DH's own decode too, and it is the one returned.
            DataResult<PalettedContainer<BlockState>> own = original.call(codec, ops, input);
            if (FastChunkPaletteRead.sameBlocks(direct, own)) {
                check.passed();
            } else {
                check.fail("a section's block states differ from the codec's", null);
            }
            return own;
        }
        return direct;
    }
}
