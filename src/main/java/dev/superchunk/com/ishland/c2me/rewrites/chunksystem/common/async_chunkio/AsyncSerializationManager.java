package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio;

import it.unimi.dsi.fastutil.objects.Object2ReferenceLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncSerializationManager {

    public static final boolean DEBUG = Boolean.getBoolean("c2me.chunkio.debug");

    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME Async Serialization Manager");

    private static final ThreadLocal<ArrayDeque<Scope>> scopeHolder = ThreadLocal.withInitial(ArrayDeque::new);

    public static void push(Scope scope) {
        scopeHolder.get().push(scope);
    }

    public static Scope getScope(ChunkPos pos) {
        final Scope scope = scopeHolder.get().peek();
        if (pos == null) return scope;
        if (scope != null) {
            if (scope.pos.equals(pos))
                return scope;
            LOGGER.error("Scope position mismatch! Expected: {} but got {}.", scope.pos, pos, new Throwable());
        }
        return null;
    }

    public static void pop(Scope scope) {
        if (scope != scopeHolder.get().peek()) throw new IllegalArgumentException("Scope mismatch");
        scopeHolder.get().pop();
    }

    public static class Scope {
        public final ChunkPos pos;
        public final Map<BlockPos, CompoundTag> blockEntities;
        private final AtomicBoolean isOpen = new AtomicBoolean(false);

        @SuppressWarnings("unchecked")
        public Scope(ChunkAccess chunk, ServerLevel world) {
            this.pos = chunk.getPos();
            Map<BlockPos, CompoundTag> blockEntities = new Object2ReferenceLinkedOpenHashMap<>();
            for (BlockPos blockPos : chunk.getBlockEntitiesPos()) {
                final CompoundTag nbt = chunk.getBlockEntityNbtForSaving(blockPos, world.registryAccess());
                if (nbt == null) {
                    // the game actually allows this to exist
                    LOGGER.debug("Block entity at {} for block {} in chunk {} is missing", blockPos, chunk.getBlockState(blockPos), chunk.getPos());
                }
                if (blockEntities.containsKey(blockPos)) {
                    LOGGER.warn("Duplicate block entity at {} in chunk {}", blockPos, chunk.getPos());
                } else {
                    blockEntities.put(blockPos, nbt);
                }
            }
            this.blockEntities = blockEntities;
        }

        public void open() {
            if (!isOpen.compareAndSet(false, true)) throw new IllegalStateException("Cannot use scope twice");
        }

    }

}
