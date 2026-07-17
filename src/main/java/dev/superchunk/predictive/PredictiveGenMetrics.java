package dev.superchunk.predictive;

import dev.superchunk.config.PredictiveGen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk {@code player.predictiveGen} proof metrics — one {@code [predictive-gen]} log line
 * per second while there is predictor activity (never spams an idle server, never logs when the
 * flag is OFF because nothing ever starts the collector).
 *
 * <p>Counters (cumulative atomics, logged as per-second deltas + cumulative hit rate):
 * <ul>
 *   <li><b>issued / retracted</b> — predictor tickets added / removed (ticket churn = corridor
 *       turnover; a player flying straight should retract almost nothing).</li>
 *   <li><b>hits / misses</b> — a predicted chunk is a HIT when it enters the predicting player's
 *       real no-tick view (chebyshev &le; no-tick view distance) within the configured hit window
 *       after being ticketed; it is a MISS when the window expires first. Misses are wasted
 *       generation — a persistently low hit rate means the corridor shape/lookahead is wrong for
 *       how players actually move.</li>
 * </ul>
 * Gauges (volatile snapshots per world, summed across registered per-world loaders): active
 * corridor players, desired corridor chunks, ticketed chunks, in-flight predicted loads.
 *
 * <p>Same lazy-daemon-thread pattern as {@code PlayerLatencyMetrics}: {@link #ensureStarted}
 * is first called from the first corridor activation, long after executors exist.
 */
public final class PredictiveGenMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-PredictiveGen");

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    static final AtomicLong TICKETS_ISSUED = new AtomicLong();
    static final AtomicLong TICKETS_RETRACTED = new AtomicLong();
    static final AtomicLong HITS = new AtomicLong();
    static final AtomicLong MISSES = new AtomicLong();
    /** Hit samples dropped because the pending-hit table hit its bound (untracked, neither hit nor miss). */
    static final AtomicLong HIT_SAMPLES_DROPPED = new AtomicLong();

    /** Per-world loaders, registered on first activity, unregistered on world close. */
    private static final CopyOnWriteArrayList<PredictiveChunkLoader> LOADERS = new CopyOnWriteArrayList<>();

    private PredictiveGenMetrics() {
    }

    static void register(PredictiveChunkLoader loader) {
        LOADERS.addIfAbsent(loader);
    }

    static void unregister(PredictiveChunkLoader loader) {
        LOADERS.remove(loader);
    }

    /** Starts the 1s collector thread once; cheap checked no-op afterwards (and when logging is OFF). */
    static void ensureStarted() {
        if (!PredictiveGen.LOG_METRICS) return;
        if (STARTED.get()) return; // hot path: one volatile read, no CAS
        if (!STARTED.compareAndSet(false, true)) return;
        final Thread thread = new Thread(PredictiveGenMetrics::run, "superchunk-predictive-gen-metrics");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void run() {
        long lastIssued = 0, lastRetracted = 0, lastHits = 0, lastMisses = 0, lastDropped = 0;
        while (true) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
            try {
                final long issued = TICKETS_ISSUED.get();
                final long retracted = TICKETS_RETRACTED.get();
                final long hits = HITS.get();
                final long misses = MISSES.get();
                final long dropped = HIT_SAMPLES_DROPPED.get();

                int players = 0, desired = 0, ticketed = 0, inFlight = 0, pendingHits = 0;
                for (PredictiveChunkLoader loader : LOADERS) {
                    players += loader.gaugeActivePlayers;
                    desired += loader.gaugeDesiredChunks;
                    ticketed += loader.gaugeTicketedChunks;
                    inFlight += loader.gaugeInFlightLoads;
                    pendingHits += loader.gaugePendingHitSamples;
                }

                final long dIssued = issued - lastIssued;
                final long dRetracted = retracted - lastRetracted;
                final long dHits = hits - lastHits;
                final long dMisses = misses - lastMisses;
                final long dDropped = dropped - lastDropped;
                lastIssued = issued;
                lastRetracted = retracted;
                lastHits = hits;
                lastMisses = misses;
                lastDropped = dropped;

                // Only log seconds with activity: an idle server stays silent. pendingHits is
                // deliberately NOT part of the gate (it is display-only): samples of a player
                // who slowed down resolve on the position feed, so they must not keep an
                // otherwise idle server logging.
                if (players == 0 && desired == 0 && ticketed == 0 && inFlight == 0
                        && dIssued == 0 && dRetracted == 0 && dHits == 0 && dMisses == 0) {
                    continue;
                }

                final long resolved = hits + misses;
                final String hitRate = resolved == 0
                        ? "n/a"
                        : String.format(Locale.ROOT, "%.1f%%", 100.0 * hits / resolved);
                LOGGER.info("[predictive-gen] players={} desired={} ticketed={} inFlight={} pendingHitSamples={} "
                                + "issued=+{} retracted=+{} hits=+{} misses=+{} hitRate={} ({}/{} cumulative){}",
                        players, desired, ticketed, inFlight, pendingHits,
                        dIssued, dRetracted, dHits, dMisses, hitRate, hits, resolved,
                        dDropped > 0 ? " droppedHitSamples=+" + dDropped : "");
            } catch (Throwable t) {
                // The gauge must never take anything down; log and continue.
                LOGGER.warn("[predictive-gen] metrics tick failed", t);
            }
        }
    }
}
