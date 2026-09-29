package dev.superchunk.worldgen;

import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Features that reach past the chunks world generation lets them touch
 * ({@code MixinFeatureOutsideRegion}, {@code MixinWorldGenRegionFarWrites}).
 *
 * <p>While a chunk is decorated, a feature may read the chunks within 8 of it and write within 1.
 * A feature configured to reach farther (JJThunder To The Max's large_dripstone searches 512
 * blocks and drifts with its wind) hits both limits:
 * <ul>
 * <li>A read past 8 chunks throws from {@code WorldGenRegion.getChunk} ("Requested chunk unavailable
 * during world generation"), and vanilla fails the whole chunk. The chunk then never exists, its
 * neighbours can never finish, and whatever needs them waits or fails. Instead, that one placed
 * feature is skipped in that chunk and the chunk generates. Nothing changes for any chunk vanilla
 * can generate. Kill switch {@code -Dsuperchunk.worldgen.skipOutOfRegionFeatures=false}.</li>
 * <li>A write past 1 chunk is dropped by {@code WorldGenRegion.ensureCanWrite}, which logs every
 * dropped block ("Detected setBlock in a far chunk"): 185,000 lines in half an hour of play with
 * that pack. The blocks are still dropped; the log gets the first one per feature and then a count
 * at most every {@link #INTERVAL_NANOS}. Vanilla's lines:
 * {@code -Dsuperchunk.worldgen.logEveryFarWrite=true}.</li>
 * </ul>
 */
public final class FeatureRegionReads {

    public static final boolean SKIP_ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.skipOutOfRegionFeatures", "true"));
    public static final boolean LOG_EVERY_FAR_WRITE = Boolean.getBoolean("superchunk.worldgen.logEveryFarWrite");

    /** WorldGenRegion.getChunk's message for a chunk outside the region. */
    private static final String OUTSIDE_REGION = "Requested chunk unavailable during world generation";
    private static final long INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Worldgen");

    private static final long FIRST = -1;
    private static final ConcurrentHashMap<String, Tally> SKIPS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Tally> FAR_WRITES = new ConcurrentHashMap<>();

    private FeatureRegionReads() {
    }

    /** Whether {@code t} is WorldGenRegion refusing a chunk outside the region (possibly wrapped). */
    public static boolean outsideRegion(Throwable t) {
        for (int depth = 0; t != null && depth < 16; depth++) {
            if (t instanceof IllegalStateException && OUTSIDE_REGION.equals(t.getMessage())) {
                return true;
            }
            Throwable next = t instanceof ReportedException reported ? reported.getReport().getException() : t.getCause();
            t = next == t ? null : next;
        }
        return false;
    }

    /** A placed feature was skipped in the chunk at {@code origin} because it read outside the region. */
    public static void skipped(WorldGenLevel level, PlacedFeature feature, BlockPos origin) {
        String name = level.registryAccess().registry(Registries.PLACED_FEATURE)
                .flatMap(registry -> registry.getResourceKey(feature))
                .map(key -> key.location().toString())
                .orElseGet(feature::toString);
        long count = SKIPS.computeIfAbsent(name, k -> new Tally()).record();
        if (count == FIRST) {
            LOGGER.warn("[SuperChunk] Feature {} read a chunk farther away than world generation allows, so it was "
                    + "skipped in chunk [{}, {}] (vanilla fails the whole chunk, which leaves a hole that nothing next "
                    + "to it can finish loading around). This comes from the datapack or mod that configures the "
                    + "feature. Further skips are counted here every 10 minutes.", name,
                    SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ()));
        } else if (count > 0) {
            LOGGER.warn("[SuperChunk] Feature {} was skipped {} more times for reading too far away.", name, count);
        }
    }

    /**
     * A block write outside the region, which WorldGenRegion drops; {@code message} is vanilla's line.
     * Returns whether vanilla's line should still be logged.
     */
    public static boolean farWrite(String feature, String message) {
        if (LOG_EVERY_FAR_WRITE) {
            return true;
        }
        long count = FAR_WRITES.computeIfAbsent(feature == null ? "" : feature, k -> new Tally()).record();
        if (count == FIRST) {
            LOGGER.warn("[SuperChunk] {} (dropped, as vanilla does: features may only change the chunks next to the "
                    + "one being decorated). Vanilla logs every such block; further ones from this feature are "
                    + "counted here every 10 minutes.", message);
        } else if (count > 0) {
            LOGGER.warn("[SuperChunk] {} more blocks written too far away were dropped{}.", count,
                    feature == null ? "" : ", from " + feature);
        }
        return false;
    }

    /** {@link #FIRST} for the first event, the events since the last line when one is due, else 0. */
    private static final class Tally {
        private final AtomicLong sinceLine = new AtomicLong();
        private volatile long lastLine;
        private volatile boolean logged;

        long record() {
            this.sinceLine.incrementAndGet();
            long now = System.nanoTime();
            if (this.logged && now - this.lastLine < INTERVAL_NANOS) {
                return 0;
            }
            synchronized (this) {
                if (!this.logged) {
                    this.sinceLine.set(0);
                    this.lastLine = now;
                    this.logged = true;
                    return FIRST;
                }
                if (now - this.lastLine >= INTERVAL_NANOS) {
                    this.lastLine = now;
                    return this.sinceLine.getAndSet(0);
                }
            }
            return 0;
        }
    }
}
