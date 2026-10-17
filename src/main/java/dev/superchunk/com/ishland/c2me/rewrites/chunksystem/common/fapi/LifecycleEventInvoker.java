package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.fapi;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.20.1 port: posts {@link ChunkEvent.Load}/{@link ChunkEvent.Unload} on the Forge event
 * bus. The C2ME chunk system REPLACES the two Forge-patched vanilla code paths where 1.20.1
 * normally fires these ({@code ChunkMap.protoChunkToFullChunk}'s
 * {@code new ChunkEvent.Load(levelchunk, !(protochunk instanceof ImposterProtoChunk))},
 * src1201 ChunkMap:722, and {@code ChunkMap.processUnloads}' {@code ChunkEvent.Unload},
 * src1201 ChunkMap:507), so without these posts no server chunk would ever fire
 * ChunkEvent.Load/Unload — silently voiding the platform's chunk-lifecycle contract for map
 * mods, chunk-claim/protection, etc.
 *
 * <p>The Fabric-only CHUNK_LEVEL_TYPE_CHANGE event has no Forge equivalent and stays a no-op
 * (gated off via LateModStatuses#fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE).
 */
public class LifecycleEventInvoker {

    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME Lifecycle Event Invoker");

    public static void invokeChunkLoaded(ServerLevel world, LevelChunk chunk, boolean newChunk) {
        try {
            MinecraftForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, newChunk));
        } catch (Throwable t) {
            LOGGER.error("Failed to invoke chunk load event (world={}, pos={}, newChunk={})", world, chunk.getPos(), newChunk, t);
        }
    }

    public static void invokeChunkUnload(ServerLevel world, LevelChunk chunk) {
        try {
            MinecraftForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
        } catch (Throwable t) {
            LOGGER.error("Failed to invoke chunk unload event (world={}, pos={})", world, chunk.getPos(), t);
        }
    }

    public static boolean needsInvokeChunkLevelTypeChange() {
        return false; // Forge does not have an equivalent event
    }

    public static void invokeChunkLevelTypeChange(ServerLevel world, LevelChunk chunk, FullChunkStatus oldLevelType, FullChunkStatus newLevelType) {
        // no-op on Forge
    }

}
