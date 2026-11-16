package com.seibel.distanthorizons.core.wrapperInterfaces.chunk;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public class ChunkLightStorage {
    public int minY;
    public int maxY;
    public LightSection[] lightSections;

    public void set(int x, int y, int z, int lightLevel) {
        throw new UnsupportedOperationException();
    }

    public static class LightSection {
        public byte constantValue;
        public long[] data;
        public short[] counts;

        public LightSection(int initialValue) {
            throw new UnsupportedOperationException();
        }
    }
}
