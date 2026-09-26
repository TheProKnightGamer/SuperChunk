package dev.superchunk.compat;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the optional reflective API with thread-local ownership, without a Skein dependency. */
public final class SkeinCompatTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        check(SkeinCompat.discover(name -> { throw new ClassNotFoundException(name); }, Object.class) == null,
                "Absent Skein must leave the vanilla catchers intact");
        SkeinCompat.ClassLookup lookup = fixtureLookup("com.theproknightgamr.skein");
        SkeinCompat.ClassLookup legacyLookup = fixtureLookup("com.asher.skein");
        SkeinCompat.Bridge bridge = SkeinCompat.discover(lookup, Object.class);
        check(bridge != null, "Complete current optional API must bind");
        check(!bridge.isDimensionPhaseActive(), "Inactive dimension barrier must be visible to whole-server tasks");
        SkeinCompat.Bridge legacyBridge = SkeinCompat.discover(legacyLookup, Object.class);
        check(legacyBridge != null, "Legacy optional API must remain supported");
        fails(() -> SkeinCompat.discover(name -> {
            try {
                return lookup.find(name);
            } catch (ClassNotFoundException absent) {
                return legacyLookup.find(name);
            }
        }, Object.class), "Two installed implementations must fail closed rather than use the wrong owner's state");
        fails(() -> SkeinCompat.discover(name -> {
            if (name.endsWith("TickContext")) throw new ClassNotFoundException(name);
            return lookup.find(name);
        }, Object.class), "Incomplete current ownership API must fail closed");
        fails(() -> SkeinCompat.discover(name -> {
            if (name.equals("com.theproknightgamr.skein.core.TickContext")) {
                throw new ClassNotFoundException(name);
            }
            if (name.equals("com.asher.skein.Skein")) throw new ClassNotFoundException(name);
            try {
                return lookup.find(name);
            } catch (ClassNotFoundException absent) {
                return legacyLookup.find(name);
            }
        }, Object.class), "Incomplete current API must not borrow members from leftover legacy classes");
        fails(() -> SkeinCompat.discover(name -> name.endsWith("$Worker") ? String.class : lookup.find(name),
                Object.class), "A non-Thread worker marker must fail closed");
        fails(() -> SkeinCompat.discover(name -> name.endsWith("$Live") ? MissingSavingFlag.class : lookup.find(name),
                Object.class), "Missing safety config must fail closed");
        fails(() -> SkeinCompat.discover(name -> name.endsWith(".SkeinConfig") ? Object.class : lookup.find(name),
                Object.class), "Missing deferred reload API must fail closed");
        fails(() -> SkeinCompat.discover(name -> name.endsWith(".DimensionTicker") ? MissingBarrierQueue.class : lookup.find(name),
                Object.class), "Missing command barrier queue API must fail closed");

        setFlags(31);
        check(bridge.pinSupportedPhases(), "Enabled phases must be pinned before dispatch");
        check(flags() == 0, "Every unsafe phase, including saving, must be disabled");
        check(Live.enabled && Live.dimensions, "General/dimension settings must remain enabled");
        check(!bridge.pinSupportedPhases(), "Unchanged settings must not produce repeated warnings");
        Live.enabled = false;
        Live.dimensions = false;
        setFlags(31); // Skein refresh() rewrites its Live snapshot on config reload.
        check(bridge.pinSupportedPhases(), "Config reload must be pinned again");
        check(flags() == 0 && !Live.enabled && !Live.dimensions, "Pins must preserve disabled user settings");
        Live.enabled = true;
        Live.dimensions = true;
        check(!bridge.deferConfigMutation() && !Config.stale, "Startup refresh must run immediately outside the barrier");

        DimensionTicker.active = true;
        check(bridge.isDimensionPhaseActive(), "Active dimension barrier must be visible to whole-server tasks");
        setFlags(31);
        fails(bridge::pinSupportedPhases, "Settings must never be changed inside the barrier");
        check(flags() == 31, "Rejected in-phase pin must not partially change live flags");
        setFlags(0);

        Object level = new Object();
        Object otherLevel = new Object();
        Context.owner.set(level);
        check(!bridge.isDimensionTicker(level), "Ordinary thread with ownership must not get the worker exemption");
        Context.owner.remove();
        runThread(new Thread(() -> check(!bridge.isDimensionTicker(level),
                "A thread named like a worker must not get the exemption"), "Skein-Worker-test"));

        runThread(new Worker(() -> {
            check(!bridge.isDimensionTicker(level), "Idle workers do not own a level");
            Context.owner.set(level);
            check(bridge.isDimensionTicker(level), "Exact non-deferring dimension owner must be allowed");
            check(legacyBridge.isDimensionTicker(level), "Legacy namespace must retain the same ownership checks");
            check(bridge.deferConfigMutation(), "Command-block refresh must defer while dimensions tick");
            check(Config.stale && flags() == 0, "Deferred refresh must only mark stale without restoring unsafe flags");
            check(bridge.deferConfigMutation(), "The registry rebuild following a deferred refresh must also defer");
            check(bridge.isDimensionTicker(level), "Deferred reload must preserve the active dimension ownership contract");
            check(!bridge.isDimensionTicker(otherLevel), "A different level must retain its async catcher");
            check(!bridge.isDimensionTicker(null), "Idle/null level must never qualify");
            Context.deferred.set(true);
            check(!bridge.isDimensionTicker(level), "Cell phase worker must not qualify even during dimension ticking");
            Context.deferred.set(false);
            DimensionTicker.active = false;
            check(!bridge.isDimensionTicker(level), "Non-deferring save worker outside the barrier must not qualify");
            DimensionTicker.active = true;
            Live.enabled = false;
            check(!bridge.isDimensionTicker(level), "Disabled Skein must not receive an exemption");
            Live.enabled = true;
            Live.dimensions = false;
            check(!bridge.isDimensionTicker(level), "Disabled dimensions must not receive an exemption");
            Live.dimensions = true;
            for (int mask = 0; mask < 32; mask++) {
                setFlags(mask);
                check(bridge.isDimensionTicker(level) == (mask == 0),
                        "Every enabled in-level/saving flag combination must reject the exemption: " + mask);
            }
            setFlags(0);
            SkeinCompat.Bridge throwing = SkeinCompat.discover(name -> name.endsWith("TickContext")
                    ? ThrowingContext.class : lookup.find(name), Object.class);
            fails(() -> throwing.isDimensionTicker(level), "Failure inside the optional ownership API must fail closed");
            Context.owner.remove();
            Context.deferred.remove();
            check(!bridge.isDimensionTicker(level), "A worker returning to idle must lose its exemption");
        }));
        DimensionTicker.active = false;
        check(Config.stale, "Reload request must survive the worker barrier");
        check(!bridge.deferConfigMutation(), "The next server Pre hook must be allowed to apply the reload");
        Config.stale = false; // refresh() consumes the same flag set by markStale().
        setFlags(31); // Newly loaded user settings before the refresh RETURN hook.
        check(bridge.pinSupportedPhases() && flags() == 0, "Refresh RETURN must pin new settings before any level dispatch");
        check(!bridge.deferConfigMutation(), "Registry rebuild must be allowed after the barrier");
        checkDeferredCommands(bridge, legacyBridge);
        checkCellBridge(lookup);
        System.out.println("SkeinCompatTest passed " + checks + " checks");
    }

    private static void checkCellBridge(SkeinCompat.ClassLookup legacy) throws InterruptedException {
        SkeinCompat.Bridge modern = SkeinCompat.discover(name -> name.endsWith(".core.SuperChunkBridge")
                ? CellBridge.class : legacy.find(name), Object.class);
        Object level = new Object();
        Object other = new Object();
        setFlags(31);
        check(modern.pinSupportedPhases() && flags() == 15, "Cell-capable Skein must retain all tick phases and disable saving");
        check(!modern.pinSupportedPhases(), "Repeated cell-policy pin must be stable");
        check(!modern.isChunkTaskOwner(level), "Server thread without cell context must not claim a chunk task");
        DimensionTicker.active = true;
        runThread(new Worker(() -> {
            Context.owner.set(level);
            check(modern.isDimensionTicker(level), "Nested phases must not revoke the outer dimension owner");
            check(modern.isChunkTaskOwner(level), "Dimension owner must retain chunk access");
            check(!modern.isChunkTaskOwner(other), "Dimension owner must not drive another level's executor");
            Context.deferred.set(true);
            check(!modern.isDimensionTicker(level), "Cell worker must not masquerade as dimension owner");
            check(!modern.isChunkTaskOwner(level), "Cell worker without lock must not drive chunk callbacks");
            CellBridge.locked.set(true);
            check(modern.isChunkTaskOwner(level), "Locked cell must drive its own chunk callbacks");
            check(!modern.isChunkTaskOwner(other), "Locked cell must not drive another level's callbacks");
            CellBridge.locked.set(false);
            check(!modern.isChunkTaskOwner(level), "Releasing lock must revoke callback ownership");
            Config.stale = false;
            check(modern.deferConfigMutation() && Config.stale, "Nested cell reload must defer");
            fails(modern::pinSupportedPhases, "Cell work must not mutate live phase settings");
            Context.deferred.set(false);
            Live.parallelSaving = true;
            check(!modern.isDimensionTicker(level), "Save phase must still veto dimension ownership");
            Live.parallelSaving = false;
            Context.owner.remove();
            Context.deferred.remove();
        }));
        DimensionTicker.active = false;
        List<String> commands = new ArrayList<>();
        runThread(new Worker(() -> {
            Context.owner.set(level);
            Context.deferred.set(true);
            check(modern.isParallelPhaseActive(), "Cells without dimension threading must open a command barrier");
            check(modern.deferConfigMutation(), "Cells without dimension threading must defer reloads");
            check(modern.deferCommand(level, false, () -> commands.add("cell")), "Cell command must enter its deferred buffer");
            check(commands.isEmpty(), "Cell command must not execute early");
            Context.deferred.set(false);
            for (Runnable command; (command = CellBridge.commands.poll()) != null;) command.run();
            Context.owner.remove();
            Context.deferred.remove();
        }));
        check(commands.equals(List.of("cell")), "Cell-only barrier must execute a command exactly once");
        check(!modern.isParallelPhaseActive(), "Closed cell barrier must not stay active");
        Live.threadSafeRandom = false;
        check(modern.pinSupportedPhases() && flags() == 0, "Missing random isolation must disable cell phases");
        Live.threadSafeRandom = true;
        Config.stale = false;
    }

    private static void checkDeferredCommands(SkeinCompat.Bridge bridge, SkeinCompat.Bridge legacyBridge)
            throws InterruptedException {
        Object level = new Object();
        Object otherLevel = new Object();
        List<String> results = new ArrayList<>();
        Runnable command = () -> results.add("unexpected");
        check(!bridge.deferCommand(level, false, command), "Inactive barrier must preserve the caller's original path");
        check(results.isEmpty() && DimensionTicker.commands.isEmpty(), "False must neither execute nor queue the command");
        DimensionTicker.active = true;
        fails(() -> bridge.deferCommand(level, false, command), "Unowned active caller must fail before original execution");
        check(DimensionTicker.commands.isEmpty(), "Unowned caller must not queue work");
        check(bridge.deferCommand(level, true, () -> {
            check(!DimensionTicker.active, "Coordinator transaction must run after the barrier");
            results.add("coordinator");
        }), "Server coordinator must be able to defer the whole transaction");
        runThread(new Worker(() -> {
            fails(() -> bridge.deferCommand(level, false, command), "Idle worker must not defer a transaction");
            Context.owner.set(level);
            fails(() -> bridge.deferCommand(otherLevel, false, command), "Worker must not defer for a different level");
            Context.deferred.set(true);
            fails(() -> bridge.deferCommand(level, false, command), "Cell phase worker must not defer as a dimension owner");
            Context.deferred.set(false);
            Live.entities = true;
            fails(() -> bridge.deferCommand(level, false, command), "Unsupported in-level phase must reject worker commands");
            Live.entities = false;
            check(bridge.deferCommand(level, false, () -> {
                check(!DimensionTicker.active, "Worker transaction must run after the barrier");
                results.add("command");
                results.add("chain");
                results.add("conditional-chain");
            }), "Exact worker must queue its command and chain as one transaction");
            check(legacyBridge.deferCommand(level, false, () -> results.add("legacy")),
                    "Legacy Skein must use the same barrier queue");
            Context.owner.remove();
            Context.deferred.remove();
        }));
        check(results.isEmpty() && DimensionTicker.commands.size() == 3,
                "Accepted transactions must be deferred without partial execution");
        DimensionTicker.active = false;
        DimensionTicker.drainCommands();
        check(results.equals(List.of("coordinator", "command", "chain", "conditional-chain", "legacy")),
                "Barrier drain must execute each whole transaction exactly once in queue order");
        check(DimensionTicker.commands.isEmpty(), "Barrier drain must leave no duplicate transaction");

        DimensionTicker.active = true;
        DimensionTicker.endBeforeDefer = true;
        check(!bridge.deferCommand(level, true, command), "A closed barrier's queue rejection must preserve false");
        check(!DimensionTicker.active && DimensionTicker.commands.isEmpty() && results.size() == 5,
                "Queue rejection must leave execution to the caller without running or queuing work");
        DimensionTicker.endBeforeDefer = false;
        DimensionTicker.active = true;
        DimensionTicker.rejectWhileActive = true;
        fails(() -> bridge.deferCommand(level, true, command),
                "An incompatible queue refusal during an active barrier must not fall through to unsafe execution");
        check(DimensionTicker.commands.isEmpty() && results.size() == 5, "Rejected transaction must remain untouched");
        DimensionTicker.rejectWhileActive = false;
        DimensionTicker.active = false;
    }

    private static SkeinCompat.ClassLookup fixtureLookup(String root) {
        Map<String, Class<?>> classes = Map.of(
                root + ".Skein", SkeinCompatTest.class,
                root + ".core.TickContext$Worker", Worker.class,
                root + ".core.TickContext", Context.class,
                root + ".core.DimensionTicker", DimensionTicker.class,
                root + ".SkeinConfig", Config.class,
                root + ".SkeinConfig$Live", Live.class);
        return name -> {
            Class<?> type = classes.get(name);
            if (type == null) throw new ClassNotFoundException(name);
            return type;
        };
    }

    private static void runThread(Thread thread) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        thread.setUncaughtExceptionHandler((ignored, error) -> failure.set(error));
        thread.setDaemon(true);
        thread.start();
        thread.join(5000);
        check(!thread.isAlive(), "Compatibility fixture must complete promptly");
        if (failure.get() != null) throw new AssertionError("Worker fixture failed", failure.get());
    }

    private static void fails(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            checks++;
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void setFlags(int mask) {
        Live.entities = (mask & 1) != 0;
        Live.randomTicks = (mask & 2) != 0;
        Live.blockEntities = (mask & 4) != 0;
        Live.scheduledTicks = (mask & 8) != 0;
        Live.parallelSaving = (mask & 16) != 0;
    }

    private static int flags() {
        return (Live.entities ? 1 : 0) | (Live.randomTicks ? 2 : 0) | (Live.blockEntities ? 4 : 0)
                | (Live.scheduledTicks ? 8 : 0) | (Live.parallelSaving ? 16 : 0);
    }

    public static final class Worker extends Thread {
        Worker(Runnable action) { super(action, "Skein-test-worker"); }
    }

    public static final class Context {
        static final ThreadLocal<Object> owner = new ThreadLocal<>();
        static final ThreadLocal<Boolean> deferred = ThreadLocal.withInitial(() -> false);
        public static boolean owns(Object level) { return owner.get() == level; }
        public static boolean deferring() { return deferred.get(); }
    }

    public static final class ThrowingContext {
        public static boolean owns(Object level) { throw new IllegalStateException("Simulated incompatible ownership API"); }
        public static boolean deferring() { return false; }
    }

    public static final class DimensionTicker {
        static volatile boolean active;
        static volatile boolean endBeforeDefer;
        static volatile boolean rejectWhileActive;
        static final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
        public static boolean phaseActive() { return active; }
        public static boolean deferPastBarrier(Runnable action) {
            if (endBeforeDefer) active = false;
            if (!active || rejectWhileActive) return false;
            commands.add(action);
            return true;
        }
        static void drainCommands() {
            if (active) throw new AssertionError("Cannot drain while levels tick");
            for (Runnable command = commands.poll(); command != null; command = commands.poll()) command.run();
        }
    }

    public static final class MissingBarrierQueue {
        public static boolean phaseActive() { return false; }
    }

    public static final class Live {
        public static volatile boolean threadSafeRandom = true;
        public static volatile boolean enabled = true;
        public static volatile boolean dimensions = true;
        public static volatile boolean entities;
        public static volatile boolean randomTicks;
        public static volatile boolean blockEntities;
        public static volatile boolean scheduledTicks;
        public static volatile boolean parallelSaving;
    }

    public static final class CellBridge {
        static final ThreadLocal<Boolean> locked = ThreadLocal.withInitial(() -> false);
        static final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
        public static int apiVersion() { return 1; }
        public static boolean supportsParallelTicks() { return Live.threadSafeRandom; }
        public static boolean phaseActive() { return DimensionTicker.active || Context.deferring(); }
        public static boolean ownsChunkTasks(Object level) {
            return Live.enabled && Context.owns(level) && Context.deferring() && locked.get();
        }
        public static boolean deferCommand(Object level, Runnable action) {
            if (level == null || !Context.owns(level) || !Context.deferring()) return false;
            commands.add(() -> { if (!DimensionTicker.deferPastBarrier(action)) action.run(); });
            return true;
        }
    }

    public static final class Config {
        static volatile boolean stale;
        public static void markStale() { stale = true; }
    }

    public static final class MissingSavingFlag {
        public static volatile boolean enabled;
        public static volatile boolean dimensions;
        public static volatile boolean entities;
        public static volatile boolean randomTicks;
        public static volatile boolean blockEntities;
        public static volatile boolean scheduledTicks;
    }
}
