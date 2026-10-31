package com.seibel.distanthorizons.core.dataObjects.fullData;

import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public class FullDataPointIdMap {
    public IBiomeWrapper getBiomeWrapper(int id) {
        throw new UnsupportedOperationException();
    }

    public IBlockStateWrapper getBlockStateWrapper(int id) {
        throw new UnsupportedOperationException();
    }

    public int size() {
        throw new UnsupportedOperationException();
    }

    public int addIfNotPresentAndGetId(IBiomeWrapper biome, IBlockStateWrapper blockState) {
        throw new UnsupportedOperationException();
    }

    public void addAll(FullDataPointIdMap inputMap) {
        throw new UnsupportedOperationException();
    }

    public void clear(long pos) {
        throw new UnsupportedOperationException();
    }
}
