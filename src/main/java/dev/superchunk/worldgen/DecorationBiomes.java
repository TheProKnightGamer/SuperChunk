package dev.superchunk.worldgen;

import dev.superchunk.MixinTargetScan;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The biome check of a feature every biome around the chunk has.
 *
 * <p>Each position a top-level feature is tried at goes through {@code BiomeFilter}: look up the
 * biome at that block (the nearest of 8 jittered cell corners), then ask that biome's settings
 * whether they list the feature. A chunk tries about 300 ore positions alone, and ores are in
 * every overworld biome.
 *
 * <p>{@code applyBiomeDecoration} already collects the biomes of every section of the 3x3 chunks
 * around the chunk it decorates. When each of them lists the feature, the filter's answer is yes
 * for any position whose 8 candidate corners lie in those chunks, whichever corner wins: the biome
 * the lookup would return is one of the collected ones. So the lookup is not made; the filter is
 * handed one of the collected biomes and runs on as before.
 *
 * <p>Conditions, each checked: vanilla removes biomes its biome source does not list from the
 * collected set, so the shortcut is only used when it removed none (the set is then all of them);
 * the level is a {@code WorldGenRegion}; the position's corners are inside the 3x3 chunks; and no
 * other mod mixes into {@code BiomeFilter}.
 *
 * <p>Kill switch {@code -Dsuperchunk.worldgen.decorationBiomes=false}. Verify
 * {@code -Dsuperchunk.worldgen.decorationBiomes.verify=true} makes the lookup as well and checks
 * that its biome is one of the collected ones.
 */
public final class DecorationBiomes {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.decorationBiomes", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.decorationBiomes.verify");

    private static final AtomicLong CHECKED = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static volatile byte hooks;

    private DecorationBiomes() {
    }

    /** One remembered {@code hasFeature} answer; final fields, so a racing reader never sees half of it. */
    public static final class Answer {
        public final Object feature;
        public final boolean has;

        public Answer(Object feature, boolean has) {
            this.feature = feature;
            this.has = has;
        }
    }

    /** Implemented on {@code WorldGenRegion}: the decoration in progress. */
    public interface Region {
        /** Start of a chunk's decoration: its position and the collected biomes, or null when the set is not complete. */
        void superchunk$decorating(int chunkX, int chunkZ, Set<Holder<Biome>> collected);

        /** The complete collected set of the decoration in progress, or null. */
        Set<Holder<Biome>> superchunk$collectedBiomes();

        /** The feature being placed, when every collected biome lists it, with one of those biomes; or nulls. */
        void superchunk$everywhere(PlacedFeature feature, Holder<Biome> anyBiome);

        /**
         * One of the collected biomes when {@code feature} is the feature every collected biome
         * lists and block (x, z)'s biome corners lie in the decorated 3x3 chunks; else null.
         */
        Holder<Biome> superchunk$everywhereBiome(PlacedFeature feature, int x, int z);
    }

    /** False while another mod mixes into {@code BiomeFilter}; its code may want the real biome. */
    public static boolean unhooked() {
        byte s = hooks;
        if (s == ForeignHooks.UNKNOWN) {
            String foreign = MixinTargetScan.foreignMixin("net.minecraft.world.level.levelgen.placement.BiomeFilter");
            if (foreign != null && foreign.startsWith("<")) {
                return false; // not loaded yet: ask again
            }
            s = foreign == null ? ForeignHooks.CLEAR : ForeignHooks.HOOKED;
            if (foreign != null) {
                org.slf4j.LoggerFactory.getLogger("SuperChunk-Compat").info(
                        "[SuperChunk] The decoration biome shortcut is off: BiomeFilter is also changed by {}.", foreign);
            }
            hooks = s;
        }
        return s == ForeignHooks.CLEAR;
    }

    /** Verify mode: the biome the lookup really returns must be a collected one. */
    public static void verify(Set<Holder<Biome>> collected, Holder<Biome> actual, int x, int y, int z) {
        long n = CHECKED.incrementAndGet();
        if (collected == null || !collected.contains(actual)) {
            if (MISMATCHES.incrementAndGet() <= 20) {
                org.slf4j.LoggerFactory.getLogger("SuperChunk-Worldgen").error(
                        "[decoration-biomes] VERIFY MISMATCH at {} {} {}: {} is not among the collected biomes", x, y, z, actual);
            }
        }
        if ((n & 0xFFFFF) == 0) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Worldgen").info(
                    "[decoration-biomes] VERIFY: lookups skipped and checked={} MISMATCHES={}", n, MISMATCHES.get());
        }
    }
}
