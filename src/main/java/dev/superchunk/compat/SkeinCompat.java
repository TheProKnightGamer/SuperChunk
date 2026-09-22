package dev.superchunk.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Allows Skein's dimension owner through the per-level async catchers.
 *
 * <p>A worker can also run cell ticks or save jobs, so its class alone is not
 * evidence of exclusive level ownership. Only the dimension barrier, the
 * exact level's non-deferring owner, and a dimension-only configuration qualify.
 * Whole-server saves retain their server-thread requirement.
 *
 * <p>Skein's Lithium detector does not recognise SuperChunk's relocated copy.
 * After every config refresh, explicitly disable Skein's in-level phases and
 * parallel saving in its live configuration, leaving its dimension setting
 * intact. Refreshes and registry rebuilds during the dimension barrier use
 * Skein's stale flag to run at the next server-tick boundary. No configuration
 * file is changed. An unknown API fails closed.
 */
public final class SkeinCompat {
    private static final String[] PACKAGE_ROOTS = {"com.theproknightgamr.skein", "com.asher.skein"};

    private static final Bridge BRIDGE = discover(
            name -> Class.forName(name, false, SkeinCompat.class.getClassLoader()), Level.class);
    private static boolean warned;
    private static final AtomicBoolean deferredRefreshLogged = new AtomicBoolean();
    private static final AtomicBoolean deferredCommandLogged = new AtomicBoolean();

    private SkeinCompat() {}

    /** Called at tickChildren HEAD, after ServerTickEvent.Pre and before level dispatch. */
    public static void beforeTickChildren(Thread serverThread) {
        if (BRIDGE == null) {
            return;
        }
        if (Thread.currentThread() != serverThread) {
            throw new IllegalStateException("SuperChunk must configure Skein on the server thread before levels tick");
        }
        pinDimensionOnly();
    }

    /** Config refresh RETURN: also covers startup and commands executed after tickChildren HEAD. */
    public static void afterConfigRefresh() {
        if (BRIDGE != null) {
            pinDimensionOnly();
            deferredRefreshLogged.set(false);
        }
    }

    /** Config/registry mutation HEAD: the caller must cancel when this returns true. */
    public static boolean deferConfigMutation() {
        if (BRIDGE == null || !BRIDGE.deferConfigMutation()) {
            return false;
        }
        if (deferredRefreshLogged.compareAndSet(false, true)) {
            LogUtils.getLogger().info("SuperChunk deferred a Skein configuration reload until the next server tick "
                    + "because dimension workers are still active.");
        }
        return true;
    }

    private static void pinDimensionOnly() {
        if (BRIDGE.pinDimensionOnly() && !warned) {
            warned = true;
            LogUtils.getLogger().warn("SuperChunk supports Skein dimension threading only: disabling Skein's "
                    + "entities, randomTicks, blockEntities, scheduledTicks and saving parallel phases in its live "
                    + "configuration. Bundled Lithium assumes one thread per level. The dimensions setting and "
                    + "config file are unchanged; these compatibility pins are reapplied after config reloads.");
        }
    }

    /** True only for this level's exclusive dimension-ticking worker. */
    public static boolean isDimensionTicker(Level level) {
        return BRIDGE != null && BRIDGE.isDimensionTicker(level);
    }

    /** All-level server tasks must wait until Skein releases its dimension barrier. */
    public static boolean isDimensionPhaseActive() {
        return BRIDGE != null && BRIDGE.isDimensionPhaseActive();
    }

    /**
     * Queue the whole command-block transaction, including its chain, after the
     * dimension barrier. Callers should check isDimensionPhaseActive before
     * creating the Runnable on a normal tick. An active but unowned caller must
     * fail instead of falling through into a partially executed command.
     */
    public static boolean deferCommand(Level level, Runnable command) {
        if (BRIDGE == null || !BRIDGE.isDimensionPhaseActive()) {
            return false;
        }
        var server = level == null ? null : level.getServer();
        boolean serverCoordinator = server != null && server.isSameThread();
        boolean deferred = BRIDGE.deferCommand(level, serverCoordinator, command);
        if (deferred && deferredCommandLogged.compareAndSet(false, true)) {
            LogUtils.getLogger().info("SuperChunk deferred command-block transactions until Skein dimension workers finish.");
        }
        return deferred;
    }

    @FunctionalInterface
    interface ClassLookup {
        Class<?> find(String name) throws ClassNotFoundException;
    }

    // Injectable discovery keeps the optional dependency and its failure modes testable.
    static Bridge discover(ClassLookup classes, Class<?> levelType) {
        try {
            String presentRoot = null;
            for (String root : PACKAGE_ROOTS) {
                try {
                    classes.find(root + ".Skein");
                } catch (ClassNotFoundException absent) {
                    continue;
                }
                if (presentRoot != null) {
                    throw new LinkageError("Both current and legacy Skein implementations are installed");
                }
                presentRoot = root;
            }
            if (presentRoot == null) {
                return null;
            }
            // Only an absent entry class permits trying another namespace. Once
            // found, every API member must come from that same implementation.
            return new Bridge(classes.find(presentRoot + ".core.TickContext$Worker"),
                    classes.find(presentRoot + ".core.TickContext"),
                    classes.find(presentRoot + ".core.DimensionTicker"),
                    classes.find(presentRoot + ".SkeinConfig"),
                    classes.find(presentRoot + ".SkeinConfig$Live"), levelType);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw unsupported(exception);
        }
    }

