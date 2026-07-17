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
 * Bridge over Lithium's per-section <b>block-counting</b> integration, so the SuperChunk
 * write paths that bypass {@code LevelChunkSection.setBlockState} (the GPU compact-ids
 * consume path in {@code CompactConsume} and the Noisium direct-palette redirect in
 * {@code NoiseChunkGeneratorMixin}) maintain the flag counters of <i>whichever</i>
 * Lithium is live:
 *
 * <ul>
 *   <li><b>BUILTIN</b> — SuperChunk's bundled (relocated) Lithium module applied its
 *       counting mixin. Direct calls into the relocated classes, exactly as before this
 *       bridge existed (zero overhead: the mode is a static-final constant, so the JIT
 *       folds the dispatch).</li>
 *   <li><b>EXTERNAL</b> — a STANDALONE Lithium is installed (the bundled module stands
 *       down, see {@code LithiumMixinPlugin}) and its block-tracking mixin is live on
 *       {@code LevelChunkSection}. Bound structurally at runtime via MethodHandles —
 *       never compiled against — so ANY Lithium version whose merged surface still has
 *       {@code lithium$trackBlockStateChange(BlockState, BlockState)} works, current and
 *       future. The bundled bulk shortcut ({@code lithium$trackBlockStateChangeBulk}, a
 *       SuperChunk addition absent from standalone Lithium) is replayed as N per-block
 *       calls — the bulk method is <i>defined</i> as exactly that arithmetic.</li>
 *   <li><b>OFF</b> — no counting mixin anywhere (no Lithium, or its
 *       {@code util.block_tracking} rule disabled). Nothing to maintain.</li>
 *   <li><b>UNSAFE</b> — a foreign counting mixin IS live on {@code LevelChunkSection}
 *       but its surface could not be bound (a future Lithium rewrote the internals
 *       again). The bypassing writers MUST NOT run or the foreign counters go stale:
 *       {@link #directWritesUnsafe()} makes {@code CompactConsume} fall back to the
 *       original loop and the Noisium redirect fall back to the real
 *       {@code setBlockState}, so the foreign hooks stay authoritative. Correct with
 *       any Lithium, at fast-path cost only in this (log-flagged) case.</li>
 * </ul>
 *
 * <p>Resolution happens lazily on first use — worldgen time, long after every mixin is
 * applied and registries are bootstrapped — and is logged once as
 * {@code [SuperChunk-LithiumBridge]}.
 *
 * <p>The verify-path extras (tracked-flag enumeration + {@code lithium$mayContainAny})
 * are bound best-effort: if only the track call binds, counting stays EXACT and verify
 * simply skips the Lithium-flag comparison ({@link #flagsAvailable()} /
 * {@link #mayContainAvailable()}).
 */
public final class LithiumBlockTracking {

    private static final Logger LOG = LoggerFactory.getLogger("SuperChunk-LithiumBridge");

    private LithiumBlockTracking() {
    }

    private static final int OFF = 0;
    private static final int BUILTIN = 1;
    private static final int EXTERNAL = 2;
    private static final int UNSAFE = 3;

    // =====================================================================
    // Public surface (all dispatch constant-folds on Holder's static finals).
    // =====================================================================

    /** True when a bound counting integration is live (BUILTIN or EXTERNAL) — i.e. {@link #track} must be called per write. */
    public static boolean active() {
        return Holder.MODE == BUILTIN || Holder.MODE == EXTERNAL;
    }

    /**
     * True when a foreign counting mixin is live but UNBINDABLE: every SuperChunk write
     * path that bypasses {@code setBlockState} must stand down (original loop / real
     * {@code setBlockState}) so the foreign hooks maintain their own counters.
     */
    public static boolean directWritesUnsafe() {
        return Holder.MODE == UNSAFE;
    }

    /** One {@code trackBlockStateChange(newState, prevState)} — the per-block counter maintenance. */
    public static void track(LevelChunkSection section, BlockState newState, BlockState prevState) {
        if (Holder.MODE == BUILTIN) {
            ((dev.superchunk.net.caffeinemc.mods.lithium.common.block.BlockCountingSection) section)
                    .lithium$trackBlockStateChange(newState, prevState);
        } else if (Holder.MODE == EXTERNAL) {
            try {
                Holder.EXT_TRACK.invokeExact(section, newState, prevState);
            } catch (Throwable t) {
                throw new IllegalStateException("external Lithium trackBlockStateChange failed", t);
            }
        }
    }

    /** {@code count} identical {@code trackBlockStateChange(newState, prevState)} applications (bulk shortcut when BUILTIN). */
    public static void trackBulk(LevelChunkSection section, BlockState newState, BlockState prevState, int count) {
        if (Holder.MODE == BUILTIN) {
            ((dev.superchunk.net.caffeinemc.mods.lithium.common.block.BlockCountingSection) section)
                    .lithium$trackBlockStateChangeBulk(newState, prevState, count);
        } else if (Holder.MODE == EXTERNAL) {
            try {
                for (int i = 0; i < count; i++) {
                    Holder.EXT_TRACK.invokeExact(section, newState, prevState);
                }
            } catch (Throwable t) {
                throw new IllegalStateException("external Lithium trackBlockStateChange failed", t);
            }
        }
    }

    /** True when the tracked-flag predicates could be enumerated (always true for BUILTIN when active). */
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

    /** The {@code ordinal}-th tracked flag as a plain JDK predicate (Lithium's predicates implement it). */
    public static Predicate<BlockState> flagPredicate(int ordinal) {
        return Holder.FLAG_PREDICATES[ordinal];
    }

    /** Lithium's own index of the {@code ordinal}-th tracked flag (its counter slot). */
    public static int flagIndex(int ordinal) {
        return Holder.FLAG_INDICES[ordinal];
    }

    /** True when {@code lithium$mayContainAny} is bound for {@link #mayContainAny}. */
    public static boolean mayContainAvailable() {
        return Holder.MODE == BUILTIN || (Holder.MODE == EXTERNAL && Holder.EXT_MAY_CONTAIN != null);
    }

    /** {@code lithium$mayContainAny} for the {@code ordinal}-th tracked flag. */
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
        @SuppressWarnings("unchecked")
        static final Predicate<BlockState>[] FLAG_PREDICATES;
        static final Object[] FLAG_RAW;                    // the TrackedBlockStatePredicate instances (either package)
        static final int[] FLAG_INDICES;
        static final int FLAG_INDEX_BOUND;

        static {
            int mode = OFF;
            MethodHandle extTrack = null;
            MethodHandle extMayContain = null;
            Object[] flagRaw = new Object[0];
            Predicate<BlockState>[] flagPredicates = newPredicateArray(0);
            int[] flagIndices = new int[0];

            if (dev.superchunk.net.caffeinemc.mods.lithium.common.block.BlockStateFlags.ENABLED) {
                // Bundled counting mixin applied (standalone Lithium absent — the bundled module
                // stands down whenever one is installed, so BUILTIN and a live foreign mixin are
                // mutually exclusive).
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
                // Foreign-counting probe: a standalone Lithium('s fork) merged its interface into
                // LevelChunkSection. Probe BOTH by interface simple name and by merged method name
                // so a partial future rename still trips the UNSAFE guard instead of going stale.
                Method trackMethod = findMethod(LevelChunkSection.class, "lithium$trackBlockStateChange",
                        BlockState.class, BlockState.class);
                Class<?> countingIface = findInterfaceBySimpleName(LevelChunkSection.class, "BlockCountingSection");
                if (trackMethod != null || countingIface != null) {
                    try {
                        if (trackMethod == null) {
                            throw new NoSuchMethodException(
                                    "counting interface " + countingIface.getName()
                                            + " present but no lithium$trackBlockStateChange(BlockState, BlockState)");
                        }
                        extTrack = unreflect(trackMethod)
                                .asType(MethodType.methodType(void.class, LevelChunkSection.class,
                                        BlockState.class, BlockState.class));
                        mode = EXTERNAL;
                        // Best-effort extras (verify path only) — failure leaves counting exact.
                        try {
                            Class<?> owner = trackMethod.getDeclaringClass();
                            Class<?> pkgRoot = countingIface != null ? countingIface : owner;
                            Class<?> flagsCls = Class.forName(
                                    pkgRoot.getPackageName() + ".BlockStateFlags", true, pkgRoot.getClassLoader());
                            Field trackedField = flagsCls.getField("TRACKED_FLAGS");
                            Object[] tracked = (Object[]) trackedField.get(null);
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
                            Method mayContain = findMethodByName(LevelChunkSection.class, "lithium$mayContainAny", 1);
                            if (mayContain != null) {
                                extMayContain = unreflect(mayContain)
                                        .asType(MethodType.methodType(boolean.class, LevelChunkSection.class, Object.class));
                            }
                        } catch (Throwable extras) {
                            flagRaw = new Object[0];
                            flagPredicates = newPredicateArray(0);
                            flagIndices = new int[0];
                            extMayContain = null;
                            LOG.info("[SuperChunk-LithiumBridge] external Lithium bound for counting, but its "
                                    + "tracked-flag surface was not recognized — verify-mode flag checks are skipped "
                                    + "({}).", String.valueOf(extras));
                        }
                    } catch (Throwable required) {
                        mode = UNSAFE;
                        LOG.error("[SuperChunk-LithiumBridge] a Lithium block-counting mixin is live on "
                                + "LevelChunkSection but its surface could not be bound — SuperChunk's "
                                + "direct-write fast paths (GPU compact-consume, Noisium palette writes) are "
                                + "DISABLED so the installed Lithium's counters stay correct. Worldgen runs the "
                                + "vanilla write path.", required);
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
            FLAG_RAW = flagRaw;
            FLAG_PREDICATES = flagPredicates;
            FLAG_INDICES = flagIndices;
            FLAG_INDEX_BOUND = bound;

            switch (mode) {
                case BUILTIN -> LOG.info("[SuperChunk-LithiumBridge] block-counting mode BUILTIN "
                        + "(bundled Lithium counting mixin live, {} tracked flags).", flagIndices.length);
                case EXTERNAL -> LOG.info("[SuperChunk-LithiumBridge] block-counting mode EXTERNAL "
                        + "(standalone Lithium counting mixin bound structurally, {} tracked flags, mayContainAny {}).",
                        flagIndices.length, extMayContain != null ? "bound" : "unavailable");
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
