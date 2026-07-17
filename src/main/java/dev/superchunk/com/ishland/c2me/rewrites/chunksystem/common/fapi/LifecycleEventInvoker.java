package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.fapi;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge port: posts {@link ChunkEvent.Load}/{@link ChunkEvent.Unload} on the NeoForge event
 * bus, mirroring the official C2ME-NeoForge port. The C2ME chunk system REPLACES the two vanilla
 * code paths where NeoForge normally fires these (ChunkStatusTasks.full's proto->full conversion
 * and ChunkMap.processUnloads), so without these posts no server chunk would ever fire
 * ChunkEvent.Load/Unload — silently voiding the platform's chunk-lifecycle contract for map
 * mods, chunk-claim/protection, etc.
 *
 * <p>The Fabric-only CHUNK_LEVEL_TYPE_CHANGE event has no NeoForge equivalent and stays a no-op
 * (gated off via LateModStatuses#fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE).
 */
public class LifecycleEventInvoker {

    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME Lifecycle Event Invoker");

    public static void invokeChunkLoaded(ServerLevel world, LevelChunk chunk, boolean newChunk) {
        try {
            NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, newChunk));
        } catch (Throwable t) {
            LOGGER.error("Failed to invoke chunk load event (world={}, pos={}, newChunk={})", world, chunk.getPos(), newChunk, t);
        }
    }

    public static void invokeChunkUnload(ServerLevel world, LevelChunk chunk) {
        try {
            NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
        } catch (Throwable t) {
            LOGGER.error("Failed to invoke chunk unload event (world={}, pos={})", world, chunk.getPos(), t);
        }
    }

    public static boolean needsInvokeChunkLevelTypeChange() {
        return false; // NeoForge does not have an equivalent event
    }

    public static void invokeChunkLevelTypeChange(ServerLevel world, LevelChunk chunk, FullChunkStatus oldLevelType, FullChunkStatus newLevelType) {
        // no-op on NeoForge
    }

}