    private static IllegalStateException unsupported(Throwable cause) {
        return new IllegalStateException("SuperChunk cannot establish safe Skein dimension ownership/configuration. "
                + "This Skein API is unsupported; disable Skein or use a compatible version before ticking levels.", cause);
    }

    static final class Bridge {
        private static final String[] PARALLEL_FLAGS = {
                "entities", "randomTicks", "blockEntities", "scheduledTicks", "parallelSaving"
        };

        private final Class<?> worker;
        private final MethodHandle owns;
        private final MethodHandle deferring;
        private final MethodHandle dimensionPhase;
        private final MethodHandle deferPastBarrier;
        private final MethodHandle markConfigStale;
        private final VarHandle enabled;
        private final VarHandle dimensions;
        private final VarHandle[] parallelFlags = new VarHandle[PARALLEL_FLAGS.length];

        private Bridge(Class<?> worker, Class<?> context, Class<?> ticker, Class<?> config,
                       Class<?> live, Class<?> levelType)
                throws ReflectiveOperationException {
            if (!Thread.class.isAssignableFrom(worker)) {
                throw new NoSuchMethodException("Skein TickContext.Worker is not a Thread");
            }
            this.worker = worker;
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            this.owns = lookup.findStatic(context, "owns", MethodType.methodType(boolean.class, levelType))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            this.deferring = lookup.findStatic(context, "deferring", MethodType.methodType(boolean.class));
            this.dimensionPhase = lookup.findStatic(ticker, "phaseActive", MethodType.methodType(boolean.class));
            this.deferPastBarrier = lookup.findStatic(ticker, "deferPastBarrier",
                    MethodType.methodType(boolean.class, Runnable.class));
            this.markConfigStale = lookup.findStatic(config, "markStale", MethodType.methodType(void.class));
            this.enabled = lookup.findStaticVarHandle(live, "enabled", boolean.class);
            this.dimensions = lookup.findStaticVarHandle(live, "dimensions", boolean.class);
            for (int i = 0; i < PARALLEL_FLAGS.length; i++) {
                this.parallelFlags[i] = lookup.findStaticVarHandle(live, PARALLEL_FLAGS[i], boolean.class);
            }
        }

        boolean deferConfigMutation() {
            try {
                if (!(boolean) this.dimensionPhase.invokeExact()) {
                    return false;
                }
                // markStale is the same volatile handoff used by Skein's file
                // watcher. refreshIfStale also rebuilds registries on server Pre.
                this.markConfigStale.invokeExact();
                return true;
            } catch (Throwable exception) {
                throw unsupported(exception);
            }
        }

        boolean isDimensionPhaseActive() {
            try {
                return (boolean) this.dimensionPhase.invokeExact();
            } catch (Throwable exception) {
                throw unsupported(exception);
            }
        }

        boolean deferCommand(Object level, boolean serverCoordinator, Runnable command) {
            if (!this.isDimensionPhaseActive()) {
                return false;
            }
            if (level == null || (!serverCoordinator && !this.isDimensionTicker(level))) {
                throw new IllegalStateException("Cannot execute or defer a command-block transaction from a thread "
                        + "that does not own its level during Skein dimension ticking");
            }
            try {
                boolean deferred = (boolean) this.deferPastBarrier.invokeExact(command);
                if (!deferred && (boolean) this.dimensionPhase.invokeExact()) {
                    throw new IllegalStateException("Skein rejected a command-block transaction while its dimension barrier was active");
                }
                return deferred;
            } catch (Throwable exception) {
                throw unsupported(exception);
            }
        }

        boolean pinDimensionOnly() {
            try {
                if ((boolean) this.dimensionPhase.invokeExact()) {
                    throw new IllegalStateException("Cannot change Skein compatibility settings during a dimension phase");
                }
                boolean changed = false;
                for (VarHandle flag : this.parallelFlags) {
                    if ((boolean) flag.getVolatile()) {
                        flag.setVolatile(false);
                        changed = true;
                    }
                }
                return changed;
            } catch (Throwable exception) {
                throw unsupported(exception);
            }
        }

        boolean isDimensionTicker(Object level) {
            if (level == null || !this.worker.isInstance(Thread.currentThread())) {
                return false;
            }
            try {
                if (!(boolean) this.enabled.getVolatile() || !(boolean) this.dimensions.getVolatile()
                        || !(boolean) this.dimensionPhase.invokeExact()
                        || (boolean) this.deferring.invokeExact() || !(boolean) this.owns.invokeExact(level)) {
                    return false;
                }
                for (VarHandle flag : this.parallelFlags) {
                    if ((boolean) flag.getVolatile()) {
                        return false;
                    }
                }
                return true;
            } catch (Throwable exception) {
                throw unsupported(exception);
            }
        }
    }
}
