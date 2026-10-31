package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public class FullDataPointUtil {
    public static int getId(long dataPoint) {
        throw new UnsupportedOperationException();
    }

    public static long setId(long dataPoint, int id) {
        throw new UnsupportedOperationException();
    }

    public static long encode(int id, int height, int relMinY, byte blockLight, byte skyLight) throws DataCorruptedException {
        throw new UnsupportedOperationException();
    }
}
