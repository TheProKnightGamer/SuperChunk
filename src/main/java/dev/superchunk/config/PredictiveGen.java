package dev.superchunk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Properties;

/**
 * Runtime view of the {@code player.predictiveGen} section of {@code superchunk.properties} —
 * <b>predictive chunk generation along a fast-moving player's projected path</b>.
 *
 * <p><b>The idea.</b> When a player moves fast (elytra, boosted flight, fast vehicles), the
 * brute-force fix is raising the whole view radius — O(r²) extra chunks, almost all of them
 * behind or beside the player. Instead, SuperChunk projects the player's measured velocity
 * {@link #LOOKAHEAD_SECONDS} ahead and pre-loads only a narrow <i>corridor</i> of chunks along
 * the predicted path, feeding them into the EXISTING notickvd pipeline (same ticket level/type
 * pattern as {@code PlayerNoTickLoader}: FlowSched SERVER_ACCESSIBLE_CHUNK_SENDING target +
 * vanilla level-33 ticket) so they generate and send through the normal path. This complements —
 * and does not duplicate — the rest of the player-latency stack:
 * {@code player.sendAtChunkSending} (P1) makes corridor chunks client-sendable at CHUNK_SENDING,
 * {@code player.fullSpeedLoading} un-throttles the send, and {@code player.priorityBias} (P2)
 * additionally receives the predicted positions so in-pipeline work along the path outranks
 * background pregen.
 *
 * <p><b>Mechanics.</b> A per-player velocity tracker (EMA of the horizontal position delta per
 * tick, measured server-side from {@code ChunkMap.tick()} — NOT {@code getDeltaMovement}, which
 * is unreliable for elytra) stays idle below {@link #MIN_SPEED_BPS} (zero overhead when walking).
 * At or above the threshold, every {@link #RECOMPUTE_INTERVAL_TICKS} ticks the corridor from the
 * current position to the projected position is (re)computed on the notickvd scheduler thread:
 * width = min(no-tick view distance, {@link #CORRIDOR_WIDTH_CHUNKS}), nearest-first along the
 * path, at most {@link #MAX_PREDICTED_CHUNKS} chunks per player. Predicted loads run under their
 * OWN small in-flight budget ({@link #MAX_CONCURRENT_PREDICTED_LOADS}) so they never starve the
 * main {@code maxConcurrentChunkLoads} accounting of {@code PlayerNoTickLoader}.
 *
 * <p><b>Hygiene.</b> Tickets are ref-counted per chunk across players and retracted when the
 * player turns (corridor recompute drops out-of-corridor tickets), slows below the threshold,
 * disconnects, or changes dimension — mirroring how {@code PlayerNoTickLoader} removes
 * out-of-range tickets. A 1s {@code [predictive-gen]} log line reports predicted/ticketed
 * counters and the hit rate (a prediction is a HIT when the chunk enters the player's real
 * no-tick view within {@link #HIT_WINDOW_SECONDS}; misses = wasted generation — watch the rate).
 *
 * <p>All values are read ONCE from {@link SuperChunkConfig} (boot-time; no hot reload) and the
 * class never throws from static init — parse failures fall back to defaults. Defaults with the
 * master toggle OFF leave shipped behavior byte-identical (every hook is a checked no-op on a
 * static-final flag), and the predictor only ever activates for real connected players, so
 * pregen is entirely unaffected.
 */
public final class PredictiveGen {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-PredictiveGen");

    private static final String KEY_ENABLED = "player.predictiveGen";
    private static final String KEY_LOOKAHEAD_SECONDS = "player.predictiveGen.lookaheadSeconds";
    private static final String KEY_MAX_PREDICTED_CHUNKS = "player.predictiveGen.maxPredictedChunks";
    private static final String KEY_MIN_SPEED_BPS = "player.predictiveGen.minSpeedBps";
    private static final String KEY_MAX_CONCURRENT_PREDICTED_LOADS = "player.predictiveGen.maxConcurrentPredictedLoads";
    private static final String KEY_CORRIDOR_WIDTH_CHUNKS = "player.predictiveGen.corridorWidthChunks";
    private static final String KEY_RECOMPUTE_INTERVAL_TICKS = "player.predictiveGen.recomputeIntervalTicks";
    private static final String KEY_HIT_WINDOW_SECONDS = "player.predictiveGen.hitWindowSeconds";
    private static final String KEY_LOG_METRICS = "player.predictiveGen.logMetrics";

    /** Master toggle; false (default) = shipped behavior unchanged, no tracker ever allocates. */
    public static final boolean ENABLED;

    /** How far ahead (seconds) the position is projected along the measured velocity. */
    public static final double LOOKAHEAD_SECONDS;

    /** Hard cap on corridor chunks per player, nearest-first along the path. */
    public static final int MAX_PREDICTED_CHUNKS;

    /** Horizontal speed (blocks/second, EMA) below which the predictor is fully idle. */
    public static final double MIN_SPEED_BPS;

    /**
     * In-flight budget for predicted loads per world — a SEPARATE small budget so predictions
     * never consume {@code PlayerNoTickLoader}'s {@code maxConcurrentChunkLoads} accounting
     * (total in flight is bounded by the sum of the two budgets).
     */
    public static final int MAX_CONCURRENT_PREDICTED_LOADS;

    /** Corridor width in chunks; the effective width is min(no-tick view distance, this). */
    public static final int CORRIDOR_WIDTH_CHUNKS;

    /** How often (ticks) corridors are recomputed / retired and departed players swept. */
    public static final int RECOMPUTE_INTERVAL_TICKS;

    /** Hit window: a predicted chunk entering the player's real view within this many seconds is a HIT. */
    public static final double HIT_WINDOW_SECONDS;

