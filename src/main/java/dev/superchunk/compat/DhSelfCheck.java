package dev.superchunk.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Run-time side of a Distant Horizons patch in self-check mode ({@link DhFastPathTable}): which
 * calls also run DH's own code for comparison, and the switch that turns the patch off.
 *
 * <p>Every call is compared until {@code -Dsuperchunk.dh.selfCheck.first} of them (default 2,048)
 * had something to compare and came out equal, then one call in 64. A patch asks {@link #failed()} first and does nothing once it is set; it calls
 * {@link #fail} on the first result that differs from DH's own, or on anything thrown by its own
 * code, and from then on DH runs alone. One warning names the patch and what differed.
 */
public final class DhSelfCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");
    private static final long FIRST = Math.max(1L, Long.getLong("superchunk.dh.selfCheck.first", 2048L));
    private static final int SAMPLE_MASK = 63;

    private final String patch;
    private final String unit;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong compared = new AtomicLong();
    private final AtomicBoolean reported = new AtomicBoolean();
    private volatile boolean failed;
    /** Racy on purpose: a cheap counter for patches called millions of times. */
    private long looseCalls;

    DhSelfCheck(String patch, String unit) {
        this.patch = patch;
        this.unit = unit;
    }

    /** True once the patch has been turned off. */
    public boolean failed() {
        return this.failed;
    }

    /** Whether this call is one to compare with DH's own result. */
    public boolean sample() {
        long n = this.calls.getAndIncrement();
        return this.compared.get() < FIRST || (n & SAMPLE_MASK) == 0;
    }

    /** As {@link #sample()} without the shared counter's cost; a lost count only moves the sample. */
    public boolean sampleLoose() {
        long n = this.looseCalls++;
        return n < FIRST * 64 || (n & SAMPLE_MASK) == 0;
    }

    /** A compared call whose result equalled DH's own. */
    public void passed() {
        if (this.compared.incrementAndGet() == FIRST) {
            LOGGER.info("[SuperChunk-Compat] [dh] {} self-check: the first {} {} equal Distant Horizons' own; checking one in {} from here.",
                    this.patch, FIRST, this.unit, SAMPLE_MASK + 1);
        }
    }

    /**
     * A compared call whose result equalled DH's own because there was nothing in it (a chunk of
     * air). It proves nothing about the patch, so it does not count towards the calls that are
     * all compared.
     */
    public void passedEmpty() {
    }

    /** Turns the patch off: its result differed from DH's own, or its code threw. */
    public void fail(String what, Throwable thrown) {
        this.failed = true;
        if (this.reported.compareAndSet(false, true)) {
            LOGGER.warn("[SuperChunk-Compat] [dh] {} turned itself off: under this Distant Horizons version its result differs from DH's own "
                            + "({}; after {} compared {}). DH's result was used and DH runs its own code from here, as without SuperChunk. "
                            + "Please report this with your Distant Horizons version.",
                    this.patch, what, this.compared.get(), this.unit, thrown);
        }
    }
}
