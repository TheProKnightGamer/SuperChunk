package dev.superchunk.worldgen;

/** Exact arithmetic shortcuts for vanilla's nearest-quart-cell biome selection. */
public final class BiomeFiddleMath {
    private BiomeFiddleMath() {
    }

    /** Floor modulus by a positive power of two, including negative dividends. */
    public static int floorMod1024(long value) {
        return (int) (value & 1023L);
    }
}
