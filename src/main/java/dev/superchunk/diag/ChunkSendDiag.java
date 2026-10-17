package dev.superchunk.diag;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the chunk-SEND path, so "chunks do not appear until I rejoin" can be measured
 * instead of guessed at.
 *
 * <p>Off unless {@code -Dsuperchunk.diag.chunkSend=true}. When on, a daemon prints one line every
 * two seconds naming every stage a chunk send can die at:
 *
 * <ul>
 *   <li>{@code track} — ChunkMap.updateChunkTracking entered (a player's tracked set changed).</li>
 *   <li>{@code trackChunk/trackNull} — what the (b) redirect resolved. trackNull means the
 *       SERVER_ACCESSIBLE future was NOT complete at tracking time, so vanilla had nothing to
 *       send and will not retry from this path.</li>
 *   <li>{@code accSend/accWatchers} — the CHUNK_SENDING seat firing, and how many players
 *       getPlayers() returned for that chunk. accWatchers==0 with accSend>0 is the smoking gun
 *       for "generated, nobody was listening, never re-offered".</li>
 *   <li>{@code plc/plcCancel} — playerLoadedChunk calls and how many the duplicate-send gate
 *       cancelled. plcCancel>0 means the gate is eating sends.</li>
 * </ul>
 *
 * <p>Read it as a funnel: track -> trackChunk -> plc -> (not cancelled) = a packet. Whichever
 * number collapses is where the bug is.
 */
public final class ChunkSendDiag {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean ENABLED = Boolean.getBoolean("superchunk.diag.chunkSend");

    private static final AtomicLong track = new AtomicLong();
    private static final AtomicLong trackChunk = new AtomicLong();
    private static final AtomicLong trackNull = new AtomicLong();
    private static final AtomicLong accSend = new AtomicLong();
    private static final AtomicLong accWatchers = new AtomicLong();
    private static final AtomicLong plc = new AtomicLong();
    private static final AtomicLong plcCancel = new AtomicLong();

    private ChunkSendDiag() {}

    public static void onTracking() { track.incrementAndGet(); }
    public static void onTrackResolved(boolean nonNull) { (nonNull ? trackChunk : trackNull).incrementAndGet(); }
    public static void onAccessibleSend(int watchers) { accSend.incrementAndGet(); accWatchers.addAndGet(watchers); }
    public static void onPlayerLoadedChunk(boolean cancelled) { plc.incrementAndGet(); if (cancelled) plcCancel.incrementAndGet(); }

    public static void start() {
        if (!ENABLED) return;
        Thread t = new Thread(() -> {
            long pT = 0, pTC = 0, pTN = 0, pAS = 0, pAW = 0, pP = 0, pPC = 0;
            while (true) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
                long T = track.get(), TC = trackChunk.get(), TN = trackNull.get(),
                     AS = accSend.get(), AW = accWatchers.get(), P = plc.get(), PC = plcCancel.get();
                long dT = T - pT, dTC = TC - pTC, dTN = TN - pTN, dAS = AS - pAS, dAW = AW - pAW,
                     dP = P - pP, dPC = PC - pPC;
                pT = T; pTC = TC; pTN = TN; pAS = AS; pAW = AW; pP = P; pPC = PC;
                if ((dT | dTC | dTN | dAS | dP) == 0) continue; // stay quiet when idle
                LOGGER.info("[chunk-send-diag] last 2s: track={} trackChunk={} trackNull={} | accSend={} accWatchers={} | plc={} plcCancelled={}",
                        dT, dTC, dTN, dAS, dAW, dP, dPC);
            }
        }, "superchunk-chunk-send-diag");
        t.setDaemon(true);
        t.start();
        LOGGER.info("[chunk-send-diag] ON (-Dsuperchunk.diag.chunkSend=true). Funnel: track -> trackChunk -> plc -> (not cancelled) = packet sent.");
    }
}
