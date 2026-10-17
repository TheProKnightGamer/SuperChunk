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
        // 1.20.1 DIVERGENCE from the neo tree (deliberate; the neo parity argument does not carry).
        // Neo flipped this fallback to `true` because neo's SuperChunkConfig defaults
        // player.sendAtChunkSending=true — there, the flag IS the port-defect fix. On 1.20.1 the
        // defect does not exist (no vanilla ChunkHolder.getChunkToSend filter) and the notickvd
        // port sends at FULL/CHUNK_SENDING unconditionally, so SuperChunkConfig deliberately keeps
        // def("player.sendAtChunkSending", "false") — the flag only adds an "is INERT" boot line.
        // The throw-path fallback MUST agree with that shipped default, otherwise a config read
        // failure would flip a documented-false flag on and log a boot line claiming a fix that is
        // already unconditional.
        boolean sendAtChunkSending = false;
        boolean priorityBias = false;
        boolean latencyMetrics = false;
        try {
            Properties p = SuperChunkConfig.get();
            sendAtChunkSending = parseBoolean(p, KEY_SEND_AT_CHUNK_SENDING);
            priorityBias = parseBoolean(p, KEY_PRIORITY_BIAS);
            latencyMetrics = parseBoolean(p, KEY_LATENCY_METRICS);
        } catch (Throwable t) {
            // Never let config trouble take down class init (would poison every hook site with
            // NoClassDefFoundError). Fall back to defaults == all flags OFF == shipped behavior
            // (on 1.20.1 all three keys ship false; see the divergence note above).
            LOGGER.warn("[SuperChunk-PlayerLatency] Failed to read player latency settings — all flags stay OFF.", t);
        }
        SEND_AT_CHUNK_SENDING = sendAtChunkSending;
        PRIORITY_BIAS = priorityBias;
        LATENCY_METRICS = latencyMetrics && dev.superchunk.diag.LogQuieter.verbose(); // metrics are log-only
    }

    private PlayerLatency() {
    }

    /**
     * Logs ONE boot line per enabled flag stating exactly what changes; called from
     * {@code SuperChunkMixinPlugin.onLoad()} right after {@link FullSpeedLoading#logBootLine()},
     * so the lines always appear at boot when a flag is ON (and never when OFF).
     */
    public static void logBootLine() {
        // 1.20.1 HONESTY (vmp-notickvd-player.md §5.2). Per-flag status on this port:
        //  - sendAtChunkSending: INERT AS A FLAG — the defect it fixed is structural on 1.20.1
        //    (updateChunkTracking requires getTickingChunk()), and the notickvd port fixes it
        //    UNCONDITIONALLY (getTickingChunk→getFullChunkFuture redirect + send-at-accessible /
        //    CHUNK_SENDING send). Same behavior, always on with notickvd, no separate flag.
        //  - priorityBias: ACTIVE (the original plan's drop was REVERSED after Wave C ported the
        //    c2me chunk system + SchedulingManager the band lives in).
        //  - latencyMetrics: INERT — all three metrics need APIs absent on 1.20.1 (PlayerChunkSender
        //    getChunkToSend misses / send-latency stamps collapse on the synchronous send path).
        // Never claim a lifted throttle that doesn't exist.
        if (SEND_AT_CHUNK_SENDING) {
            LOGGER.info("[SuperChunk-PlayerLatency] player.sendAtChunkSending=true is INERT on 1.20.1: chunks always "
                    + "become client-sendable at FULL/CHUNK_SENDING with the notickvd module (built into the port "
                    + "unconditionally — there is no vanilla getChunkToSend filter to fix on this version). "
                    + "Note: like upstream C2ME notickvd, non-postprocessed chunks are exposed to clients at this status "
                    + "(see chunkSystem.suppressGhostMushrooms / MC-276863).");
        }
        if (PRIORITY_BIAS) {
            LOGGER.info("[SuperChunk-PlayerLatency] player.priorityBias=true — chunk-system tasks within (view distance + 8) "
                    + "chebyshev of a player run in priority band 18..32 (floor 18 keeps control-plane 15 / saves 16 / light 17 "
                    + "ahead), live-updated on player section crossings. Pure reordering: no budget, no throttle, no new work. "
                    + "(Requires the c2me chunk system; with -Dsuperchunk.chunksystem.disable the band has no consumers.)");
        }
        if (LATENCY_METRICS) {
            LOGGER.info("[SuperChunk-PlayerLatency] player.latencyMetrics=true is INERT on 1.20.1: metric (a) counts "
                    + "PlayerChunkSender getChunkToSend misses (API absent — sending is synchronous on 1.20.1), and "
                    + "metric (c)'s CHUNK_SENDING→send latency collapses to ~0 on the synchronous send path. "
                    + "No [player-latency] lines will be logged.");
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
