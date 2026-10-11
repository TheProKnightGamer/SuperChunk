package dev.superchunk.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/** Switches and verify counters for {@code MixinWorldGenRegionCenterChunk}. */
public final class RegionCenterChunk {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.regionCenterChunk", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.regionCenterChunk.verify");

    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();

    private RegionCenterChunk() {
    }

    public static void verify(Object center, Object actual, Object status) {
        CHECKS.incrementAndGet();
        if (actual != center && MISMATCHES.incrementAndGet() <= 16) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Region").warn(
                    "[region-center] VERIFY MISMATCH: center {} answered {} at status {}", center, actual, status);
        }
    }

    public static void report() {
        if (VERIFY) {
            long m = MISMATCHES.get();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Region").info(
                    "[region-center] VERIFY: checked={} MISMATCHES={} -> {}", CHECKS.get(), m, m == 0 ? "PASS" : "FAIL");
        }
    }
}
