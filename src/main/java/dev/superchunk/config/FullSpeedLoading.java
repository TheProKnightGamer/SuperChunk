package dev.superchunk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Runtime view of the {@code player.fullSpeedLoading} section of {@code superchunk.properties}
 * — the <b>"no radius shrink" / full-throttle chunk loading</b> toggle.
 *
 * <p><b>The problem.</b> When a player flies fast, two SERVER-side self-throttles make the
 * effective loaded ring around them shrink until the player outruns loaded terrain:
 * <ol>
 *   <li><b>Vanilla {@code PlayerChunkSender} slow-ack shrink.</b> The client reports a
 *       "desired chunks per tick" with every batch ack (computed by the client-side
 *       {@code ChunkBatchSizeCalculator}); the server REPLACES its per-tick send budget with
 *       that value (clamped to [0.01, 64], NaN &rarr; 0.01). A client busy meshing terrain during
 *       fast flight reports tiny values, so the server sends ever-smaller batches — exactly when
 *       the most chunks are needed. Vanilla also resets the batch quota to 1 whenever all batches
 *       drain (slow-start). Lifted by {@code dev.superchunk.mixin.MixinPlayerChunkSenderFullSpeed}.</li>
 *   <li><b>C2ME notickvd concurrency cap.</b> The no-tick view-distance loader keeps at most
 *       {@code noTickViewDistance.maxConcurrentChunkLoads} (default: executor parallelism + 1)
 *       chunk loads in flight per world. With SuperChunk's GPU-augmented generation throughput
 *       this starves the pipeline: the ring refills a handful of chunks at a time no matter how
 *       fast generation is. Lifted (raise-only) by
 *       {@code dev.superchunk.com.ishland.c2me.notickvd.mixin.MixinPlayerNoTickLoaderFullSpeed}.</li>
 * </ol>
 *
 * <p><b>Audited but deliberately NOT lifted:</b> the "send-cancel on movement" path
 * (VMP area-map remove listener &rarr; {@code PlayerChunkSender.dropChunk}) only cancels pending
 * sends for chunks that actually LEFT the player's view — that is correct behavior, not a
 * throttle; disabling it would waste bandwidth on out-of-view chunks and desync the client's
 * chunk cache. Likewise the pre-first-ack window of 1 batch (join handshake warm-up) is kept.
 *
 * <p><b>HONESTY NOTE (client-side limits).</b> This toggle removes SERVER-side self-throttling
 * only. A slow client still decodes, meshes and acks batches at its own pace, and the vanilla
 * unacked-batch window (raise-only via {@link #MAX_UNACKED_BATCHES}) still provides backpressure
 * so a struggling client is never buried under an unbounded packet backlog.
 *
 * <p>All values are read ONCE from {@link SuperChunkConfig} (boot-time; no hot reload) and the
 * class never throws from static init — parse failures fall back to defaults. Defaults with the
 * master toggle OFF leave shipped behavior identical (every hook is a checked no-op).
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
            LOGGER.info("[SuperChunk-FullSpeed] player.fullSpeedLoading=true — server-side chunk-loading throttles lifted: "
                            + "(1) PlayerChunkSender per-tick send budget pinned to {} chunks/tick (client slow-ack shrink ignored, "
                            + "post-drain quota slow-start neutralized); "
                            + "(2) unacked-batch window = {} (vanilla 10, raise-only — still real client backpressure); "
                            + "(3) C2ME notickvd maxConcurrentChunkLoads floor = {} (C2ME default: parallelism+1). "
                            + "NOT lifted: out-of-view send-cancel on movement (correct behavior, not a throttle) and the pre-first-ack "
                            + "window of 1 batch (join warm-up). Server-side only: a slow client still decodes/acks at its own pace.",
                    CHUNKS_PER_TICK, MAX_UNACKED_BATCHES, NOTICKVD_MAX_CONCURRENT_LOADS);
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
