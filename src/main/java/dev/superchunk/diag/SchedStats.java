package dev.superchunk.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.LongAdder;

/**
 * Test tool: counts what the chunk scheduler does ({@code -Dsuperchunk.debug.schedStats=true}),
 * to see where its task volume comes from. One line every 10 seconds with the counts since the
 * last line; divide by the chunks generated in that time. Off: every call site is a constant
 * {@code if (false)}.
 */
public final class SchedStats {

    public static final boolean ENABLED = Boolean.getBoolean("superchunk.debug.schedStats");

    public static final int ENQUEUE = 0, TICK = 1, TICK_BUSY = 2, TICK_IDLE = 3, TICK_ADVANCE = 4, TICK_DOWNGRADE = 5,
            TICKET_ADD_DEP = 6, TICKET_ADD_EXT = 7, TICKET_REMOVE = 8, MARK_DIRTY = 9, MARK_DIRTY_SCHEDULED = 10,
            DEP_ADD = 11, DEP_REMOVE = 12, CONTEXT = 13, TICK_STALE = 14, TICK_CLEANUP = 15, TICKET_SAME_TARGET = 16;
    private static final String[] NAMES = {"enqueue", "tick", "tickBusy", "tickIdle", "tickAdvance", "tickDowngrade",
            "ticketAddDep", "ticketAddExt", "ticketRemove", "markDirty", "markDirtyScheduled", "depAdd", "depRemove",
            "context", "tickStale", "tickCleanup", "ticketNoTick"};
    private static final LongAdder[] COUNTS = new LongAdder[NAMES.length];
    private static final long[] LAST = new long[NAMES.length];

    static {
        for (int i = 0; i < COUNTS.length; i++) {
            COUNTS[i] = new LongAdder();
        }
        if (ENABLED) {
            Logger logger = LoggerFactory.getLogger("SuperChunk-SchedStats");
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(10_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    StringBuilder sb = new StringBuilder("[sched-stats] 10s:");
                    for (int i = 0; i < COUNTS.length; i++) {
                        long now = COUNTS[i].sum();
                        sb.append(' ').append(NAMES[i]).append('=').append(now - LAST[i]);
                        LAST[i] = now;
                    }
                    logger.info(sb.toString());
                }
            }, "superchunk-sched-stats");
            t.setDaemon(true);
            t.start();
        }
    }

    private SchedStats() {
    }

    public static void count(int what) {
        COUNTS[what].increment();
    }
}
