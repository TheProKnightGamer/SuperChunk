package dev.superchunk.net.caffeinemc.mods.lithium.common.world.block_pattern_matching;

import dev.superchunk.net.caffeinemc.mods.lithium.common.util.Pos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;

public class BlockSearch {

    // PORT-1.20.1: core.BlockBox (1.21 record, min()/max() BlockPos) -> BoundingBox (int accessors).
    public static boolean hasAtLeast(LevelReader levelReader, BoundingBox searchBox, Block requiredBlock, int requiredBlockCount) {
        Predicate<BlockState> predicate = blockState -> blockState.is(requiredBlock);
        for (int chunkX = SectionPos.blockToSectionCoord(searchBox.minX()); chunkX <= SectionPos.blockToSectionCoord(searchBox.maxX()); chunkX++) {
            for (int chunkZ = SectionPos.blockToSectionCoord(searchBox.minZ()); chunkZ <= SectionPos.blockToSectionCoord(searchBox.maxZ()); chunkZ++) {

                ChunkAccess chunk = levelReader.getChunk(chunkX, chunkZ);
                int minSectionYIndex = Pos.SectionYIndex.fromBlockCoord(levelReader, searchBox.minY());
                int maxSectionYIndex = Pos.SectionYIndex.fromBlockCoord(levelReader, searchBox.maxY());
                for (int sectionYIndex = minSectionYIndex; sectionYIndex <= maxSectionYIndex; sectionYIndex++) {
                    if (sectionYIndex >= 0 && sectionYIndex < chunk.getSectionsCount()) {
                        LevelChunkSection section = chunk.getSection(sectionYIndex);
                        if (section.maybeHas(predicate)) {
                            int sectionYCoord = Pos.SectionYCoord.fromSectionIndex(levelReader, sectionYIndex);
                            requiredBlockCount -= countBlocksInBoxInSection(
                                    section,
                                    Math.max(searchBox.minX(), chunkX << 4),
                                    Math.max(searchBox.minY(), sectionYCoord << 4),
                                    Math.max(searchBox.minZ(), chunkZ << 4),
                                    Math.min(searchBox.maxX(), (chunkX << 4) + 15),
                                    Math.min(searchBox.maxY(), (sectionYCoord << 4) + 15),
                                    Math.min(searchBox.maxZ(), (chunkZ << 4) + 15),
                                    requiredBlock, requiredBlockCount
                            );
                            if (requiredBlockCount <= 0) {
                                return true;
                            }
                        }
                    } else if (requiredBlock == Blocks.VOID_AIR) {
                        return true; //Handle VOID_AIR somewhat correctly. We should count the volume, but noone uses void air anyway
                    }
                }
            }
        }
        return false;
    }

    public static int countBlocksInBoxInSection(LevelChunkSection section, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block requiredBlock, int findMax) {
        int found = 0;
        //Optimized iteration order xzy
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (section.getBlockState(x & 15, y & 15, z & 15).is(requiredBlock)) {
                        found++;
                        if (found >= findMax) {
                            return found;
                        }
                    }
                }
            }
        }
        return found;
    }
}
