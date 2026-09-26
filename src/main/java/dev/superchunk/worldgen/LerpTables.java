package dev.superchunk.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-cell interpolation tables for {@code NoiseChunk.NoiseInterpolator}'s in-cell reads
 * ({@code MixinChunkNoiseSamplerDensityInterpolator}).
 *
 * <p>Every block of a noise cell reads each interpolated density function as vanilla
 * {@code Mth.lerp3(tx, ty, tz, corners)} = {@code lerp(tz, lerp(ty, lerp(tx, c000, c100),
 * lerp(tx, c010, c110)), lerp(ty, lerp(tx, c001, c101), lerp(tx, c011, c111)))}: seven lerps and
 * three divisions per block, per interpolator. Within one cell the four x-lerps depend only on
 * the block's x, and the two y-lerps only on (x, y), so they are computed once per column / row
 * and reused; each block then does the final z-lerp. Same operands, same operations, same order:
 * the result is bit-identical (Java double arithmetic is strict IEEE). The profiled cost was
 * ~9% of worker CPU in {@code Mth.lerp} alone, the largest single self frame in the noise fill.
 *
 * <p>Kill switch {@code -Dsuperchunk.worldgen.lerpTables=false}. Verify
 * {@code -Dsuperchunk.worldgen.lerpTables.verify=true} computes both and returns vanilla's.
 */
public final class LerpTables {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.lerpTables", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.lerpTables.verify");

    private static final AtomicLong CHECKED = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();

    private LerpTables() {
    }

    /** Verify mode: records one table read against vanilla's lerp3; returns vanilla's value. */
    public static double verify(double table, double vanilla) {
        CHECKED.incrementAndGet();
        if (Double.doubleToRawLongBits(table) != Double.doubleToRawLongBits(vanilla)) {
            if (MISMATCHES.incrementAndGet() <= 16) {
                org.slf4j.LoggerFactory.getLogger("SuperChunk-LerpTables").warn(
                        "[lerp-tables] VERIFY MISMATCH: table={} vanilla={}", table, vanilla);
            }
        }
        return vanilla;
    }

    public static void reportVerify() {
        if (VERIFY) {
            long m = MISMATCHES.get();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-LerpTables").info(
                    "[lerp-tables] VERIFY: checked={} MISMATCHES={} -> {}", CHECKED.get(), m, m == 0 ? "PASS" : "FAIL");
        }
    }
}
