package com.seibel.distanthorizons.core.wrapperInterfaces.chunk;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public interface IChunkWrapper {
    IBiomeWrapper getBiome(int relX, int relY, int relZ);

    IBlockStateWrapper getBlockState(int relX, int relY, int relZ, IMutableBlockPosWrapper mcBlockPos, IBlockStateWrapper guess);

    IMutableBlockPosWrapper getMutableBlockPosWrapper();

    DhChunkPos getChunkPos();

    boolean isDhBlockLightingCorrect();

    int getHeight();

    int getMinNonEmptyHeight();

    int getInclusiveMinBuildHeight();

    int getExclusiveMaxBuildHeight();

    int getSolidHeightMapValue(int xRel, int zRel);

    int getLightBlockingHeightMapValue(int xRel, int zRel);

    int getDhBlockLight(int relX, int y, int relZ);

    int getDhSkyLight(int relX, int y, int relZ);
}
