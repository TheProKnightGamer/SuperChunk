package dev.superchunk.worldgen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Random;
import net.minecraft.util.Mth;

/**
 * Standalone differential checks for {@link OreRuns}: each row's run against a block-by-block scan, and the
 * whole of {@code doPlace}'s fast loops (as {@code MixinOreFeatureFast} runs them) against vanilla's loops.
 */
public final class OreRunsTest {

    public static void main(String[] args) {
        Random random = new Random(0x0be5L);
        long rows = 0;

        // random rows, and rows built so the sphere's surface passes within a few ulps of a block centre
        for (int trial = 0; trial < 4_000_000; trial++) {
            double r = 0.5 + random.nextDouble() * 5.5;
            double s = switch (trial % 4) {
                case 0 -> random.nextDouble();
                case 1 -> 1.0 - Math.ulp(1.0) * (1 + random.nextInt(1000));
                case 2 -> 0.0;
                default -> random.nextDouble() * random.nextDouble();
            };
            double c;
            if ((trial & 1) == 0) {
                c = random.nextInt(4000) - 2000 + random.nextDouble();
                if (trial % 10 == 0) {
                    c = Math.floor(c) + (random.nextBoolean() ? 0.5 : 0.0);
                }
            } else {
                int end = random.nextInt(4000) - 2000;
                c = end + 0.5 - r * Math.sqrt(1.0 - s) * (random.nextBoolean() ? 1 : -1);
                for (int n = random.nextInt(9) - 4; n != 0; n += n > 0 ? -1 : 1) {
                    c = n > 0 ? Math.nextUp(c) : Math.nextDown(c);
                }
            }
            int min = Mth.floor(c - r) - random.nextInt(3) + (random.nextInt(4) == 0 ? random.nextInt(5) : 0);
            int max = Mth.floor(c + r) + random.nextInt(3) - (random.nextInt(4) == 0 ? random.nextInt(5) : 0);
            long run = OreRuns.run(s, c, r, 1.0 / r, min, max);
            long scan = scan(s, c, r, min, max);
            check(sameRun(run, scan), "run s=" + s + " c=" + c + " r=" + r + " [" + min + "," + max + "]: "
                    + str(run) + " vs " + str(scan));
            rows++;
        }

        // the product-first test answers exactly as the division does
        for (int trial = 0; trial < 20_000_000; trial++) {
            double r = 0.5 + random.nextDouble() * 5.5;
            double s = random.nextInt(3) == 0 ? 0.0 : random.nextDouble();
            int k = random.nextInt(4000) - 2000;
            double c = random.nextBoolean()
                    ? k + random.nextDouble() * 14 - 7
                    : k + 0.5 - r * Math.sqrt(1.0 - s) * (random.nextBoolean() ? 1 : -1);
            for (int n = random.nextInt(9) - 4; n != 0; n += n > 0 ? -1 : 1) {
                c = n > 0 ? Math.nextUp(c) : Math.nextDown(c);
            }
            check(OreRuns.insideFast(s, k, c, r, 1.0 / r) == OreRuns.inside(s, k, c, r),
                    "insideFast s=" + s + " k=" + k + " c=" + c + " r=" + r);
        }

        // the whole loop: spheres as vanilla's place and doPlace draw them
        long ores = 0;
        long blocks = 0;
        for (int trial = 0; trial < 300_000; trial++) {
            int size = trial % 5 == 0 ? 1 + random.nextInt(64) : new int[]{4, 7, 8, 9, 10, 12, 17, 20, 33, 64}[random.nextInt(10)];
            int bx = random.nextInt(2000) - 1000;
            int by = random.nextInt(400) - 80;
            int bz = random.nextInt(2000) - 1000;
            float f = random.nextFloat() * (float) Math.PI;
            float f1 = (float) size / 8.0F;
            int i = Mth.ceil(((float) size / 16.0F * 2.0F + 1.0F) / 2.0F);
            double d0 = (double) bx + Math.sin((double) f) * (double) f1;
            double d1 = (double) bx - Math.sin((double) f) * (double) f1;
            double d2 = (double) bz + Math.cos((double) f) * (double) f1;
            double d3 = (double) bz - Math.cos((double) f) * (double) f1;
            double d4 = (double) (by + random.nextInt(3) - 2);
            double d5 = (double) (by + random.nextInt(3) - 2);
            int x = bx - Mth.ceil(f1) - i;
            int y = by - 2 - i;
            int z = bz - Mth.ceil(f1) - i;
            int width = 2 * (Mth.ceil(f1) + i);
            int height = 2 * (2 + i);
            double[] spheres = spheres(random, size, d0, d1, d4, d5, d2, d3);
            check(OreRuns.spheresFit(spheres, size, x, y, z, width, height), "vanilla's spheres must fit: size " + size);
            IntArrayList expected = OreRuns.vanillaBlocks(spheres, size, x, y, z, width, height, -64, 320);
            IntArrayList actual = fastBlocks(spheres, size, x, y, z, width, height, -64, 320);
            check(expected.equals(actual), "ore size " + size + " at " + bx + "," + by + "," + bz + ": "
                    + actual.size() / 3 + " blocks vs vanilla " + expected.size() / 3);
            checkInside(actual, x, y, z, width, height);
            ores++;
            blocks += expected.size() / 3;
        }

        // spheres that leave the box, or are not finite, take vanilla's loop
        check(!OreRuns.spheresFit(new double[]{3.5, 0, 0, 1.0}, 1, -1, -1, -1, 4, 4), "a sphere past the box must not fit");
        check(OreRuns.spheresFit(new double[]{1.5, 1.5, 1.5, 1.0, 9, 9, 9, -1.0}, 2, 0, 0, 0, 4, 4), "a dropped sphere is ignored");
        check(!OreRuns.spheresFit(new double[]{Double.NaN, 0, 0, 1.0}, 1, -10, -10, -10, 20, 20), "NaN must not fit");
        check(!OreRuns.spheresFit(new double[]{0, 0, 0, Double.POSITIVE_INFINITY}, 1, -10, -10, -10, 20, 20), "infinity must not fit");

        System.out.println("OreRunsTest: " + rows + " rows, " + ores + " ores (" + blocks + " blocks) OK");
    }

