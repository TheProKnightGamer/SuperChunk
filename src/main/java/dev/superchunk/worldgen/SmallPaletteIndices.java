package dev.superchunk.worldgen;

/** Allocation-free, encounter-ordered distinct IDs for palettes containing at most eight values. */
public final class SmallPaletteIndices {
    /** An encountered ID was outside the supplied palette; use the ordinary reader. */
    public static final long INVALID = -1L;

    private SmallPaletteIndices() {
    }

    /**
     * Scans vanilla's non-straddling bit storage. The first ID occupies the low nibble;
     * IDs are encoded as {@code id + 1}, leaving zero as the end marker. The caller
     * guarantees 1..6 bits per entry and a palette with 1..8 contiguous IDs.
     *
     * <p>Collect before invoking any consumer, as vanilla does: a consumer may change
     * the storage. Once every palette ID has appeared, the remaining scan is redundant.
     */
    public static long collect(long[] storage, int bits, int size, int paletteSize) {
        int perWord = 64 / bits;
        int mask = (1 << bits) - 1;
        int seen = 0;
        int count = 0;
        long ordered = 0L;
        int remaining = size;
        for (long word : storage) {
            int entries = Math.min(perWord, remaining);
            for (int i = 0; i < entries; i++) {
                int id = (int) word & mask;
                if (id >= paletteSize) {
                    return INVALID;
                }
                int bit = 1 << id;
                if ((seen & bit) == 0) {
                    seen |= bit;
                    ordered |= (long) (id + 1) << (count * 4);
                    if (++count == paletteSize) {
                        return ordered;
                    }
                }
                word >>>= bits;
            }
            remaining -= entries;
            if (remaining == 0) {
                break;
            }
        }
        return ordered;
    }
}
