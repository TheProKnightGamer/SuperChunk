package dev.superchunk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Server-side chunk-loading throttle lifts. FORGE 1.20.1 REALITY: the neo build's
 * PlayerChunkSender levers (batch-size unlock, sendAtChunkSending) target 1.20.2+ machinery
 * that does not exist here — on this port only the notickvd view-distance cap floor survives
 * (see portplans/vmp-notickvd-player.md drops). The toggle + boot line remain so configs stay
 * portable across both builds; the boot line states exactly which levers are live.
 */
public final class FullSpeedLoading {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-FullSpeed");

    private static final String KEY_ENABLED = "player.fullSpeedLoading";
    private static final String KEY_CHUNKS_PER_TICK = "player.fullSpeedLoading.chunksPerTick";
    private static final String KEY_MAX_UNACKED = "player.fullSpeedLoading.maxUnackedBatches";
    private static final String KEY_NOTICKVD_LOADS = "player.fullSpeedLoading.noTickVdMaxConcurrentLoads";

    /** Master toggle; false (default) = shipped behavior unchanged. */
    public static final boolean ENABLED;

    /**
     * Pinned {@code PlayerChunkSender.desiredChunksPerTick} while ON (default 64 = vanilla
     * {@code MAX_CHUNKS_PER_TICK}, the most a vanilla client would ever request). Values above 64
     * are allowed (clamped to [1, 512]) for LAN/loopback setups; the client copes, it just
     * decodes bigger batches.
     */
    public static final float CHUNKS_PER_TICK;

    /**
     * In-flight (unacknowledged) batch window while ON, raise-only over vanilla's 10. The window
     * is genuine client backpressure — it is what stops the server from burying a slow client —
     * so it can be widened but never removed, and never lowered below vanilla.
     */
    public static final int MAX_UNACKED_BATCHES;

    /**
     * Floor for C2ME notickvd's {@code maxConcurrentChunkLoads} while ON (raise-only: the
     * effective cap is {@code max(c2meConfigured, this)}). Bounded (clamped to [1, 65536]) rather
     * than unlimited so a 32-view-distance spiral cannot flood the chunk-system scheduler with
     * tens of thousands of in-flight loads at once.
     */
    public static final int NOTICKVD_MAX_CONCURRENT_LOADS;

    static {
        boolean enabled = false;
        int chunksPerTick = 64;
        int maxUnacked = 10;
        int noTickVdLoads = 128;
        try {
            Properties p = SuperChunkConfig.get();
            enabled = parseBoolean(p, KEY_ENABLED, false);
            chunksPerTick = parseIntClamped(p, KEY_CHUNKS_PER_TICK, 64, 1, 512);
            maxUnacked = parseIntClamped(p, KEY_MAX_UNACKED, 10, 1, 1000);
            noTickVdLoads = parseIntClamped(p, KEY_NOTICKVD_LOADS, 128, 1, 65536);
        } catch (Throwable t) {
            // Never let config trouble take down class init (would poison every hook site with
            // NoClassDefFoundError). Fall back to defaults == toggle OFF == vanilla behavior.
            LOGGER.warn("[SuperChunk-FullSpeed] Failed to read player.fullSpeedLoading settings — toggle stays OFF.", t);
        }
        ENABLED = enabled;
        CHUNKS_PER_TICK = chunksPerTick;
        MAX_UNACKED_BATCHES = maxUnacked;
        NOTICKVD_MAX_CONCURRENT_LOADS = noTickVdLoads;
    }

    private FullSpeedLoading() {
    }

    /**
     * Logs ONE boot line stating exactly which server-side throttles are lifted; called from
     * {@code SuperChunkMixinPlugin.onLoad()} right after the unified config's write-through, so
     * the line always appears at boot when the toggle is ON (and never when OFF).
     */
    public static void logBootLine() {
        if (ENABLED) {
            // 1.20.1 HONESTY (vmp-notickvd-player.md §5.1): PlayerChunkSender does not exist on
            // 1.20.1 — chunk sends are immediate and unthrottled (no batches, no acks) — so
            // levers (1) chunksPerTick and (2) maxUnackedBatches are INHERENTLY SATISFIED and
            // parsed as documented no-ops (the unified config stays cross-version compatible).
            // The only real lever on this port is (3), the notickvd concurrent-load floor.
            LOGGER.info("[SuperChunk-FullSpeed] player.fullSpeedLoading=true — on 1.20.1 the only server-side "
                            + "chunk-loading throttle is C2ME notickvd's concurrent-load cap: floor raised to {} "
                            + "(C2ME default: parallelism+1; raise-only). chunksPerTick={} and maxUnackedBatches={} are "
                            + "parsed but INERT on 1.20.1 — there is no PlayerChunkSender (no per-tick send budget, no "
                            + "batch/ack window): vanilla 1.20.1 sends are already immediate and unthrottled. "
                            + "Server-side only: a slow client still decodes at its own pace.",
                    NOTICKVD_MAX_CONCURRENT_LOADS, (int) CHUNKS_PER_TICK, MAX_UNACKED_BATCHES);
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
            LOGGER.warn("[SuperChunk-FullSpeed] {}='{}' is not an integer — using default {}.", key, v, fallback);
            return fallback;
        }
        int clamped = Math.max(min, Math.min(max, parsed));
        if (clamped != parsed) {
            LOGGER.warn("[SuperChunk-FullSpeed] {}={} out of range [{}, {}] — clamped to {}.", key, parsed, min, max, clamped);
        }
        return clamped;
    }
}
