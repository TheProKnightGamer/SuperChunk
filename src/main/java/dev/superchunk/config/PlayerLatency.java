package dev.superchunk.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Runtime view of the {@code player.sendAtChunkSending} / {@code player.priorityBias} /
 * {@code player.latencyMetrics} keys of {@code superchunk.properties} — the
 * <b>player chunk-arrival latency</b> fixes and their proof metrics.
 *
 * <p><b>P1 — {@code player.sendAtChunkSending} (port-defect fix).</b>
 * {@code NewChunkHolderVanillaInterface} overrides {@code getTickingChunk()} to the C2ME
 * BLOCK_TICKING future but the 1.21.1 port never overrode vanilla
 * {@code ChunkHolder.getChunkToSend()} ({@code !sendSync.isDone() ? null : getTickingChunk()}).
 * Net effect: a chunk only becomes CLIENT-sendable once it reaches BLOCK_TICKING — behind the
 * PLAYER-ticket throttler, the 8-neighbor gate and an extra main-thread hop — even though the
 * chunk system has a dedicated earlier SERVER_ACCESSIBLE_CHUNK_SENDING status whose whole point
 * is sending (notickvd marks chunks pending-to-send there). Worse, with view-distance &gt;
 * simulation-distance the outer no-tick rings never reach BLOCK_TICKING, so their pending
 * chunks are re-filtered to null every tick and NEVER send through
 * {@code PlayerChunkSender.collectChunksToSend}. When ON, the holder exposes the chunk to the
 * sender as soon as (a) vanilla {@code sendSync} is done (light-send deps stay respected) and
 * (b) the FlowSched status is at least SERVER_ACCESSIBLE_CHUNK_SENDING.
 *
 * <p><b>P2 — {@code player.priorityBias} (player-proximity priority band).</b> Clones the
 * existing sync-load priority boost in {@code SchedulingManager}: chunk-system tasks within
 * (view distance + 8) chebyshev of any player's chunk get priority
 * {@code clamp(18 + distance, 18, 32)}, live-updated on player section crossings. Pure
 * reordering of the existing work queue — no budget, no throttle, no new work. Priorities
 * 15 (chunk-system control plane) / 16 (chunk IO saves) / 17 (lighting) stay ahead of the band.
 *
 * <p><b>Metrics — {@code player.latencyMetrics}.</b> One [player-latency] log line per second
 * each for: the collect-miss counter (pending chunks that {@code getChunkToSend} filtered to
 * null — P1's proof), the global work-queue depth by priority bucket (P2's proof), and the
 * send-latency percentiles (now minus the chunk's SERVER_ACCESSIBLE_CHUNK_SENDING completion
 * stamp).
 *
 * <p>All values are read ONCE from {@link SuperChunkConfig} (boot-time; no hot reload) and the
 * class never throws from static init — parse failures fall back to defaults. All three flags
 * default OFF, leaving shipped behavior byte-identical (every hook is a checked no-op on a
 * static-final flag).
 */
public final class PlayerLatency {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-PlayerLatency");

    private static final String KEY_SEND_AT_CHUNK_SENDING = "player.sendAtChunkSending";
    private static final String KEY_PRIORITY_BIAS = "player.priorityBias";
    private static final String KEY_LATENCY_METRICS = "player.latencyMetrics";

    /** P1: expose chunks to {@code PlayerChunkSender} at SERVER_ACCESSIBLE_CHUNK_SENDING. */
    public static final boolean SEND_AT_CHUNK_SENDING;

    /** P2: player-proximity priority band (18..32) in the chunk-system work queue. */
    public static final boolean PRIORITY_BIAS;

    /** Proof metrics: 1s [player-latency] log lines (collect-miss, queue-depth, send-latency). */
    public static final boolean LATENCY_METRICS;

    static {
        boolean sendAtChunkSending = true; // default ON (the port-defect fix); see SuperChunkConfig
        boolean priorityBias = false;
        boolean latencyMetrics = false;
        try {
            Properties p = SuperChunkConfig.get();
            sendAtChunkSending = parseBoolean(p, KEY_SEND_AT_CHUNK_SENDING);
            priorityBias = parseBoolean(p, KEY_PRIORITY_BIAS);
            latencyMetrics = parseBoolean(p, KEY_LATENCY_METRICS);
        } catch (Throwable t) {
            // Never let config trouble take down class init (would poison every hook site with
            // NoClassDefFoundError). Fall back to defaults: sendAtChunkSending ON (the fix),
            // priorityBias / latencyMetrics OFF.
            LOGGER.warn("[SuperChunk-PlayerLatency] Failed to read player latency settings — using defaults "
                    + "(sendAtChunkSending on, others off).", t);
        }
        SEND_AT_CHUNK_SENDING = sendAtChunkSending;
        PRIORITY_BIAS = priorityBias;
        LATENCY_METRICS = latencyMetrics;
    }

    private PlayerLatency() {
    }

    /**
     * Logs ONE boot line per enabled flag stating exactly what changes; called from
     * {@code SuperChunkMixinPlugin.onLoad()} right after {@link FullSpeedLoading#logBootLine()},
     * so the lines always appear at boot when a flag is ON (and never when OFF).
     */
    public static void logBootLine() {
        if (SEND_AT_CHUNK_SENDING) {
            LOGGER.info("[SuperChunk-PlayerLatency] player.sendAtChunkSending=true — chunks become client-sendable at "
                    + "SERVER_ACCESSIBLE_CHUNK_SENDING (vanilla sendSync/light deps still respected) instead of BLOCK_TICKING. "
                    + "Note: like upstream C2ME notickvd, this exposes non-postprocessed chunks to clients at this status "
                    + "(see chunkSystem.suppressGhostMushrooms / MC-276863).");
        }
        if (PRIORITY_BIAS) {
            LOGGER.info("[SuperChunk-PlayerLatency] player.priorityBias=true — chunk-system tasks within (view distance + 8) "
                    + "chebyshev of a player run in priority band 18..32 (floor 18 keeps control-plane 15 / saves 16 / light 17 "
                    + "ahead), live-updated on player section crossings. Pure reordering: no budget, no throttle, no new work.");
        }
        if (LATENCY_METRICS) {
            LOGGER.info("[SuperChunk-PlayerLatency] player.latencyMetrics=true — 1s [player-latency] lines: per-player "
                    + "collect-miss counter, global work-queue depth buckets {<=17, 18-32, 33, 34+}, and send-latency "
                    + "p50/p99 (now - CHUNK_SENDING completion).");
        }
    }

    private static boolean parseBoolean(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return false;
        }
        return Boolean.parseBoolean(v.trim());
    }
}
