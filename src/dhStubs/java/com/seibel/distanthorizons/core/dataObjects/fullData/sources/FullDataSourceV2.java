package com.seibel.distanthorizons.core.dataObjects.fullData.sources;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public class FullDataSourceV2 {
    public final FullDataPointIdMap mapping;
    public final ByteArrayList columnGenerationSteps;
    public final ByteArrayList columnWorldCompressionMode;
    public final LongArrayList[] dataPoints;
    public boolean isEmpty;
    public Boolean applyToParent;

    private FullDataSourceV2() {
        throw new UnsupportedOperationException();
    }

    public static FullDataSourceV2 createEmpty(long pos) {
        throw new UnsupportedOperationException();
    }

    public LongArrayList getColumnAtRelPos(int relX, int relZ) {
        throw new UnsupportedOperationException();
    }

    public void setSingleColumn(LongArrayList longArray, int relX, int relZ, EDhApiWorldGenerationStep worldGenStep,
                                EDhApiWorldCompressionMode worldCompressionMode) {
        throw new UnsupportedOperationException();
    }

    public void close() {
        throw new UnsupportedOperationException();
    }
}
