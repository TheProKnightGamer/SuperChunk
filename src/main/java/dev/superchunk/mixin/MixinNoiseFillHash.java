package dev.superchunk.mixin;

import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Test hook: {@code -Dsuperchunk.debug.noiseFillHash=<file>} appends {@code "cx cz hash"} for every
 * chunk the CPU noise fill ({@code doFill}) produces — a hash of every block state, the fluid
 * post-processing lists in order, and the two worldgen heightmaps, right after the fill.
 *
 * <p>A chunk's noise fill depends only on its own position (no blending in a fresh world), unlike
 * the finished chunk, which features from neighbours generated in scheduling order can change. So
 * two runs must produce the same line per chunk, which makes this the whole-pregen check for an
 * exact change to the fill ({@code tools/noisehashdiff.py}). Off by default: one static test.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseFillHash {

    @Unique
    private static final String SUPERCHUNK$PATH = System.getProperty("superchunk.debug.noiseFillHash");

    @Inject(method = "doFill", at = @At("RETURN"))
    private void superchunk$hashFill(Blender blender, StructureManager structureManager, RandomState randomState,
                                     ChunkAccess chunk, int minCellY, int cellCountY,
                                     CallbackInfoReturnable<ChunkAccess> cir,
                                     @com.llamalad7.mixinextras.sugar.Local net.minecraft.world.level.levelgen.NoiseChunk noiseChunk) {
        if (SUPERCHUNK$PATH == null) {
            return;
        }
        long h = 0xCBF29CE484222325L;
        for (LevelChunkSection section : chunk.getSections()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        h = superchunk$mix(h, Block.BLOCK_STATE_REGISTRY.getId(section.getBlockState(x, y, z)));
                    }
                }
            }
        }
        final long blocks = h;
        ShortList[] marks = chunk.getPostProcessing();
        for (int i = 0; i < marks.length; i++) {
            h = superchunk$mix(h, 1000 + i);
            if (marks[i] != null) {
                for (int k = 0; k < marks[i].size(); k++) {
                    h = superchunk$mix(h, marks[i].getShort(k));
                }
            }
        }
        final long withMarks = h;
        for (Heightmap.Types type : new Heightmap.Types[]{Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG}) {
            for (long word : chunk.getOrCreateHeightmapUnprimed(type).getRawData()) {
                h = superchunk$mix(h, word);
            }
        }
        // Structure terrain adaptation: flag chunks whose fill also ran the beardifier, so a
        // comparison can tell which code a mismatch went through.
        boolean beard = ((dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler) noiseChunk).getBeardifier()
                instanceof dev.superchunk.gpu.dfc.ScBeardBoxAccess box && box.superchunk$beardBox(new int[6]);
        // columns: cx cz total [B] | blocks blocks+marks (to tell which part differs)
        superchunk$append(chunk.getPos().x + " " + chunk.getPos().z + " " + Long.toHexString(h) + (beard ? " B" : "")
                + " | " + Long.toHexString(blocks) + " " + Long.toHexString(withMarks) + "\n");
    }

    @Unique
    private static long superchunk$mix(long h, long v) {
        return (h ^ v) * 0x100000001B3L;
    }

    @Unique
    private static synchronized void superchunk$append(String line) {
        try {
            Files.writeString(Path.of(SUPERCHUNK$PATH), line, StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // test hook: a missed line shows up as a missing chunk in the comparison
        }
    }
}
