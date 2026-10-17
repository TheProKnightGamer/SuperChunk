package dev.superchunk.compat;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Bridge over an installed Lithium-family mod's per-section <b>block-counting</b>, so the
 * SuperChunk write paths that bypass {@code LevelChunkSection.setBlockState} (the GPU
 * compact-ids consume path in {@code CompactConsume}) maintain the installed mod's
 * counters. The Forge 1.20.1 mirror of the neo-1.21.1 bridge; on this loader the
 * "Lithium" family is <b>Radium</b> ({@code me.jellysquid.mods.lithium.*}) or
 * <b>Canary</b> ({@code com.abdelaziz.canary.*}) — structurally identical internals,
 * verified against radium-0.12.4 and canary-0.3.3 bytecode.
 *
 * <ul>
 *   <li><b>BUILTIN</b> — SuperChunk's own (relocated) Lithium counting mixin applied.
 *       Since the merged-Lithium wave L1 (portplans/lithium.md) the REAL
 *       {@code BlockStateFlags}/{@code BlockCountingSection} are bundled; {@code ENABLED}
 *       flips true when {@code lithium.util.block_tracking=true} (+ its deps) is set in
 *       {@code superchunk.properties} (the shipped default keeps it false, neo parity).</li>
 *   <li><b>EXTERNAL</b> — an installed fork's counting mixin is live on
 *       {@code LevelChunkSection}. Bound structurally at runtime, two styles probed:
 *       <ol>
 *         <li><i>new-style</i> (modern Lithium): public merged
 *             {@code lithium$trackBlockStateChange(BlockState, BlockState)};</li>
 *         <li><i>old-style</i> (Radium/Canary, the 1.20.1 era): mixin-merged private
 *             {@code short[] countsByFlag} + private static
 *             {@code addToFlagCount(short[], BlockState, int)} — their own bulk
 *             primitive ({@code counts[flagBit] += change} per flag bit of the state's
 *             {@code BlockStateFlagHolder} mask), invoked with exactly the +new/−old
 *             deltas their {@code updateFlagCounters} inject applies. A null counts
 *             array is left null: their lazy client-side init recounts from the palette
 *             (which already contains our writes), so skipping is self-healing, and
 *             fresh worldgen sections carry no listeners so no notification is owed.</li>
 *       </ol></li>
 *   <li><b>OFF</b> — no counting mixin anywhere. Nothing to maintain.</li>
 *   <li><b>UNSAFE</b> — a counting interface IS on {@code LevelChunkSection} but neither
 *       style binds (a future fork rewrote the internals): {@link #directWritesUnsafe()}
 *       makes {@code CompactConsume} fall back to the original loop, whose writes go
 *       through the real {@code setBlockState}, keeping the foreign hooks authoritative.
 *       Correct with any fork, at fast-path cost only in this (log-flagged) case.</li>
 * </ul>
 *
 * <p>Resolution happens lazily on first use — worldgen time, long after every mixin is
 * applied — and is logged once as {@code [SuperChunk-LithiumBridge]}. The verify-path
 * extras (tracked-flag enumeration + {@code mayContainAny}) are bound best-effort.
 */
public final class LithiumBlockTracking {

    private static final Logger LOG = LoggerFactory.getLogger("SuperChunk-LithiumBridge");

    private LithiumBlockTracking() {
    }

    private static final int OFF = 0;
    private static final int BUILTIN = 1;
    private static final int EXTERNAL_TRACK = 2;   // new-style: merged track method
    private static final int EXTERNAL_COUNTS = 3;  // old-style: countsByFlag + addToFlagCount
    private static final int UNSAFE = 4;

    // =====================================================================
    // Public surface (all dispatch constant-folds on Holder's static finals).
    // =====================================================================

    /** True when a bound counting integration is live — i.e. {@link #track} must be called per write. */
    public static boolean active() {
        return Holder.MODE == BUILTIN || Holder.MODE == EXTERNAL_TRACK || Holder.MODE == EXTERNAL_COUNTS;
    }

    /** True when a foreign counting mixin is live but UNBINDABLE: bypassing writers must stand down. */
    public static boolean directWritesUnsafe() {
        return Holder.MODE == UNSAFE;
    }

    /** One {@code trackBlockStateChange(newState, prevState)}-equivalent counter maintenance. */
    public static void track(LevelChunkSection section, BlockState newState, BlockState prevState) {
        switch (Holder.MODE) {
            case BUILTIN ->
                    ((dev.superchunk.net.caffeinemc.mods.lithium.common.block.BlockCountingSection) section)
                            .lithium$trackBlockStateChange(newState, prevState);
            case EXTERNAL_TRACK -> {
                try {
                    Holder.EXT_TRACK.invokeExact(section, newState, prevState);
                } catch (Throwable t) {
                    throw new IllegalStateException("external Lithium trackBlockStateChange failed", t);
                }
            }
            case EXTERNAL_COUNTS -> countsAdjust(section, newState, prevState, 1);
            default -> {
            }
        }
    }

    /** {@code count} identical {@code trackBlockStateChange(newState, prevState)} applications. */
    public static void trackBulk(LevelChunkSection section, BlockState newState, BlockState prevState, int count) {
        switch (Holder.MODE) {
            case BUILTIN ->
                    ((dev.superchunk.net.caffeinemc.mods.lithium.common.block.BlockCountingSection) section)
                            .lithium$trackBlockStateChangeBulk(newState, prevState, count);
            case EXTERNAL_TRACK -> {
                try {
                    for (int i = 0; i < count; i++) {
                        Holder.EXT_TRACK.invokeExact(section, newState, prevState);
                    }
                } catch (Throwable t) {
                    throw new IllegalStateException("external Lithium trackBlockStateChange failed", t);
                }
            }
            case EXTERNAL_COUNTS -> countsAdjust(section, newState, prevState, count);
            default -> {
            }
        }
    }

    /**
     * Old-style bulk: {@code addToFlagCount(counts, newState, +n)} + {@code (prevState, -n)} —
     * the exact deltas the fork's own {@code updateFlagCounters} inject applies per write.
     * A null counts array is skipped on purpose (see class doc: lazy re-init self-heals).
     */
    private static void countsAdjust(LevelChunkSection section, BlockState newState, BlockState prevState, int n) {
        try {
            short[] counts = (short[]) Holder.EXT_COUNTS_FIELD.get(section);
            if (counts == null) {
                return;
            }
            Holder.EXT_ADD_TO_FLAG_COUNT.invoke(null, counts, newState, n);
            if (prevState != null) {
                Holder.EXT_ADD_TO_FLAG_COUNT.invoke(null, counts, prevState, -n);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("external Lithium-fork flag-count adjustment failed", t);
        }
    }

    /** True when the tracked-flag predicates could be enumerated. */
    public static boolean flagsAvailable() {
        return Holder.FLAG_PREDICATES.length > 0;
    }

    /** Number of enumerable tracked flags (0 when {@link #flagsAvailable()} is false). */
    public static int flagCount() {
        return Holder.FLAG_PREDICATES.length;
    }

    /** Exclusive upper bound of {@link #flagIndex} values — size prediction arrays with this. */
    public static int flagIndexUpperBound() {
        return Holder.FLAG_INDEX_BOUND;
    }

    /** The {@code ordinal}-th tracked flag as a plain JDK predicate (the forks' predicates implement it). */
    public static Predicate<BlockState> flagPredicate(int ordinal) {
        return Holder.FLAG_PREDICATES[ordinal];
    }

    /** The fork's own index of the {@code ordinal}-th tracked flag (its counter slot). */
    public static int flagIndex(int ordinal) {
        return Holder.FLAG_INDICES[ordinal];
    }

    /** True when {@code mayContainAny} is bound for {@link #mayContainAny}. */
    public static boolean mayContainAvailable() {
        return Holder.MODE == BUILTIN
                || ((Holder.MODE == EXTERNAL_TRACK || Holder.MODE == EXTERNAL_COUNTS) && Holder.EXT_MAY_CONTAIN != null);
    }

    /** {@code mayContainAny} for the {@code ordinal}-th tracked flag. */
    public static boolean mayContainAny(LevelChunkSection section, int ordinal) {
        if (Holder.MODE == BUILTIN) {
            return ((dev.superchunk.net.caffeinemc.mods.lithium.common.block.BlockCountingSection) section)
                    .lithium$mayContainAny(
                            (dev.superchunk.net.caffeinemc.mods.lithium.common.block.TrackedBlockStatePredicate)
                                    Holder.FLAG_RAW[ordinal]);
        }
        try {
            return (boolean) Holder.EXT_MAY_CONTAIN.invokeExact(section, Holder.FLAG_RAW[ordinal]);
        } catch (Throwable t) {
            throw new IllegalStateException("external Lithium mayContainAny failed", t);
        }
    }

    // =====================================================================
    // Resolution (once, lazily).
    // =====================================================================

    private static final class Holder {
        static final int MODE;
        static final MethodHandle EXT_TRACK;               // (LevelChunkSection, BlockState, BlockState)void
        static final MethodHandle EXT_MAY_CONTAIN;         // (LevelChunkSection, Object)boolean, nullable
        static final Field EXT_COUNTS_FIELD;               // short[] countsByFlag, nullable
        static final Method EXT_ADD_TO_FLAG_COUNT;         // static (short[], BlockState, int)void, nullable
        static final Predicate<BlockState>[] FLAG_PREDICATES;
        static final Object[] FLAG_RAW;
        static final int[] FLAG_INDICES;
        static final int FLAG_INDEX_BOUND;

        static {
            int mode = OFF;
            MethodHandle extTrack = null;
            MethodHandle extMayContain = null;
            Field extCountsField = null;
            Method extAddToFlagCount = null;
            Object[] flagRaw = new Object[0];
            Predicate<BlockState>[] flagPredicates = newPredicateArray(0);
            int[] flagIndices = new int[0];

            if (dev.superchunk.net.caffeinemc.mods.lithium.common.block.BlockStateFlags.ENABLED) {
                mode = BUILTIN;
                var tracked = dev.superchunk.net.caffeinemc.mods.lithium.common.block.BlockStateFlags.TRACKED_FLAGS;
                flagRaw = new Object[tracked.length];
                flagPredicates = newPredicateArray(tracked.length);
                flagIndices = new int[tracked.length];
                for (int i = 0; i < tracked.length; i++) {
                    flagRaw[i] = tracked[i];
                    flagPredicates[i] = tracked[i];
                    flagIndices[i] = tracked[i].getIndex();
                }
            } else {
                Class<?> countingIface = findInterfaceBySimpleName(LevelChunkSection.class, "BlockCountingSection");
                Method trackMethod = findMethod(LevelChunkSection.class, "lithium$trackBlockStateChange",
                        BlockState.class, BlockState.class);
                if (countingIface != null || trackMethod != null) {
                    try {
                        if (trackMethod != null) {
                            // new-style fork
                            extTrack = unreflect(trackMethod)
                                    .asType(MethodType.methodType(void.class, LevelChunkSection.class,
                                            BlockState.class, BlockState.class));
                            mode = EXTERNAL_TRACK;
                        } else {
                            // old-style (Radium/Canary): mixin-merged private members
                            extCountsField = findDeclaredField(LevelChunkSection.class, "countsByFlag", short[].class);
                            extAddToFlagCount = findDeclaredStatic(LevelChunkSection.class, "addToFlagCount");
                            if (extCountsField == null || extAddToFlagCount == null) {
                                throw new NoSuchMethodException("counting interface " + countingIface.getName()
                                        + " present but neither track method nor countsByFlag/addToFlagCount found");
                            }
                            extCountsField.setAccessible(true);
                            extAddToFlagCount.setAccessible(true);
                            mode = EXTERNAL_COUNTS;
                        }
                        // Best-effort extras (verify path only) — failure leaves counting exact.
                        try {
                            Class<?> pkgRoot = countingIface != null ? countingIface : trackMethod.getDeclaringClass();
                            Class<?> flagsCls = Class.forName(
                                    pkgRoot.getPackageName() + ".BlockStateFlags", true, pkgRoot.getClassLoader());
                            Object[] tracked = (Object[]) flagsCls.getField("TRACKED_FLAGS").get(null);
                            flagRaw = tracked.clone();
                            flagPredicates = newPredicateArray(tracked.length);
                            flagIndices = new int[tracked.length];
                            for (int i = 0; i < tracked.length; i++) {
                                @SuppressWarnings("unchecked")
                                Predicate<BlockState> p = (Predicate<BlockState>) tracked[i];
                                flagPredicates[i] = p;
                                Method getIndex = tracked[i].getClass().getMethod("getIndex");
                                trySetAccessible(getIndex);
                                flagIndices[i] = (int) getIndex.invoke(tracked[i]);
                            }
                            Method mayContain = findMethodByName(LevelChunkSection.class, "mayContainAny", 1);
                            if (mayContain == null) {
                                mayContain = findMethodByName(LevelChunkSection.class, "lithium$mayContainAny", 1);
                            }
                            if (mayContain != null) {
                                extMayContain = unreflect(mayContain)
                                        .asType(MethodType.methodType(boolean.class, LevelChunkSection.class, Object.class));
                            }
                        } catch (Throwable extras) {
                            flagRaw = new Object[0];
                            flagPredicates = newPredicateArray(0);
                            flagIndices = new int[0];
                            extMayContain = null;
                            LOG.info("[SuperChunk-LithiumBridge] external Lithium-fork bound for counting, but its "
                                    + "tracked-flag surface was not recognized — verify-mode flag checks are skipped "
                                    + "({}).", String.valueOf(extras));
                        }
                    } catch (Throwable required) {
                        mode = UNSAFE;
                        extTrack = null;
                        extCountsField = null;
                        extAddToFlagCount = null;
                        LOG.error("[SuperChunk-LithiumBridge] a Lithium-fork block-counting mixin is live on "
                                + "LevelChunkSection but its surface could not be bound — SuperChunk's "
                                + "direct-write fast paths (GPU compact-consume) are DISABLED so the installed "
                                + "fork's counters stay correct. Worldgen runs the vanilla write path.", required);
                    }
                }
            }

            int bound = 0;
            for (int idx : flagIndices) {
                bound = Math.max(bound, idx + 1);
            }

            MODE = mode;
            EXT_TRACK = extTrack;
            EXT_MAY_CONTAIN = extMayContain;
            EXT_COUNTS_FIELD = extCountsField;
            EXT_ADD_TO_FLAG_COUNT = extAddToFlagCount;
            FLAG_RAW = flagRaw;
            FLAG_PREDICATES = flagPredicates;
            FLAG_INDICES = flagIndices;
            FLAG_INDEX_BOUND = bound;

            switch (mode) {
                case BUILTIN -> LOG.info("[SuperChunk-LithiumBridge] block-counting mode BUILTIN "
                        + "(bundled counting mixin live, {} tracked flags).", flagIndices.length);
                case EXTERNAL_TRACK -> LOG.info("[SuperChunk-LithiumBridge] block-counting mode EXTERNAL "
                        + "(new-style fork bound via merged track method, {} tracked flags, mayContainAny {}).",
                        flagIndices.length, extMayContain != null ? "bound" : "unavailable");
                case EXTERNAL_COUNTS -> LOG.info("[SuperChunk-LithiumBridge] block-counting mode EXTERNAL "
                        + "(Radium/Canary-style fork bound via countsByFlag+addToFlagCount, {} tracked flags, "
                        + "mayContainAny {}).", flagIndices.length, extMayContain != null ? "bound" : "unavailable");
                case UNSAFE -> {
                    // Already logged at ERROR above.
                }
                default -> LOG.info("[SuperChunk-LithiumBridge] block-counting mode OFF (no counting mixin live).");
            }
        }

        @SuppressWarnings("unchecked")
        private static Predicate<BlockState>[] newPredicateArray(int n) {
            return (Predicate<BlockState>[]) new Predicate[n];
        }

        private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
            try {
                return cls.getMethod(name, params);
            } catch (Throwable t) {
                return null;
            }
        }

        private static Method findMethodByName(Class<?> cls, String name, int paramCount) {
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            return null;
        }

        private static Field findDeclaredField(Class<?> cls, String name, Class<?> type) {
            try {
                Field f = cls.getDeclaredField(name);
                return f.getType() == type ? f : null;
            } catch (Throwable t) {
                return null;
            }
        }

        private static Method findDeclaredStatic(Class<?> cls, String name) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(name) && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 3) {
                    return m;
                }
            }
            return null;
        }

        private static Class<?> findInterfaceBySimpleName(Class<?> cls, String simpleName) {
            for (Class<?> iface : cls.getInterfaces()) {
                if (iface.getSimpleName().equals(simpleName)) {
                    return iface;
                }
            }
            return null;
        }

        private static void trySetAccessible(Method m) {
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
                // public member of an exported package works without it
            }
        }

        /** publicLookup first (public merged member), then setAccessible + private lookup as fallback. */
        private static MethodHandle unreflect(Method m) throws IllegalAccessException {
            try {
                return MethodHandles.publicLookup().unreflect(m);
            } catch (IllegalAccessException e) {
                trySetAccessible(m);
                return MethodHandles.lookup().unreflect(m);
            }
        }
    }
}
