package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses;

import com.google.common.base.Preconditions;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import dev.superchunk.com.ishland.c2me.base.common.config.LateModStatuses;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IWorldChunk;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.compat.lithium.LithiumChunkStatusTrackerInvoker;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.fapi.LifecycleEventInvoker;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.threadstate.ChunkTaskWork;
import dev.superchunk.com.ishland.flowsched.scheduler.Cancellable;
import dev.superchunk.com.ishland.flowsched.util.Assertions;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import net.minecraft.world.entity.EntityType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;

import java.util.List;

public class ServerAccessible extends NewChunkStatus {

    public ServerAccessible(int ordinal) {
        super(ordinal, ChunkStatus.FULL);
    }

    @Override
    public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable) {
        final ChunkAccess chunk = context.holder().getItem().get().chunk();
        Preconditions.checkState(chunk instanceof ProtoChunk, "ChunkAccess must be a proto chunk");
        ProtoChunk protoChunk = (ProtoChunk) chunk;

        return Completable
                .fromRunnable(() -> {
                    Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                        ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
                        final LevelChunk worldChunk = toFullChunk(protoChunk, serverWorld);

                        worldChunk.setFullStatus(context.holder().getUserData().get()::getLevelType);
                        context.holder().getItem().set(new ChunkState(worldChunk, new ImposterProtoChunk(worldChunk, false), ChunkStatus.FULL));
                        if (!((IWorldChunk) worldChunk).isLoadedToWorld()) {
                            worldChunk.runPostLoad();
                            worldChunk.setLoaded(true);
                            worldChunk.registerAllBlockEntitiesAfterLevelLoad();
                            worldChunk.registerTickContainerInLevel(serverWorld);
                            // No fabric gate: on NeoForge the invoker posts ChunkEvent.Load itself
                            // (the vanilla post site in ChunkStatusTasks.full is bypassed by this
                            // chunk system) — upstream C2ME-NeoForge removes the gate the same way.
                            LifecycleEventInvoker.invokeChunkLoaded(serverWorld, worldChunk, !(protoChunk instanceof ImposterProtoChunk));
                        }

                        if (LateModStatuses.fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE) {
                            LifecycleEventInvoker.invokeChunkLevelTypeChange(serverWorld, worldChunk, FullChunkStatus.INACCESSIBLE, FullChunkStatus.FULL);
                        }
                        ((IThreadedAnvilChunkStorage) context.tacs()).getCurrentChunkHolders().put(context.holder().getKey().toLong(), context.holder().getUserData().get());
                        ((IThreadedAnvilChunkStorage) context.tacs()).setChunkHolderListDirty(true);
                    }
                })
                .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()));
    }

    @Override
    public Completable postUpgradeToThis(ChunkLoadingContext context) {
        return Completable.complete();
    }

    private static LevelChunk toFullChunk(ProtoChunk protoChunk, ServerLevel serverWorld) {
        LevelChunk worldChunk;
        if (protoChunk instanceof ImposterProtoChunk) {
            worldChunk = ((ImposterProtoChunk) protoChunk).getWrapped();
        } else {
            worldChunk = new LevelChunk(serverWorld, protoChunk, worldChunkx -> {
                final List<CompoundTag> entities = protoChunk.getEntities();
                if (!entities.isEmpty()) {
                    serverWorld.addWorldGenChunkEntities(EntityType.loadEntitiesRecursive(entities, serverWorld));
                }
            });
        }
        return worldChunk;
    }

    @Override
    public Completable preDowngradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
        return Completable.complete();
    }

    @Override
    public Completable downgradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
        ChunkState state = context.holder().getItem().get();
        final ChunkAccess chunk = state.chunk();
        Preconditions.checkState(chunk instanceof LevelChunk, "ChunkAccess must be a full chunk");
        return Completable
                .fromRunnable(() -> {
                    Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, false))) {
                        ((IThreadedAnvilChunkStorage) context.tacs()).getCurrentChunkHolders().remove(context.holder().getKey().toLong());
                        ((IThreadedAnvilChunkStorage) context.tacs()).setChunkHolderListDirty(true);
                        final LevelChunk worldChunk = (LevelChunk) chunk;
                        ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
        //                worldChunk.setLoaded(false);
        //                worldChunk.removeChunkTickSchedulers(((IThreadedAnvilChunkStorage) context.tacs()).getWorld());
                       if (LateModStatuses.fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE) {
                        LifecycleEventInvoker.invokeChunkLevelTypeChange(serverWorld, worldChunk, FullChunkStatus.FULL, FullChunkStatus.INACCESSIBLE);
                    }
                        LithiumChunkStatusTrackerInvoker.invokeOnChunkInaccessible(((IThreadedAnvilChunkStorage) context.tacs()).getWorld(), context.holder().getKey());worldChunk.setFullStatus(null);context.holder().getItem().set(new ChunkState(state.protoChunk(), state.protoChunk(), ChunkStatus.FULL));
                    }
                })
                .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()));
    }

    @Override
    public String toString() {
        return "minecraft:full, Border";
    }
}