    private static double[] spheres(Random random, int j, double minX, double maxX, double minY, double maxY,
                                    double minZ, double maxZ) {
        double[] adouble = new double[j * 4];
        for (int k = 0; k < j; k++) {
            float f = (float) k / (float) j;
            adouble[k * 4 + 0] = Mth.lerp((double) f, minX, maxX);
            adouble[k * 4 + 1] = Mth.lerp((double) f, minY, maxY);
            adouble[k * 4 + 2] = Mth.lerp((double) f, minZ, maxZ);
            double d3 = random.nextDouble() * (double) j / 16.0;
            adouble[k * 4 + 3] = ((double) (Mth.sin((float) Math.PI * f) + 1.0F) * d3 + 1.0) / 2.0;
        }
        for (int l3 = 0; l3 < j - 1; l3++) {
            if (!(adouble[l3 * 4 + 3] <= 0.0)) {
                for (int i4 = l3 + 1; i4 < j; i4++) {
                    if (!(adouble[i4 * 4 + 3] <= 0.0)) {
                        double d8 = adouble[l3 * 4 + 0] - adouble[i4 * 4 + 0];
                        double d10 = adouble[l3 * 4 + 1] - adouble[i4 * 4 + 1];
                        double d12 = adouble[l3 * 4 + 2] - adouble[i4 * 4 + 2];
                        double d14 = adouble[l3 * 4 + 3] - adouble[i4 * 4 + 3];
                        if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12) {
                            if (d14 > 0.0) {
                                adouble[i4 * 4 + 3] = -1.0;
                            } else {
                                adouble[l3 * 4 + 3] = -1.0;
                            }
                        }
                    }
                }
            }
        }
        return adouble;
    }

    // MixinOreFeatureFast.superchunk$doPlaceRuns's loops, handing on each block instead of placing it
    private static IntArrayList fastBlocks(double[] adouble, int j, int x, int y, int z, int width, int height,
                                           int minBuild, int maxBuildExcl) {
        IntArrayList out = new IntArrayList();
        long[] taken = OreRuns.taken(width * height * width);
        for (int j4 = 0; j4 < j; j4++) {
            double d9 = adouble[j4 * 4 + 3];
            if (!(d9 < 0.0)) {
                double d11 = adouble[j4 * 4 + 0];
                double d13 = adouble[j4 * 4 + 1];
                double d15 = adouble[j4 * 4 + 2];
                int k4 = Math.max(Mth.floor(d11 - d9), x);
                int l = Math.max(Mth.floor(d13 - d9), y);
                int i1 = Math.max(Mth.floor(d15 - d9), z);
                int j1 = Math.max(Mth.floor(d11 + d9), k4);
                int k1 = Math.max(Mth.floor(d13 + d9), l);
                int l1 = Math.max(Mth.floor(d15 + d9), i1);
                int yLo = Math.max(l, minBuild);
                int yHi = Math.min(k1, maxBuildExcl - 1);
                double inv9 = 1.0 / d9;
                for (int i2 = k4; i2 <= j1; i2++) {
                    double d5 = ((double) i2 + 0.5 - d11) / d9;
                    if (d5 * d5 < 1.0) {
                        long ys = OreRuns.run(d5 * d5, d13, d9, inv9, yLo, yHi);
                        for (int j2 = OreRuns.lo(ys), yEnd = OreRuns.hi(ys); j2 <= yEnd; j2++) {
                            double d6 = ((double) j2 + 0.5 - d13) / d9;
                            long zs = OreRuns.run(d5 * d5 + d6 * d6, d15, d9, inv9, i1, l1);
                            int zLo = OreRuns.lo(zs);
                            int zHi = OreRuns.hi(zs);
                            if (zLo > zHi) {
                                continue;
                            }
                            int base = width * (i2 - x + width * (j2 - y)) - z;
                            int from = base + zLo;
                            int last = base + zHi;
                            for (int w = from >>> 6; w <= last >>> 6; w++) {
                                long run = -1L;
                                if (w == from >>> 6) {
                                    run &= -1L << from;
                                }
                                if (w == last >>> 6) {
                                    run &= -1L >>> ~last;
                                }
                                long fresh = run & ~taken[w];
                                taken[w] |= run;
                                for (; fresh != 0L; fresh &= fresh - 1L) {
                                    out.add(i2);
                                    out.add(j2);
                                    out.add((w << 6) + Long.numberOfTrailingZeros(fresh) - base);
                                }
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private static long scan(double s, double c, double r, int min, int max) {
        int lo = max + 1;
        int hi = max;
        boolean seenOut = false;
        boolean seenIn = false;
        for (int k = min; k <= max; k++) {
            if (OreRuns.inside(s, k, c, r)) {
                check(!(seenIn && seenOut), "the test must hold on one interval: s=" + s + " c=" + c + " r=" + r);
                if (!seenIn) {
                    lo = k;
                }
                seenIn = true;
                seenOut = false;
                hi = k;
            } else {
                seenOut = seenIn || seenOut;
            }
        }
        return OreRuns.pack(lo, hi);
    }

    private static boolean sameRun(long a, long b) {
        boolean emptyA = OreRuns.lo(a) > OreRuns.hi(a);
        boolean emptyB = OreRuns.lo(b) > OreRuns.hi(b);
        return emptyA ? emptyB : a == b;
    }

    private static String str(long run) {
        return OreRuns.lo(run) > OreRuns.hi(run) ? "empty" : "[" + OreRuns.lo(run) + "," + OreRuns.hi(run) + "]";
    }

    private static void checkInside(IntArrayList blocks, int x, int y, int z, int width, int height) {
        for (int n = 0; n < blocks.size(); n += 3) {
            check(blocks.getInt(n) >= x && blocks.getInt(n) < x + width
                    && blocks.getInt(n + 1) >= y && blocks.getInt(n + 1) < y + height
                    && blocks.getInt(n + 2) >= z && blocks.getInt(n + 2) < z + width, "block outside the box");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
