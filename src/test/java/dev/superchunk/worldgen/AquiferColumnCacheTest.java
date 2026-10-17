package dev.superchunk.worldgen;

import dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.ScAquiferColumnCache;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import java.util.Random;

/** Differential checks against the old ceiling map, including the out-of-grid fallback. */
public final class AquiferColumnCacheTest {
    private static volatile long sink;

    public static void main(String[] args) {
        Random random = new Random(0xa91feL);
        int checks = 0;
        for (int min : new int[] {Integer.MIN_VALUE, -1_875_001, -2, 0, 1_874_999, Integer.MAX_VALUE}) {
            for (int sizeX = 1; sizeX <= 5; sizeX++) {
                for (int sizeZ = 1; sizeZ <= 5; sizeZ++) {
                    ScAquiferColumnCache cache = new ScAquiferColumnCache(min, -min, sizeX, sizeZ);
                    Long2IntOpenHashMap reference = new Long2IntOpenHashMap();
                    reference.defaultReturnValue(Integer.MIN_VALUE);
                    for (int i = 0; i < 10_000; i++) {
                        int x = min + random.nextInt(12) - 4;
                        int z = -min + random.nextInt(12) - 4;
                        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
                        if (cache.get(x, z) != reference.get(key)) {
                            throw new AssertionError("ceiling mismatch at " + x + "," + z);
                        }
                        int value = switch (i % 5) {
                            case 0 -> Integer.MIN_VALUE;
                            case 1 -> Integer.MAX_VALUE;
                            default -> random.nextInt(8192) - 4096;
                        };
                        reference.put(key, value);
                        cache.put(x, z, value);
                        if (cache.get(x, z) != value) {
                            throw new AssertionError("write not retained");
                        }
                        checks++;
                    }
                }
            }
        }
        System.out.println("AquiferColumnCache: " + checks + " map-parity cases passed");
        if (Arrays.asList(args).contains("--benchmark")) {
            benchmark();
        }
    }

    private static void benchmark() {
        final int baseX = 250, baseZ = -173;
        ScAquiferColumnCache cache = new ScAquiferColumnCache(baseX, baseZ, 3, 3);
        Long2IntOpenHashMap reference = new Long2IntOpenHashMap();
        reference.defaultReturnValue(Integer.MIN_VALUE);
        for (int x = baseX; x <= baseX + 1; x++) {
            for (int z = baseZ; z <= baseZ + 1; z++) {
                int value = 63 + x - z;
                cache.put(x, z, value);
                reference.put(((long) x << 32) ^ (z & 0xFFFFFFFFL), value);
            }
        }
        Random random = new Random(21);
        int[] xs = new int[1024], zs = new int[1024];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = baseX + random.nextInt(2);
            zs[i] = baseZ + random.nextInt(2);
        }
        long[] baseline = new long[7], optimized = new long[7];
        final int iterations = 5_000_000;
        for (int round = -5; round < baseline.length; round++) {
            long sum = 0;
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                int at = i & 1023;
                sum += reference.get(((long) xs[at] << 32) ^ (zs[at] & 0xFFFFFFFFL));
            }
            long oldTime = System.nanoTime() - start;
            sink = sum;
            sum = 0;
            start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                int at = i & 1023;
                sum += cache.get(xs[at], zs[at]);
            }
            long newTime = System.nanoTime() - start;
            sink = sum;
            if (round >= 0) {
                baseline[round] = oldTime;
                optimized[round] = newTime;
            }
        }
        Arrays.sort(baseline);
        Arrays.sort(optimized);
        System.out.printf("Aquifer ceiling memo: hash map %.2f ns, dense table %.2f ns per lookup%n",
                baseline[3] / (double) iterations, optimized[3] / (double) iterations);
    }
}
