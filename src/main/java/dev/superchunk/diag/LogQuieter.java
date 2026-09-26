package dev.superchunk.diag;

import dev.superchunk.config.SuperChunkConfig;

import java.util.Set;

/**
 * SuperChunk's log volume ({@code logging.verbose}, default {@code false}).
 *
 * <p>Quiet by default: SuperChunk's own loggers write warnings and errors only. Their
 * informational lines — startup summaries, per-feature status, the once-a-second
 * {@code [predictive-gen]} / {@code [player-latency]} metrics, verify-mode summaries — are
 * dropped by a context-wide Log4j filter ({@link Log4jQuietFilter}). Warnings and errors always
 * pass: they report real trouble (a chunk that failed to generate, a GPU that fell back to the
 * CPU) and are rare. {@code -Dsuperchunk.logging.verbose=true|false} overrides the config file.
 *
 * <p>"Own loggers" means the names SuperChunk's code logs under, including the C2ME and FlowSched
 * engines it bundles. Names it shares with standalone mods that may be installed next to it
 * (Lithium, Starlight) are left alone, so their output is never touched.
 *
 * <p>Installed from {@link SuperChunkConfig#applyEarly()}, i.e. from the mixin plugin's
 * constructor, so only the few lines logged before that are unfiltered. If the logging backend is
 * not Log4j 2 the filter cannot be installed and the mod stays verbose rather than failing.
 */
public final class LogQuieter {

    private static final String PROPERTY = "superchunk.logging.verbose";
    public static final String CONFIG_KEY = "logging.verbose";

    /** Loggers created with these exact names by bundled engine code. */
    private static final Set<String> OWN_NAMES = Set.of(
            "VanillaWorldGenerationDelegate", "TheSpeedyObjectFactory", "ReadFromDisk", "ReadFromDiskAsync",
            "FlowSched Executor Worker Thread", "CheckedThreadLocalRandom");

    private static volatile Boolean verbose;

    private LogQuieter() {
    }

    /** Whether SuperChunk's informational output is wanted; decides once, from -D then config. */
    public static boolean verbose() {
        Boolean v = verbose;
        if (v == null) {
            v = decide();
        }
        return v;
    }

    private static synchronized Boolean decide() {
        if (verbose == null) {
            final String prop = System.getProperty(PROPERTY);
            boolean v;
            if (prop != null) {
                v = Boolean.parseBoolean(prop.trim());
            } else {
                try {
                    v = Boolean.parseBoolean(SuperChunkConfig.get().getProperty(CONFIG_KEY, "false").trim());
                } catch (Throwable t) {
                    v = false;
                }
            }
            verbose = v;
        }
        return verbose;
    }

    /**
     * Installs the filter unless verbose. Idempotent and re-callable (the mod constructor calls it
     * again to cover a logging configuration replaced since the mixin plugin loaded); never throws.
     */
    public static synchronized void install() {
        if (verbose()) {
            return;
        }
        try {
            Log4jQuietFilter.install();
        } catch (Throwable t) {
            verbose = Boolean.TRUE; // not Log4j 2 (or an incompatible version): keep everything
        }
    }

    /** True for the logger names SuperChunk's code (including bundled C2ME/FlowSched) uses. */
    static boolean ownLogger(String name) {
        return name != null && (name.startsWith("dev.superchunk.")
                || name.startsWith("SuperChunk")
                || name.startsWith("C2ME ")
                || name.startsWith("ChunkAccess System of ")
                || OWN_NAMES.contains(name));
    }
}
