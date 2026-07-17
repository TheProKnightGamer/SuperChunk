package dev.superchunk.gpu;

import org.slf4j.Logger;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * A tiny, self-contained Swing window that shows a "compiling GPU kernels…" loading
 * bar while OpenCL programs JIT-compile at world load. It exists purely to reassure
 * the user that the game isn't frozen: on a cold boot the NVIDIA driver's
 * {@code clBuildProgram} can take from seconds to (worst case) minutes across the
 * ~100 density-function kernels, and there is otherwise no on-screen signal.
 *
 * <p><b>Where it is driven from.</b> {@link CLProgram#build(String, String)} — the single
 * JIT choke point every source compile in the codebase passes through — calls
 * {@link #onCompileBegin()} before {@code clBuildProgram} and {@link #onCompileEnd(boolean)}
 * after. Warm boots (disk-cache binary relinks via {@code buildFromBinary}) never touch
 * {@code build()}, so the window correctly stays hidden when nothing slow is happening.
 *
 * <p><b>Unknown total.</b> The number of kernels isn't known up front (multiple dimension
 * routers, lazily-compiled cache-wrapper DFs, optional self-tests), so the bar fills on a
 * soft-approach curve of the completed count rather than a true fraction — it always moves
 * forward and never claims "done" prematurely. The label carries the exact live count and
 * elapsed time, so the user always sees real progress even during a single long compile.
 *
 * <p><b>Safety.</b> Everything is best-effort and wrapped so a UI failure can never disturb
 * worldgen. It is disabled automatically when the JVM is headless (dedicated servers) and on
 * macOS (where AWT and GLFW fight over the main run loop) unless explicitly forced. The
 * window never steals focus from the game and auto-closes a few seconds after compilation
 * goes quiet. Toggle with {@code -Dsuperchunk.gpu.compileWindow=true|false}.
 */
public final class CompileProgressWindow {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** Resolved once: is the window allowed to appear at all this session? */
    private static final boolean ENABLED = resolveEnabled();

    /** Set true after any UI failure so we stop touching AWT for the rest of the session. */
    private static volatile boolean broken = false;
    /** Set true if the user closes the window — we then never re-show it this session. */
    private static volatile boolean dismissed = false;

    // --- cross-thread state (updated from many c2me worker threads) ---
    /** Source compiles that finished successfully (the live count shown to the user). */
    private static final AtomicInteger completed = new AtomicInteger();
    /** Source compiles that failed ({@code clBuildProgram} error); shown when nonzero. */
    private static final AtomicInteger failed = new AtomicInteger();
    /** Compiles currently in flight; the watchdog never closes the window while > 0. */
    private static final AtomicInteger inFlight = new AtomicInteger();
    /** nanoTime of the most recent begin/end — the watchdog's "still busy?" signal. */
    private static volatile long lastActivityNanos;
    /** nanoTime of the first compile (for the elapsed-time readout). */
    private static volatile long startNanos;
    /**
     * Set by {@link #finishNow()} (backend teardown) and cleared by the next
     * {@link #onCompileBegin()}: end events from compiles that straggle past shutdown must
     * not resurrect the window, but a genuinely new compile wave (next world load) may.
     */
    private static volatile boolean idleClosed;

    // --- EDT-owned UI (only touched on the Swing event thread) ---
    private static JFrame frame;
    private static JProgressBar bar;
    private static JLabel status;
    private static Timer watchdog;
    private static Timer linger;
    private static boolean finishing;

    /** Controls how fast the bar fills: fraction = 1 - exp(-completed / TAU). */
    private static final double TAU = 45.0;
    /** Close the window this long after the last compile finishes (and nothing is in flight). */
    private static final long QUIET_MILLIS = 4_000L;
    /** How long the "Done" state lingers before the window disposes. */
    private static final int DONE_LINGER_MILLIS = 1_100;

    private CompileProgressWindow() {
    }

    /**
     * Called by {@link CLProgram#build(String, String)} immediately before a
     * {@code clBuildProgram} JIT. Lazily shows the window on the first compile and
     * marks the backend busy. Never throws.
     */
    public static void onCompileBegin() {
        if (!ENABLED) {
            return;
        }
        try {
            // Counter bookkeeping is unconditional (beyond ENABLED) so begin/end stay paired
            // in every interleaving with dismissal/breakage; only the UI work is gated.
            long now = System.nanoTime();
            if (startNanos == 0L) {
                startNanos = now;
            }
            lastActivityNanos = now;
            idleClosed = false;
            inFlight.incrementAndGet();
            if (broken || dismissed) {
                return;
            }
            SwingUtilities.invokeLater(CompileProgressWindow::ensureShownAndRefresh);
        } catch (Throwable t) {
            markBroken(t);
        }
    }

    /**
     * Called by {@link CLProgram#build(String, String)} after a compile finishes.
     * Advances the count and refreshes the bar. Never throws.
     *
     * @param success whether {@code clBuildProgram} actually succeeded — failures are
     *                tallied separately so the label never overstates "compiled".
     */
    public static void onCompileEnd(boolean success) {
        if (!ENABLED) {
            return;
        }
        try {
            lastActivityNanos = System.nanoTime();
            if (success) {
                completed.incrementAndGet();
            } else {
                failed.incrementAndGet();
            }
            inFlight.decrementAndGet();
            if (broken || dismissed) {
                return;
            }
            SwingUtilities.invokeLater(CompileProgressWindow::ensureShownAndRefresh);
        } catch (Throwable t) {
            markBroken(t);
        }
    }

    /**
     * Immediately finishes and disposes the window (best-effort). Wired into
     * {@link OpenCLBackend#shutdown()} so a per-world backend teardown cleanly closes
     * any lingering window instead of waiting on the quiet-period watchdog. Never throws.
     */
    public static void finishNow() {
        if (!ENABLED || broken) {
            return;
        }
        try {
            idleClosed = true; // stragglers ending after teardown must not resurrect the window
            SwingUtilities.invokeLater(() -> disposeWindow(false));
        } catch (Throwable ignored) {
            // shutdown path — swallow everything
        }
    }

    // ------------------------------------------------------------------
    // EDT-only helpers
    // ------------------------------------------------------------------

    /** Builds the window on first use, then repaints the bar/label. Runs on the EDT. */
    private static void ensureShownAndRefresh() {
        if (broken || dismissed) {
            return;
        }
        try {
            if (finishing && inFlight.get() > 0) {
                // A new compile began during the done-linger — resume live display instead
                // of letting the pending dispose leave that compile with no window.
                cancelFinish();
            }
            if (frame == null) {
                // Only (re)build while work is actually in flight: a stale end event after
                // finishNow()/teardown, or with nothing running, must not pop a window.
                if (idleClosed || inFlight.get() == 0) {
                    return;
                }
                buildWindow();
            }
            if (frame != null && !finishing) {
                refresh(false);
            }
        } catch (Throwable t) {
            markBroken(t);
        }
    }

    /** Constructs the JFrame + progress bar. Runs on the EDT when there is no live window. */
    private static void buildWindow() {
        finishing = false; // fresh window (may be a later compile wave after an earlier close)
        JFrame f = new JFrame("SuperChunk — Compiling GPU kernels");
        f.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // Don't yank focus/input away from the game window when we pop up.
        f.setAutoRequestFocus(false);
        f.setFocusableWindowState(false);
        f.setAlwaysOnTop(true);
        f.setResizable(false);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        JLabel title = new JLabel("Compiling GPU worldgen kernels…");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        JProgressBar b = new JProgressBar(0, 1000);
        b.setValue(0);
        b.setStringPainted(false);
        b.setPreferredSize(new Dimension(360, 22));
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        b.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        JLabel s = new JLabel("Starting…");
        s.setFont(s.getFont().deriveFont(11f));
        s.setAlignmentX(JPanel.LEFT_ALIGNMENT);

        panel.add(title);
        panel.add(Box.createVerticalStrut(10));
        panel.add(b);
        panel.add(Box.createVerticalStrut(8));
        panel.add(s);

        f.getContentPane().setLayout(new BorderLayout());
        f.getContentPane().add(panel, BorderLayout.CENTER);
        f.pack();
        f.setLocationRelativeTo(null); // center on screen
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                dismissed = true; // user closed it — don't reopen this session
                // DISPOSE_ON_CLOSE only kills the frame; tear down our timers/refs too,
                // or the watchdog keeps ticking disposed components for the session.
                disposeWindow(false);
            }
        });

        frame = f;
        bar = b;
        status = s;

        // Watchdog: ticks the elapsed-time readout (so the window is visibly alive even
        // during one long compile) and auto-closes once compilation goes quiet.
        watchdog = new Timer(500, e -> tick());
        watchdog.setRepeats(true);
        watchdog.start();

        f.setVisible(true);
    }

    /** Periodic EDT tick: refresh the readout, and close the window once compiling is done. */
    private static void tick() {
        if (broken || dismissed) {
            // Terminal for this window: stop ticking instead of refreshing dead components.
            if (watchdog != null) {
                watchdog.stop();
                watchdog = null;
            }
            return;
        }
        if (finishing || frame == null) {
            return;
        }
        try {
            long idleMillis = (System.nanoTime() - lastActivityNanos) / 1_000_000L;
            if (inFlight.get() == 0 && completed.get() + failed.get() > 0 && idleMillis >= QUIET_MILLIS) {
                beginFinish();
            } else {
                refresh(false);
            }
        } catch (Throwable t) {
            markBroken(t);
        }
    }

    /** Updates the bar fraction and the status line. {@code done} snaps the bar to full. */
    private static void refresh(boolean done) {
        if (bar == null || status == null) {
            return;
        }
        int n = completed.get();
        int nFailed = failed.get();
        String failedSuffix = nFailed == 0 ? "" : " · " + nFailed + " failed";
        if (done) {
            bar.setValue(1000);
            status.setText(n + " kernel" + (n == 1 ? "" : "s") + " compiled" + failedSuffix + " · done");
            return;
        }
        // Soft-approach curve: always advances, never reaches 100% until we're actually done.
        double frac = 1.0 - Math.exp(-(n + nFailed) / TAU);
        if (frac > 0.99) {
            frac = 0.99;
        }
        bar.setValue((int) Math.round(frac * 1000.0));
        long elapsed = startNanos == 0L ? 0L : (System.nanoTime() - startNanos) / 1_000_000_000L;
        status.setText(String.format(Locale.ROOT, "%d compiled%s · %ds elapsed", n, failedSuffix, elapsed));
    }

    /** Enters the "Done" state: fill the bar, then dispose after a short linger. */
    private static void beginFinish() {
        finishing = true;
        refresh(true);
        linger = new Timer(DONE_LINGER_MILLIS, e -> disposeWindow(true));
        linger.setRepeats(false);
        linger.start();
    }

    /** Backs out of a pending done-linger because a new compile wave started. Runs on the EDT. */
    private static void cancelFinish() {
        finishing = false;
        if (linger != null) {
            linger.stop();
            linger = null;
        }
    }

    /** Tears down the window + timers. Runs on the EDT. {@code natural} = quiet-period close. */
    private static void disposeWindow(boolean natural) {
        try {
            if (watchdog != null) {
                watchdog.stop();
                watchdog = null;
            }
            if (linger != null) {
                linger.stop();
                linger = null;
            }
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
            bar = null;
            status = null;
            finishing = false; // allow a later compile wave to build a fresh window
            if (natural) {
                LOGGER.info("[SuperChunk-GPU] kernel-compile progress window closed ({} kernels compiled).",
                        completed.get());
            }
        } catch (Throwable ignored) {
            // teardown must never propagate
        }
    }

    // ------------------------------------------------------------------

    private static void markBroken(Throwable t) {
        broken = true;
        try {
            LOGGER.debug("[SuperChunk-GPU] compile progress window disabled after error", t);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Resolves whether the window may appear. Off when: explicitly disabled; the JVM is
     * headless (dedicated server / {@code -Djava.awt.headless=true}); or on macOS, where
     * AWT and the game's GLFW loop contend for the main thread — unless
     * {@code -Dsuperchunk.gpu.compileWindow=true} forces it.
     */
    private static boolean resolveEnabled() {
        try {
            String prop = System.getProperty("superchunk.gpu.compileWindow");
            if (prop != null && prop.equalsIgnoreCase("false")) {
                return false;
            }
            boolean forced = prop != null && prop.equalsIgnoreCase("true");
            if (GraphicsEnvironment.isHeadless()) {
                return false;
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("mac") && !forced) {
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
