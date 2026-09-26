package dev.superchunk.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.BiomeManager;

/**
 * Differential checks of {@link BiomeFiddleCache} against the unmodified
 * {@code BiomeManager.getFiddledDistance} and {@code getBiome}. Pass {@code bench} to time both.
 */
public final class BiomeFiddleCacheTest {
    private static final MethodHandle VANILLA_DISTANCE;
    private static long distances;
    private static long selections;

    static {
        try {
            VANILLA_DISTANCE = MethodHandles.privateLookupIn(BiomeManager.class, MethodHandles.lookup())
                    .findStatic(BiomeManager.class, "getFiddledDistance", MethodType.methodType(
                            double.class, long.class, int.class, int.class, int.class,
                            double.class, double.class, double.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args) throws Throwable {
        Random random = new Random(0xf1dd1e5L);
        BiomeFiddleCache cache = BiomeFiddleCache.local();
        int[] quart = new int[3];
        long[] seeds = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, BiomeManager.obfuscateSeed(8675309L)};
        // Surface-shaped access: whole chunks of columns, descending y, alternating seeds so the
        // table is cleared and refilled, and full 8-corner selection against the real getBiome.
        for (int chunk = 0; chunk < 60; chunk++) {
            long seed = seeds[chunk % seeds.length];
            BiomeManager manager = manager(seed, quart);
            int cx = (random.nextInt(200_000) - 100_000) << 4;
            int cz = (random.nextInt(200_000) - 100_000) << 4;
            for (int x = cx; x < cx + 16; x++) {
                for (int z = cz; z < cz + 16; z++) {
                    for (int y = 120; y >= -64; y -= 1 + random.nextInt(3)) {
                        select(cache, manager, seed, x, y, z, quart);
                    }
                }
            }
        }
        // Scattered positions: constant slot collisions and replacement.
        for (int i = 0; i < 300_000; i++) {
            long seed = seeds[random.nextInt(seeds.length)];
            int x = random.nextInt(60_000_000) - 30_000_000;
            int y = random.nextInt(4_096) - 2_048;
            int z = random.nextInt(60_000_000) - 30_000_000;
            select(cache, manager(seed, quart), seed, x, y, z, quart);
        }
        // Coordinates at and beyond the packed-key range must bypass the table, still exactly.
        int[] edges = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -0x800001, -0x800000, -0x7FFFFF, -1, 0, 1,
                0x7FFFFE, 0x7FFFFF, 0x800000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        int[] yEdges = {Integer.MIN_VALUE, -0x801, -0x800, -0x7FF, -1, 0, 0x7FE, 0x7FF, 0x800, Integer.MAX_VALUE};
        for (long seed : seeds) {
            for (int x : edges) {
                for (int y : yEdges) {
                    for (int z : edges) {
                        for (int k = 0; k < 2; k++) {
                            double xn = random.nextInt(4) / 4.0 - random.nextInt(2);
                            double yn = random.nextInt(4) / 4.0 - random.nextInt(2);
                            double zn = random.nextInt(4) / 4.0 - random.nextInt(2);
                            distance(cache, seed, x, y, z, xn, yn, zn);
                        }
                    }
                }
            }
            check(BiomeFiddleCache.encode(0x800000, 0, 0) == BiomeFiddleCache.UNCACHEABLE, "x range");
            check(BiomeFiddleCache.encode(0, 0x800, 0) == BiomeFiddleCache.UNCACHEABLE, "y range");
            check(BiomeFiddleCache.encode(0, 0, -0x800001) == BiomeFiddleCache.UNCACHEABLE, "z range");
            check(BiomeFiddleCache.encode(0, 0, 0) != 0L, "empty-slot key");
        }
        // Cells at the packed-key edges: the cell path must decline (-1) or agree.
        for (long seed : seeds) {
            BiomeManager manager = manager(seed, quart);
            for (int q : new int[] {-0x800001, -0x800000, -0x7FFFFF, 0x7FFFFE, 0x7FFFFF, 0x800000}) {
                for (int d = 0; d < 4; d++) {
                    select(cache, manager, seed, (q << 2) + 2 + d, 64 + d, (q << 2) + 2 + d, quart);
                    select(cache, manager, seed, 5 + d, (q < 0 ? -0x801 : 0x7FF) * 4 + 2 + d, 7 + d, quart);
                }
            }
        }
        System.out.println("BiomeFiddleCache: " + distances + " bit-exact distances and " + selections
                + " vanilla nearest-cell selections (per-corner and cell paths) passed");
        if (args.length > 0 && args[0].equals("bench")) {
            bench(random);
        }
    }

    private static void select(BiomeFiddleCache cache, BiomeManager manager, long seed, int x, int y, int z,
                               int[] quart) throws Throwable {
        manager.getBiome(new BlockPos(x, y, z));
        int bx = x - 2, by = y - 2, bz = z - 2;
        int qx = bx >> 2, qy = by >> 2, qz = bz >> 2;
        double fx = (bx & 3) / 4.0, fy = (by & 3) / 4.0, fz = (bz & 3) / 4.0;
        int winner = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            int cx = corner >> 2, cy = (corner >> 1) & 1, cz = corner & 1;
            double d = distance(cache, seed, qx + cx, qy + cy, qz + cz, fx - cx, fy - cy, fz - cz);
            if (best > d) {
                best = d;
                winner = corner;
            }
        }
        check(quart[0] == qx + (winner >> 2) && quart[1] == qy + ((winner >> 1) & 1)
                && quart[2] == qz + (winner & 1), "nearest-cell mismatch at " + x + "," + y + "," + z);
        int corner = cache.nearestCorner(seed, x, y, z);
        boolean inRange = BiomeFiddleCache.encode(qx, qy, qz) != BiomeFiddleCache.UNCACHEABLE;
        check(inRange ? corner == winner : corner == -1, "cell-path corner mismatch at " + x + "," + y + "," + z);
        selections++;
    }

    private static double distance(BiomeFiddleCache cache, long seed, int x, int y, int z,
                                   double xn, double yn, double zn) throws Throwable {
        double vanilla = (double) VANILLA_DISTANCE.invokeExact(seed, x, y, z, xn, yn, zn);
        double cached = cache.distance(seed, x, y, z, xn, yn, zn);
        double copy = BiomeFiddleCache.fiddledDistance(seed, x, y, z, xn, yn, zn);
        check(Double.doubleToRawLongBits(vanilla) == Double.doubleToRawLongBits(cached),
                "cached distance mismatch at " + x + "," + y + "," + z);
        check(Double.doubleToRawLongBits(vanilla) == Double.doubleToRawLongBits(copy),
                "copied distance mismatch at " + x + "," + y + "," + z);
        distances++;
        return cached;
    }

    private static BiomeManager manager(long seed, int[] quart) {
        return new BiomeManager((x, y, z) -> {
            quart[0] = x;
            quart[1] = y;
            quart[2] = z;
            return null;
        }, seed);
    }

    /** Surface-shaped loop: vanilla's 8 distances per block versus the cached ones. */
    private static void bench(Random random) throws Throwable {
        BiomeFiddleCache cache = BiomeFiddleCache.local();
        long seed = BiomeManager.obfuscateSeed(8675309L);
        for (int round = 0; round < 6; round++) {
            double sink = 0;
            long a = System.nanoTime();
            int n = 0;
            for (int chunk = 0; chunk < 40; chunk++) {
                int cx = chunk << 4;
                for (int x = cx; x < cx + 16; x++) for (int z = 0; z < 16; z++) for (int y = 100; y > 40; y--) {
                    int bx = x - 2, by = y - 2, bz = z - 2;
                    for (int c = 0; c < 8; c++) {
                        sink += (double) VANILLA_DISTANCE.invokeExact(seed, (bx >> 2) + (c >> 2), (by >> 2) + ((c >> 1) & 1),
                                (bz >> 2) + (c & 1), (bx & 3) / 4.0 - (c >> 2), (by & 3) / 4.0 - ((c >> 1) & 1), (bz & 3) / 4.0 - (c & 1));
                    }
                    n++;
                }
            }
            long b = System.nanoTime();
            for (int chunk = 0; chunk < 40; chunk++) {
                int cx = chunk << 4;
                for (int x = cx; x < cx + 16; x++) for (int z = 0; z < 16; z++) for (int y = 100; y > 40; y--) {
                    int bx = x - 2, by = y - 2, bz = z - 2;
                    for (int c = 0; c < 8; c++) {
                        sink -= cache.distance(seed, (bx >> 2) + (c >> 2), (by >> 2) + ((c >> 1) & 1),
                                (bz >> 2) + (c & 1), (bx & 3) / 4.0 - (c >> 2), (by & 3) / 4.0 - ((c >> 1) & 1), (bz & 3) / 4.0 - (c & 1));
                    }
                }
            }
            long c = System.nanoTime();
            long corners = 0;
            for (int chunk = 0; chunk < 40; chunk++) {
                int cx = chunk << 4;
                for (int x = cx; x < cx + 16; x++) for (int z = 0; z < 16; z++) for (int y = 100; y > 40; y--) {
                    corners += cache.nearestCorner(seed, x, y, z);
                }
            }
            long e = System.nanoTime();
            System.out.printf("round %d: vanilla %.1f ns/block, per-corner %.1f ns/block, cell path %.1f ns/block "
                            + "(%.2fx / %.2fx) sink=%s/%d%n", round, (b - a) / (double) n, (c - b) / (double) n,
                    (e - c) / (double) n, (b - a) / (double) (c - b), (b - a) / (double) (e - c), sink, corners);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
