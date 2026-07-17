package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.compat.lithium;

import dev.superchunk.net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker;
import dev.superchunk.net.caffeinemc.mods.lithium.mixin.LithiumMixinPlugin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Upstream C2ME reaches Lithium's {@code ChunkStatusTracker} via loader detection +
 * {@code Class.forName} + a MethodHandle, because Lithium may or may not be installed
 * alongside it. In this merge Lithium is compiled into the same jar, so the bundled case
 * is a plain static call — BUT when a STANDALONE Lithium is installed the bundled module
 * stands down ({@link LithiumMixinPlugin#isStandaloneLithiumActive()}) and the
 * notification must go to the <i>standalone</i> tracker instead (C2ME's chunk-system
 * rewrite replaces the vanilla unload path the standalone's own mixins would have
 * hooked, exactly the gap this invoker exists to fill). That case restores upstream's
 * reflective binding, resolved once at class-init. The catch keeps the old invoker's
 * containment: a tracker failure (e.g. its wrong-thread guard) logs instead of breaking
 * the chunk-system state transition.
 */
public class LithiumChunkStatusTrackerInvoker {

    private static final Logger LOGGER = LoggerFactory.getLogger(LithiumChunkStatusTrackerInvoker.class);

    /** Bound once: the STANDALONE Lithium's {@code ChunkStatusTracker.onChunkInaccessible}, or null when bundled is live. */
    private static final MethodHandle STANDALONE_ON_INACCESSIBLE = bindStandalone();

    private static MethodHandle bindStandalone() {
        if (!LithiumMixinPlugin.isStandaloneLithiumActive()) {
            return null;
        }
        try {
            Class<?> tracker = Class.forName("net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker");
            MethodHandle mh = MethodHandles.publicLookup().findStatic(tracker, "onChunkInaccessible",
                    MethodType.methodType(void.class, ServerLevel.class, ChunkPos.class));
            LOGGER.info("standalone Lithium detected — chunk-inaccessible notifications routed to ITS ChunkStatusTracker");
            return mh;
        } catch (Throwable t) {
            LOGGER.warn("standalone Lithium detected but its ChunkStatusTracker could not be bound — "
                    + "chunk-inaccessible notifications are dropped ({}).", String.valueOf(t));
            return null;
        }
    }

    public static void invokeOnChunkInaccessible(ServerLevel world, ChunkPos pos) {
        try {
            if (STANDALONE_ON_INACCESSIBLE != null) {
                STANDALONE_ON_INACCESSIBLE.invokeExact(world, pos);
            } else if (!LithiumMixinPlugin.isStandaloneLithiumActive()) {
                ChunkStatusTracker.onChunkInaccessible(world, pos);
            }
            // standalone active but unbound: drop (logged once at bind time) — never feed the
            // BUNDLED (stood-down) tracker while a standalone owns the real callbacks.
        } catch (Throwable e) {
            LOGGER.error("Lithium ChunkStatusTracker.onChunkInaccessible(ServerLevel, ChunkPos) failed", e);
        }
    }
}
