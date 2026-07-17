package dev.superchunk.latency;

import dev.superchunk.com.ishland.c2me.base.common.GlobalExecutors;
import dev.superchunk.config.PlayerLatency;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SuperChunk {@code player.latencyMetrics} — proof-metric collector for the player chunk-arrival
 * latency work. Emits one {@code [player-latency]} log line per second per metric:
 *
 * <ol>
 *   <li><b>collect-miss counter</b> (per player, logged from the {@code PlayerChunkSender} mixin
 *       via {@link #logCollectStats}): how many {@code ChunkMap.getChunkToSend} probes inside
 *       {@code collectChunksToSend} returned null this second (pending chunk not yet exposed to
 *       the sender — the P1 defect made visible) plus the current pending-chunk backlog.</li>
 *   <li><b>queue-depth gauge</b> (from the collector thread): the global c2me work-queue depth
 *       bucketed by priority — {@code <=17} (control-plane/saves/light), {@code 18-32} (the P2
 *       player band), {@code 33} (vanilla FULL level) and {@code 34+} (generation-dependency
 *       rings).</li>
 *   <li><b>send-latency</b> p50/p99/max (from the collector thread over samples recorded by the
 *       {@code sendChunk} mixin via {@link #recordSendLatency}): now minus the chunk's
 *       SERVER_ACCESSIBLE_CHUNK_SENDING completion stamp. Semantics note: for freshly generated
 *       chunks this is pipeline latency; a player re-entering long-loaded terrain contributes
 *       "time since that chunk last became send-ready", which inflates max/p99 — read alongside
 *       metric 1.</li>
 * </ol>
 *
 * <p>The collector thread starts lazily from the first {@code sendNextChunks} tick of any player
 * ({@link #ensureStarted}), i.e. only when there is something to measure and long after
 * {@link GlobalExecutors} is initialized — no boot-order risk. Flag OFF (default): nothing ever
 * starts, no line is ever logged.
 */
public final class PlayerLatencyMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-PlayerLatency");

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    /** Cap so a runaway second cannot grow the sample list unboundedly. */
    private static final int MAX_SAMPLES_PER_WINDOW = 65536;

    private static final Object SAMPLE_LOCK = new Object();
    private static LongArrayList sendLatencySamples = new LongArrayList();
    private static int droppedSamples = 0;

    private PlayerLatencyMetrics() {
    }

    /** Starts the 1s collector thread once; cheap checked no-op afterwards (and when the flag is OFF). */
    public static void ensureStarted() {
        if (!PlayerLatency.LATENCY_METRICS) return;
        if (STARTED.get()) return; // hot path: one volatile read per sender tick, no CAS
        if (!STARTED.compareAndSet(false, true)) return;
        final Thread thread = new Thread(PlayerLatencyMetrics::run, "superchunk-latency-metrics");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Metric 1, logged by the PlayerChunkSender mixin once per second per player. */
    public static void logCollectStats(String playerName, int attempts, int misses, int pendingNow) {
        LOGGER.info("[player-latency] player={} collect: attempts={} misses={} pending={}",
                playerName, attempts, misses, pendingNow);
    }

    /** Metric 3 sample feed: {@code System.nanoTime() - CHUNK_SENDING completion stamp}. */
    public static void recordSendLatency(long nanos) {
        if (nanos < 0) return;
        synchronized (SAMPLE_LOCK) {
            if (sendLatencySamples.size() < MAX_SAMPLES_PER_WINDOW) {
                sendLatencySamples.add(nanos);
            } else {
                droppedSamples++;
            }
        }
    }

    private static void run() {
        while (true) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
            try {
                logQueueDepth();
                logSendLatency();
            } catch (Throwable t) {
                // The gauge must never take anything down; log once per incident and continue.
                LOGGER.warn("[player-latency] metrics tick failed", t);
            }
        }
    }

    /** Metric 2: global work-queue depth by priority bucket. */
    private static void logQueueDepth() {
        final int[] counts = GlobalExecutors.prioritizedScheduler.getGlobalWorkQueueTaskCounts();
        int le17 = 0, band = 0, at33 = 0, rest = 0, total = 0;
        for (int priority = 0; priority < counts.length; priority++) {
            final int c = counts[priority];
            if (c == 0) continue;
            total += c;
            if (priority <= 17) le17 += c;
            else if (priority <= 32) band += c;
            else if (priority == 33) at33 += c;
            else rest += c;
        }
        LOGGER.info("[player-latency] queue-depth: <=17={} 18-32={} 33={} 34+={} total={}",
                le17, band, at33, rest, total);
    }

    /** Metric 3: p50/p99/max of the send-latency samples collected this second. */
    private static void logSendLatency() {
        long[] samples;
        int dropped;
        synchronized (SAMPLE_LOCK) {
            if (sendLatencySamples.isEmpty()) return;
            samples = sendLatencySamples.toLongArray();
            sendLatencySamples = new LongArrayList();
            dropped = droppedSamples;
            droppedSamples = 0;
        }
        Arrays.sort(samples);
        final long p50 = samples[(samples.length - 1) / 2];
        final long p99 = samples[Math.max(0, (int) Math.ceil(samples.length * 0.99) - 1)];
        final long max = samples[samples.length - 1];
        LOGGER.info("[player-latency] send-latency: n={} p50={}ms p99={}ms max={}ms (now - CHUNK_SENDING completion){}",
                samples.length, formatMs(p50), formatMs(p99), formatMs(max),
                dropped > 0 ? " dropped=" + dropped : "");
    }

    private static String formatMs(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }
}
