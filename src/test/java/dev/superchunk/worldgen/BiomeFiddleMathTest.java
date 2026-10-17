package dev.superchunk.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.world.level.biome.BiomeManager;

/** Differential checks against the unmodified Minecraft biome selector. */
public final class BiomeFiddleMathTest {
    private static final MethodHandle VANILLA_DISTANCE;

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
        Random random = new Random(0xb10_4eL);
        for (long value : new long[] {Long.MIN_VALUE, Long.MIN_VALUE + 1, -1025, -1024, -1023,
                -1, 0, 1, 1023, 1024, 1025, Long.MAX_VALUE}) {
            check(BiomeFiddleMath.floorMod1024(value) == Math.floorMod(value, 1024), "modulus boundary");
        }
        for (int i = 0; i < 1_000_000; i++) {
            long value = random.nextLong();
            check(BiomeFiddleMath.floorMod1024(value) == Math.floorMod(value, 1024), "modulus parity");
        }
        int[] actual = new int[3];
        int cases = 0;
        int[] boundaries = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 2,
                -30_000_000, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 30_000_000,
                Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (long seed : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 8675309}) {
            BiomeManager manager = manager(seed, actual);
            for (int x : boundaries) {
                for (int y : boundaries) {
                    for (int z : boundaries) {
                        compare(manager, seed, x, y, z, actual);
                        cases++;
                    }
                }
            }
        }
        // Every fractional cell position for many unrelated seeds and coordinates.
        for (int i = 0; i < 8_000; i++) {
            long seed = random.nextLong();
            BiomeManager manager = manager(seed, actual);
            int x = random.nextInt() & ~3;
            int y = random.nextInt() & ~3;
            int z = random.nextInt() & ~3;
            for (int dx = 0; dx < 4; dx++) {
                for (int dy = 0; dy < 4; dy++) {
                    for (int dz = 0; dz < 4; dz++) {
                        compare(manager, seed, x + dx, y + dy, z + dz, actual);
                        cases++;
                    }
                }
            }
        }
        System.out.println("BiomeFiddleMath: 1000000 modulus checks, " + cases
                + " vanilla nearest-cell comparisons and " + cases * 8 + " bit-exact distances passed");
    }

    private static BiomeManager manager(long seed, int[] actual) {
        return new BiomeManager((x, y, z) -> {
            actual[0] = x;
            actual[1] = y;
            actual[2] = z;
            return null;
        }, seed);
    }

    private static void compare(BiomeManager manager, long seed, int x, int y, int z,
                                int[] actual) throws Throwable {
        manager.getBiome(new BlockPos(x, y, z));
        int bx = x - 2;
        int by = y - 2;
        int bz = z - 2;
        int qx = bx >> 2;
        int qy = by >> 2;
        int qz = bz >> 2;
        double fx = (bx & 3) / 4.0;
        double fy = (by & 3) / 4.0;
        double fz = (bz & 3) / 4.0;
        int winner = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            int cx = corner >> 2;
            int cy = (corner >> 1) & 1;
            int cz = corner & 1;
            double dx = fx - cx;
            double dy = fy - cy;
            double dz = fz - cz;
            double vanilla = (double) VANILLA_DISTANCE.invokeExact(seed, qx + cx, qy + cy, qz + cz, dx, dy, dz);
            double optimized = fastDistance(seed, qx + cx, qy + cy, qz + cz, dx, dy, dz);
            check(Double.doubleToRawLongBits(vanilla) == Double.doubleToRawLongBits(optimized), "distance mismatch");
            if (best > optimized) {
                best = optimized;
                winner = corner;
            }
        }
        check(actual[0] == qx + (winner >> 2) && actual[1] == qy + ((winner >> 1) & 1)
                && actual[2] == qz + (winner & 1), "nearest-cell mismatch");
    }

    /** Vanilla's distance computation with only its floor modulus replaced. */
    private static double fastDistance(long seed, int x, int y, int z, double dx, double dy, double dz) {
        long state = LinearCongruentialGenerator.next(seed, x);
        state = LinearCongruentialGenerator.next(state, y);
        state = LinearCongruentialGenerator.next(state, z);
        state = LinearCongruentialGenerator.next(state, x);
        state = LinearCongruentialGenerator.next(state, y);
        state = LinearCongruentialGenerator.next(state, z);
        double xFiddle = fastFiddle(state);
        state = LinearCongruentialGenerator.next(state, seed);
        double yFiddle = fastFiddle(state);
        state = LinearCongruentialGenerator.next(state, seed);
        double zFiddle = fastFiddle(state);
        double xx = dx + xFiddle;
        double yy = dy + yFiddle;
        double zz = dz + zFiddle;
        return zz * zz + yy * yy + xx * xx;
    }

    private static double fastFiddle(long seed) {
        return ((double) BiomeFiddleMath.floorMod1024(seed >> 24) / 1024.0 - 0.5) * 0.9;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
