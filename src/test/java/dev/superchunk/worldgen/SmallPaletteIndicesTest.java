package dev.superchunk.worldgen;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Random;
import net.minecraft.util.SimpleBitStorage;

/** Standalone differential checks; no game bootstrap or mixin runtime is required. */
public final class SmallPaletteIndicesTest {
    private static volatile long benchmarkSink;

    public static void main(String[] args) {
        Random random = new Random(0x5ca11L);
        int cases = 0;
        int[] sizes = {0, 1, 2, 7, 8, 9, 10, 11, 12, 15, 16, 21, 31, 32, 63, 64, 65, 127, 255, 4096};
        for (int bits = 1; bits <= 6; bits++) {
            for (int paletteSize = 1; paletteSize <= Math.min(8, 1 << bits); paletteSize++) {
                for (int size : sizes) {
                    for (int trial = 0; trial < 20; trial++) {
                        int[] values = new int[size];
                        // Include palettes with unreferenced entries: early termination may
                        // depend on entries actually encountered, never just palette size.
                        int used = 1 + random.nextInt(paletteSize);
                        for (int i = 0; i < size; i++) {
                            values[i] = random.nextInt(used);
                        }
                        SimpleBitStorage storage = new SimpleBitStorage(bits, size, values);
                        if (size != 0) {
                            // Trailing padding must not introduce phantom IDs.
                            int tailEntries = size % (64 / bits);
                            if (tailEntries != 0) {
                                int validBits = tailEntries * bits;
                                long[] raw = storage.getRaw();
                                raw[raw.length - 1] |= random.nextLong() << validBits;
                            }
                        }
                        LinkedHashSet<Integer> expected = new LinkedHashSet<>();
                        storage.getAll(expected::add);
                        long actual = SmallPaletteIndices.collect(storage.getRaw(), bits, size, paletteSize);
                        for (int id : expected) {
                            check(((int) actual & 15) - 1 == id,
                                    "wrong encounter order for bits=" + bits + ", size=" + size);
                            actual >>>= 4;
                        }
                        check(actual == 0, "unexpected ID from padding");
                        cases++;
                    }
                }
            }
        }
        for (int invalidId : new int[] {8, 15, 31, 63}) {
            check(SmallPaletteIndices.collect(new long[] {invalidId}, 6, 1, 8) == SmallPaletteIndices.INVALID,
                    "invalid palette IDs must fall through instead of truncating");
        }
        System.out.println("SmallPaletteIndices: " + cases + " differential cases passed");
        if (Arrays.asList(args).contains("--benchmark")) {
            benchmark();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Conservative baseline: the existing complete scan, already stripped of lambda allocations. */
    private static long fullScan(long[] data, int bits, int size) {
        int perWord = 64 / bits;
        int mask = (1 << bits) - 1;
        int seen = 0;
        int count = 0;
        long ordered = 0;
        int remaining = size;
        for (long word : data) {
            int entries = Math.min(perWord, remaining);
            for (int i = 0; i < entries; i++) {
                int id = (int) word & mask;
                int bit = 1 << id;
                if ((seen & bit) == 0) {
                    seen |= bit;
                    ordered |= (long) (id + 1) << (count++ * 4);
                }
                word >>>= bits;
            }
            remaining -= entries;
        }
        return ordered;
    }

    private static void benchmark() {
        Random random = new Random(42);
        for (int paletteSize : new int[] {1, 2, 4, 8}) {
            long[][] datasets = new long[256][];
            for (int d = 0; d < datasets.length; d++) {
                int[] values = new int[64];
                for (int i = 0; i < values.length; i++) {
                    values[i] = random.nextInt(paletteSize);
                }
                datasets[d] = new SimpleBitStorage(3, 64, values).getRaw();
            }
            long[] baseline = new long[7];
            long[] optimized = new long[7];
            final int iterations = 1_000_000;
            for (int round = -4; round < baseline.length; round++) {
                long sum = 0;
                long start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    sum += fullScan(datasets[i & 255], 3, 64);
                }
                long oldTime = System.nanoTime() - start;
                benchmarkSink = sum;
                sum = 0;
                start = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    sum += SmallPaletteIndices.collect(datasets[i & 255], 3, 64, paletteSize);
                }
                long newTime = System.nanoTime() - start;
                benchmarkSink = sum;
                if (round >= 0) {
                    baseline[round] = oldTime;
                    optimized[round] = newTime;
                }
            }
            Arrays.sort(baseline);
            Arrays.sort(optimized);
            System.out.printf("64 entries, %d palette IDs: full scan %.1f ns, ordered collector %.1f ns%n",
                    paletteSize, baseline[3] / (double) iterations, optimized[3] / (double) iterations);
        }
    }
}