    /** 1s [predictive-gen] proof-metric log line (only emitted while there is activity). */
    public static final boolean LOG_METRICS;

    static {
        boolean enabled = false;
        double lookaheadSeconds = 4.0;
        int maxPredictedChunks = 64;
        double minSpeedBps = 8.0;
        int maxConcurrentPredictedLoads = 8;
        int corridorWidthChunks = 5;
        int recomputeIntervalTicks = 10;
        double hitWindowSeconds = 10.0;
        boolean logMetrics = true;
        try {
            Properties p = SuperChunkConfig.get();
            enabled = parseBoolean(p, KEY_ENABLED, false);
            lookaheadSeconds = parseDoubleClamped(p, KEY_LOOKAHEAD_SECONDS, 4.0, 0.5, 30.0);
            maxPredictedChunks = parseIntClamped(p, KEY_MAX_PREDICTED_CHUNKS, 64, 1, 1024);
            minSpeedBps = parseDoubleClamped(p, KEY_MIN_SPEED_BPS, 8.0, 0.5, 500.0);
            maxConcurrentPredictedLoads = parseIntClamped(p, KEY_MAX_CONCURRENT_PREDICTED_LOADS, 8, 1, 256);
            corridorWidthChunks = parseIntClamped(p, KEY_CORRIDOR_WIDTH_CHUNKS, 5, 1, 15);
            recomputeIntervalTicks = parseIntClamped(p, KEY_RECOMPUTE_INTERVAL_TICKS, 10, 1, 100);
            hitWindowSeconds = parseDoubleClamped(p, KEY_HIT_WINDOW_SECONDS, 10.0, 1.0, 120.0);
            logMetrics = parseBoolean(p, KEY_LOG_METRICS, true);
        } catch (Throwable t) {
            // Never let config trouble take down class init (would poison every hook site with
            // NoClassDefFoundError). Fall back to defaults == toggle OFF == shipped behavior.
            LOGGER.warn("[SuperChunk-PredictiveGen] Failed to read player.predictiveGen settings — toggle stays OFF.", t);
        }
        ENABLED = enabled;
        LOOKAHEAD_SECONDS = lookaheadSeconds;
        MAX_PREDICTED_CHUNKS = maxPredictedChunks;
        MIN_SPEED_BPS = minSpeedBps;
        MAX_CONCURRENT_PREDICTED_LOADS = maxConcurrentPredictedLoads;
        CORRIDOR_WIDTH_CHUNKS = corridorWidthChunks;
        RECOMPUTE_INTERVAL_TICKS = recomputeIntervalTicks;
        HIT_WINDOW_SECONDS = hitWindowSeconds;
        LOG_METRICS = logMetrics;
    }

    private PredictiveGen() {
    }

    /**
     * Logs ONE boot line stating exactly what the predictor does; called from
     * {@code SuperChunkMixinPlugin.onLoad()} right after {@link PlayerLatency#logBootLine()},
     * so the line always appears at boot when the toggle is ON (and never when OFF).
     */
    public static void logBootLine() {
        if (ENABLED) {
            LOGGER.info("[SuperChunk-PredictiveGen] player.predictiveGen=true — fast-moving players (EMA horizontal speed >= "
                            + "{} blocks/s) get a predicted-path corridor pre-loaded: lookahead {}s, width min(viewDistance, {}) chunks, "
                            + "<= {} chunks/player nearest-first, recomputed every {} ticks. Corridor chunks ride the existing notickvd "
                            + "pipeline (FlowSched SERVER_ACCESSIBLE_CHUNK_SENDING + vanilla level-33 ticket, own ticket type "
                            + "'superchunk:predictive_gen') under a separate in-flight budget of {} loads/world; predicted positions also "
                            + "feed the player.priorityBias band when that flag is ON. Tickets retract on turn/stop/disconnect/dimension "
                            + "change; hit window {}s; 1s [predictive-gen] metrics {}.",
                    MIN_SPEED_BPS, LOOKAHEAD_SECONDS, CORRIDOR_WIDTH_CHUNKS, MAX_PREDICTED_CHUNKS, RECOMPUTE_INTERVAL_TICKS,
                    MAX_CONCURRENT_PREDICTED_LOADS, HIT_WINDOW_SECONDS, LOG_METRICS ? "enabled" : "disabled");
        }
    }

    private static boolean parseBoolean(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(v.trim());
    }

    private static int parseIntClamped(Properties p, String key, int fallback, int min, int max) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[SuperChunk-PredictiveGen] {}='{}' is not an integer — using default {}.", key, v, fallback);
            return fallback;
        }
        int clamped = Math.max(min, Math.min(max, parsed));
        if (clamped != parsed) {
            LOGGER.warn("[SuperChunk-PredictiveGen] {}={} out of range [{}, {}] — clamped to {}.", key, parsed, min, max, clamped);
        }
        return clamped;
    }

    private static double parseDoubleClamped(Properties p, String key, double fallback, double min, double max) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[SuperChunk-PredictiveGen] {}='{}' is not a number — using default {}.", key, v, fallback);
            return fallback;
        }
        if (Double.isNaN(parsed)) {
            LOGGER.warn("[SuperChunk-PredictiveGen] {}=NaN — using default {}.", key, fallback);
            return fallback;
        }
        double clamped = Math.max(min, Math.min(max, parsed));
        if (clamped != parsed) {
            LOGGER.warn(String.format(Locale.ROOT,
                    "[SuperChunk-PredictiveGen] %s=%s out of range [%s, %s] — clamped to %s.", key, parsed, min, max, clamped));
        }
        return clamped;
    }
}
