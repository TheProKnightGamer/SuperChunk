package dev.superchunk.worldgen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-worker-thread memo of quart-resolution biome lookups.
 *
 * <p><b>Why.</b> A JFR profile of the current operating point (13,493 execution samples,
 * 2026-08-07) puts {@code PalettedContainer.get} at ~20% of all worldgen CPU, and <b>10.5% of the
 * total lands in one path</b>: {@code BiomeManager.getBiome → LevelReader.getNoiseBiome →
 * ProtoChunk.getNoiseBiome → PalettedContainer.get}. Vanilla's {@code SurfaceRules.Context}
 * memoizes the biome per BLOCK, so every surface block a biome-conditional rule tests — and every
 * feature-placement biome filter — re-walks region cache → chunk → section → palette → bit storage
 * for a value that is constant across each 4×4×4 quart cell.
 *
 * <p><b>Layout is the whole design.</b> The first version of this used five parallel arrays
 * (generation, x, y, z, value) and measured at the edge of noise: a hit touched FIVE cache lines,
 * which is not obviously cheaper than the pointer chase it replaces. This one is two arrays — a
 * packed {@code long} key and the value — so a hit is two lines.
 *
 * <p>The key packs the quart coordinates exactly (x and z 24 bits each, y 12 bits) plus a 4-bit
 * generation, and {@link #encode} returns {@link #UNCACHEABLE} for anything outside those ranges,
 * so a truncated coordinate can never be mistaken for a different one. 24 signed bits of quart is
 * ±33.5M blocks, past the maximum world border; 12 signed bits of quart is ±8,192 blocks, past any
 * legal world height.
 *
 * <p><b>Generations.</b> Each {@code BiomeManager} claims one, so it can only ever see entries it
 * filled itself. Four bits is enough because the table is wiped when the counter wraps: at ~2,500
 * distinct cells per manager against a 4,096-slot table, a manager's misses are compulsory
 * cold-start misses anyway (measured hit rate 88.1% over 420M lookups), and a 48 KB wipe every 16
 * managers is far cheaper than a per-manager clear.
 *
 * <p><b>Correctness.</b> Entries are never invalidated within a generation, so this is only sound
 * where {@code getNoiseBiome} is a pure function for the manager's lifetime. That is the worldgen
 * case: a {@link net.minecraft.server.level.WorldGenRegion}'s manager lives for one
 * chunk-generation step and every chunk it can see has already passed the {@code biomes} status.
 * It is NOT true of {@code ServerLevel}'s long-lived manager, which {@code /fillbiome} can rewrite
 * under, and it is deliberately NOT shared across managers — a cross-manager table would also have
 * to prove that no entry was ever filled from {@code getUncachedNoiseBiome}'s blender-free
 * fallback for a chunk another region would have resolved properly.
 */
public final class BiomeQuartCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-BiomeCache");

    /** Power of two: the slot is a masked hash, so no modulo and no probing. */
    private static final int SLOTS = 4096;
    private static final int MASK = SLOTS - 1;

    /** Returned by {@link #encode} for coordinates that do not fit the packed key. */
    public static final long UNCACHEABLE = Long.MIN_VALUE;

    private static final int GEN_BITS = 4;
    private static final int GEN_MASK = (1 << GEN_BITS) - 1;

    private static final ThreadLocal<BiomeQuartCache> LOCAL = ThreadLocal.withInitial(BiomeQuartCache::new);

    private static final boolean METRICS = Boolean.getBoolean("superchunk.worldgen.biomeQuartCache.metrics");
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final long REPORT_EVERY = 1L << 24;
    private static final AtomicInteger REPORTS = new AtomicInteger();

    /** Key 0 is unused: {@link #encode} always sets a generation of 1..15 in the high bits. */
    private final long[] key = new long[SLOTS];
    private final Object[] value = new Object[SLOTS];
    private int nextGen;

    private BiomeQuartCache() {
    }

    /** This worker thread's table (allocated once per thread). */
    public static BiomeQuartCache local() {
        return LOCAL.get();
    }

    /**
     * Claims a generation for one {@code BiomeManager}. Wiping on wrap is what lets the generation
     * live in four spare key bits instead of a fifth array.
     */
    public int claimGeneration() {
        nextGen = (nextGen + 1) & GEN_MASK;
        if (nextGen == 0) {
            Arrays.fill(key, 0L);
            Arrays.fill(value, null);
            nextGen = 1;
        }
        return nextGen;
    }

    /**
     * Packs {@code (generation, x, y, z)} into the table key, or {@link #UNCACHEABLE} when a
     * coordinate does not fit its field. {@code generation} is 1..15.
     */
    public static long encode(int generation, int x, int y, int z) {
        if (((x + 0x800000) >>> 24) != 0 || ((z + 0x800000) >>> 24) != 0 || ((y + 0x800) >>> 12) != 0) {
            return UNCACHEABLE;
        }
        return ((long) generation << 60)
                | ((long) (x & 0xFFFFFF) << 36)
                | ((long) (z & 0xFFFFFF) << 12)
                | (y & 0xFFF);
    }

    /** The value for {@code encoded}, or {@code null} on a miss. Callers must {@link #put}. */
    public Object get(long encoded) {
        int slot = slot(encoded);
        if (key[slot] == encoded) {
            if (METRICS) {
                HITS.increment();
            }
            return value[slot];
        }
        if (METRICS) {
            MISSES.increment();
            maybeReport();
        }
        return null;
    }

    public void put(long encoded, Object v) {
        int slot = slot(encoded);
        key[slot] = encoded;
        value[slot] = v;
    }

    /** Fibonacci-ish mix; the full key is compared on lookup, so this only has to spread. */
    private static int slot(long encoded) {
        long h = encoded * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 51) & MASK;
    }

    private static final LongAdder MISMATCHES = new LongAdder();

    /**
     * Verify-mode failure: a hit disagreed with the lookup it replaced. Logged loudly and counted;
     * the caller returns the FRESH value, so even a bug here degrades to "slow but correct".
     */
    public static void recordMismatch(int x, int y, int z, Object cached, Object fresh) {
        MISMATCHES.increment();
        if (MISMATCHES.sum() <= 20) {
            LOGGER.error("[biome-quart-cache] VERIFY MISMATCH at quart ({}, {}, {}): cached={} fresh={}",
                    x, y, z, cached, fresh);
        }
    }

    /** Verify-mode summary; 0 mismatches over a full pregen is the gate. */
    public static void reportVerify() {
        long mm = MISMATCHES.sum(), h = HITS.sum(), m = MISSES.sum();
        LOGGER.info("[biome-quart-cache] VERIFY: hits={} misses={} MISMATCHES={} -> {}",
                h, m, mm, mm == 0 ? "PASS" : "FAIL");
    }

    private static void maybeReport() {
        long m = MISSES.sum();
        if (m % REPORT_EVERY != 0) {
            return;
        }
        long h = HITS.sum(), total = h + m;
        if (total > 0 && REPORTS.incrementAndGet() < 40) {
            LOGGER.info("[biome-quart-cache] lookups={} hits={} ({}%) — {} slots/thread",
                    total, h, String.format("%.1f", 100.0 * h / total), SLOTS);
        }
    }
}
