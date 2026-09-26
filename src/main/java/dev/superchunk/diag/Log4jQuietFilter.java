package dev.superchunk.diag;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.message.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The Log4j 2 side of {@link LogQuieter}, kept in its own class so that nothing references
 * Log4j-core types unless the filter is actually installed.
 *
 * <p>A context-wide filter runs for every logger's enabled-check, so it answers in a few compares:
 * WARN and above, and every logger that is not SuperChunk's, are NEUTRAL (normal level handling);
 * only SuperChunk's own sub-WARN events are denied.
 */
final class Log4jQuietFilter extends AbstractFilter {

    private Log4jQuietFilter() {
        super(Result.NEUTRAL, Result.NEUTRAL);
    }

    private static final Log4jQuietFilter INSTANCE = new Log4jQuietFilter();
    private static final Set<LoggerContext> WATCHED = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Adds the filter to every Log4j context's current configuration, and to each configuration a
     * context switches to later: NeoForge replaces the early-startup configuration with the game's
     * once mods load, which would otherwise drop the filter. Idempotent; call again at any time to
     * cover contexts created since.
     */
    static synchronized void install() {
        if (!INSTANCE.isStarted()) {
            INSTANCE.start();
        }
        final List<LoggerContext> contexts = new ArrayList<>();
        if (LogManager.getFactory() instanceof Log4jContextFactory factory) {
            for (org.apache.logging.log4j.core.LoggerContext context : factory.getSelector().getLoggerContexts()) {
                contexts.add(context);
            }
        }
        if (LogManager.getContext(false) instanceof LoggerContext current && !contexts.contains(current)) {
            contexts.add(current);
        }
        for (LoggerContext context : contexts) {
            addTo(context, context.getConfiguration());
            if (WATCHED.add(context)) {
                context.addPropertyChangeListener(event -> {
                    if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                            && event.getNewValue() instanceof Configuration next) {
                        addTo(context, next);
                    }
                });
            }
        }
    }

    private static void addTo(LoggerContext context, Configuration configuration) {
        final Filter present = configuration.getFilter();
        if (present == INSTANCE
                || (present instanceof CompositeFilter composite && composite.getFilters().contains(INSTANCE))) {
            return;
        }
        configuration.addFilter(INSTANCE);
        context.updateLoggers();
    }

    private static Result decide(String loggerName, Level level) {
        if (level == null || level.isMoreSpecificThan(Level.WARN)) {
            return Result.NEUTRAL;
        }
        return LogQuieter.ownLogger(loggerName) ? Result.DENY : Result.NEUTRAL;
    }

    @Override
    public Result filter(LogEvent event) {
        return decide(event.getLoggerName(), event.getLevel());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return decide(logger.getName(), level);
    }

    // The fixed-arity overloads too: AbstractFilter's defaults box their arguments into an
    // Object[] on every enabled-check of every logger in the game.
    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return decide(logger.getName(), level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return decide(logger.getName(), level);
    }
}
