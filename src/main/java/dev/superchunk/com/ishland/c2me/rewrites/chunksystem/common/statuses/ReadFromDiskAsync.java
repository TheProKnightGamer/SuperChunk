package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses;

import dev.superchunk.com.ishland.c2me.base.common.GlobalExecutors;
import dev.superchunk.com.ishland.c2me.base.common.registry.SerializerAccess;
import dev.superchunk.com.ishland.c2me.base.common.util.HookCompatibility;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
import dev.superchunk.com.ishland.c2me.base.common.theinterface.IDirectStorage;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import dev.superchunk.com.ishland.c2me.base.common.util.RxJavaUtils;
import dev.superchunk.com.ishland.c2me.base.mixin.access.ISerializingRegionBasedStorage;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IServerLightingProvider;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IVersionedChunkStorage;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IWorldChunk;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.AsyncSerializationManager;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.BlendingInfoUtil;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.ChunkIoMainThreadTaskUtils;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.ProtoChunkExtension;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.SerializingRegionBasedStorageExtension;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IPOIUnloading;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.fapi.LifecycleEventInvoker;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.threadstate.ChunkTaskWork;
import dev.superchunk.com.ishland.flowsched.scheduler.Cancellable;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import dev.superchunk.com.ishland.flowsched.util.Assertions;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkDataEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forge 1.20.1 deltas vs neo (plan §1.3): {@code ChunkSerializer.read} loses the 1.20.5+ storage
 * key; the POI async read goes through the {@code SectionStorage.worker} IOWorker's
 * {@code loadAsync} (1.20.1 has no SimpleRegionStorage/{@code read}); {@code ChunkType} is the
 * inner {@code ChunkStatus.ChunkType}; {@code getPersistedStatus} → {@code getStatus};
 * {@code ChunkDataEvent.Save} is posted on {@code MinecraftForge.EVENT_BUS} mirroring the
 * 1.20.1 patch shape ({@code chunk.getWorldForge() != null ? ... : level}, src1201 ChunkMap:830).
 *
 * <p>RISK (plan §5.3, documented): off-thread {@code ChunkSerializer.read} posts Forge
 * {@code ChunkDataEvent.Load} from a worker thread — which is why {@code asyncSerialization}
 * ships default-OFF on forge (see {@code Config}).
 */
public class ReadFromDiskAsync extends ReadFromDisk {

    private static final Logger LOGGER = LoggerFactory.getLogger("ReadFromDiskAsync");

    public ReadFromDiskAsync(int ordinal) {
        super(ordinal);
    }

    @Override
    public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable) {
        final Single<ProtoChunk> single = invokeAsyncLoad(context)
                .retryWhen(RxJavaUtils.retryWithExponentialBackoff(3, 200))
                .cache()
                .onErrorResumeNext(throwable -> {
                    LOGGER.error("Failed to load chunk {} asynchronously, falling back to sync loading", context.holder().getKey(), throwable);
                    return invokeSyncRead(context)
                            .retryWhen(RxJavaUtils.retryWithExponentialBackoff(3, 200, new RuntimeException("Failed to load asynchronously, falling back to sync loading", throwable)));
                });
        return finalizeLoading(context, single);
    }

    protected Single<ProtoChunk> invokeAsyncLoad(ChunkLoadingContext context) {
        return Single.defer(() -> Single.fromCompletionStage(((IThreadedAnvilChunkStorage) context.tacs()).invokeGetUpdatedChunkNbt(context.holder().getKey())))
                .map(nbt -> nbt.filter(nbt2 -> {
                    boolean bl = nbt2.contains("Status", Tag.TAG_STRING);
                    if (!bl) {
                        LOGGER.error("ChunkAccess file at {} is missing level data, skipping", context.holder().getKey());
                    }

                    return bl;
                }))
                .observeOn(Schedulers.from(((IVanillaChunkManager) context.tacs()).c2me$getSchedulingManager().positionedExecutor(context.holder().getKey().toLong())))
                .map(nbt -> {
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                        final ReferenceArrayList<Runnable> mainThreadQueue = new ReferenceArrayList<>();
                        if (nbt.isPresent()) {
                            ChunkIoMainThreadTaskUtils.push(mainThreadQueue);
                            try {
                                return Pair.of(mainThreadQueue, ChunkSerializer.read(
                                        ((IThreadedAnvilChunkStorage) context.tacs()).getWorld(),
                                        ((IThreadedAnvilChunkStorage) context.tacs()).getPointOfInterestStorage(),
                                        context.holder().getKey(),
                                        nbt.get()
                                ));
                            } finally {
                                ChunkIoMainThreadTaskUtils.pop(mainThreadQueue);
                            }
                        } else {
                            return Pair.of(mainThreadQueue, createEmptyProtoChunk(context));
                        }
                    }
                })
                .flatMap(pair -> {
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                        final ServerLevel world = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
                        ProtoChunk protoChunk = pair.right();
                        // blending
                        final ChunkPos pos = context.holder().getKey();
                        protoChunk = protoChunk != null ? protoChunk : new ProtoChunk(pos, UpgradeData.EMPTY, world, world.registryAccess().registryOrThrow(Registries.BIOME), null);
                        if (protoChunk.getBelowZeroRetrogen() != null || protoChunk.getStatus().getChunkType() == ChunkStatus.ChunkType.PROTOCHUNK) {
                            ProtoChunk finalProtoChunk = protoChunk;
                            return Single.defer(() -> Single.fromCompletionStage(BlendingInfoUtil.getBlendingInfos(((IVersionedChunkStorage) context.tacs()).getWorker(), pos)))
                                    .doOnSuccess(bitSets -> ((ProtoChunkExtension) finalProtoChunk).setBlendingInfo(pos, bitSets))
                                    .map(unused -> pair);
                        } else {
                            return Single.just(pair);
                        }
                    }
                })
                .zipWith(
                        Single.defer(() -> Single.fromCompletionStage(((ISerializingRegionBasedStorage) ((IThreadedAnvilChunkStorage) context.tacs()).getPointOfInterestStorage()).getStorageAccess().loadAsync(context.holder().getKey()))),
                        (pair, poiNbt) -> {
                            // J17: List#addFirst is a J21 SequencedCollection method — use add(0, ...)
                            pair.left().add(0, () -> ((SerializingRegionBasedStorageExtension) ((IThreadedAnvilChunkStorage) context.tacs()).getPointOfInterestStorage()).update(context.holder().getKey(), poiNbt.orElse(null)));
                            return pair;
                        }
                )
                .observeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()))
                .map(pair -> {
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                        ChunkIoMainThreadTaskUtils.drainQueue(pair.left());
                    }
                    return pair.right();
                });
    }

    @Override
    public Completable preDowngradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
        return super.preDowngradeFromThis(context, cancellable);
    }

    @Override
    public Completable downgradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
        final AtomicBoolean loadedToWorld = new AtomicBoolean(false);
        return Completable
                .defer(() -> {
                    Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, false))) {
                        final ChunkState chunkState = context.holder().getItem().get();
                        ChunkAccess chunk = chunkState.chunk();
                        if (chunk instanceof ImposterProtoChunk protoChunk) chunk = protoChunk.getWrapped();

                        if (chunk instanceof LevelChunk worldChunk) {
                            loadedToWorld.set(((IWorldChunk) worldChunk).isLoadedToWorld());
                            worldChunk.setLoaded(false);
                        }

                        if ((context.holder().getFlags() & ItemHolder.FLAG_BROKEN) != 0 && chunk instanceof ProtoChunk) { // do not save broken ProtoChunks
                            LOGGER.warn("Not saving partially generated broken chunk {}", context.holder().getKey());
                            return Completable.complete();
                        } else if (chunk instanceof LevelChunk && !chunkState.reachedStatus().isOrAfter(ChunkStatus.FULL)) {
                            // do not save WorldChunks that doesn't reach full status: Vanilla behavior
                            // If saved, block entities will be lost
                            return Completable.complete();
                        } else {
                            return asyncSave(context.tacs(), chunk);
                        }
                    }
                })
                .observeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()))
                .doOnComplete(() -> {
                    Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, false))) {
                        ChunkAccess chunk = context.holder().getItem().get().chunk();
                        if (chunk instanceof ImposterProtoChunk protoChunk) chunk = protoChunk.getWrapped();

                        if (context.holder().getTargetStatus().ordinal() >= this.ordinal()) { // saving cancelled late
                            if (chunk instanceof LevelChunk worldChunk) {
                                worldChunk.setLoaded(loadedToWorld.get());
                            }
                            cancellable.cancel();
                            throw new CancellationException();
                        }

                        if (loadedToWorld.get() && chunk instanceof LevelChunk worldChunk) {
                            // Forge port: the invoker posts ChunkEvent.Unload itself (the patched
                            // vanilla post site in ChunkMap.processUnloads is bypassed here).
                            LifecycleEventInvoker.invokeChunkUnload(((IThreadedAnvilChunkStorage) context.tacs()).getWorld(), worldChunk);
                            ((IThreadedAnvilChunkStorage) context.tacs()).getWorld().unload(worldChunk);
                        }

                        ((IServerLightingProvider) ((IThreadedAnvilChunkStorage) context.tacs()).getLightingProvider()).invokeUpdateChunkStatus(chunk.getPos());
                        ((IThreadedAnvilChunkStorage) context.tacs()).getLightingProvider().tryScheduleUpdate();
                        ((IThreadedAnvilChunkStorage) context.tacs()).getWorldGenerationProgressListener().onStatusChange(chunk.getPos(), null);
                        ((IThreadedAnvilChunkStorage) context.tacs()).getChunkToNextSaveTimeMs().remove(chunk.getPos().toLong());

                        ((IPOIUnloading) ((IThreadedAnvilChunkStorage) context.tacs()).getPointOfInterestStorage()).c2me$unloadPoi(context.holder().getKey());

                        context.holder().getItem().set(new ChunkState(null, null, null));
                    }
                })
                .doOnError((throwable) -> {
                    try {
                        // SuperChunk: a late cancel (wanted again before the unload finished) puts
                        // the holder back at this status with its partial chunk, so a broken one must
                        // stay broken. Clearing the flag revived it with its failed status's
                        // dependency slot still set by clearDependencies0: the next upgrade to that
                        // status tripped "Duplicate setDependencies call" and the chunk wedged,
                        // neither loading nor broken, hanging any getChunk on it or its neighbours.
                        // It recovers when a later unload completes and the holder is recreated.
                        if (cancellable.isCancelled() && throwable instanceof CancellationException) {
                            return;
                        }
                        if ((context.holder().getFlags() & ItemHolder.FLAG_BROKEN) != 0) {
                            LOGGER.warn("Broken chunk {} was unloaded", context.holder().getKey());
                            context.holder().clearFlag(ItemHolder.FLAG_BROKEN);
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });
    }

    private @NonNull Completable asyncSave(ChunkMap tacs, ChunkAccess chunk) {
        ((IThreadedAnvilChunkStorage) tacs).getPointOfInterestStorage().flush(chunk.getPos());
        if (!chunk.isUnsaved()) {
            return Completable.complete();
        } else {
            chunk.setUnsaved(false);
            ChunkPos chunkPos = chunk.getPos();

            // Forge ChunkDataEvent.Save compat (upstream C2ME-NeoForge parity): when any
            // listener is registered, prefer the NBT-compound serializer result and post the
            // event on the main thread before writing; when event-free (the common case) keep
            // today's fast path unchanged. Without this, async saves — essentially EVERY save
            // with asyncSerialization=true — never posted ChunkDataEvent.Save at all.
            boolean chunkSaveEventFree = HookCompatibility.isChunkSaveEventFree();

            // SuperChunk: the scope serializes the block entities here, on the main thread, before
            // the error handling below exists. A block entity that throws on save (seen on neo-1.21.1:
            // Iron's Spellbooks' portal frame NPEs on its null level in a proto chunk) used to fail the
            // whole unload and mark the chunk broken. Take the same fallback as a failed async save:
            // vanilla's save logs "Failed to save chunk" with the cause, as it would without SuperChunk,
            // and the unload goes on.
            final AsyncSerializationManager.Scope scope;
            try {
                scope = new AsyncSerializationManager.Scope(chunk, ((IThreadedAnvilChunkStorage) tacs).getWorld());
            } catch (Exception e) {
                LOGGER.error("Failed to save chunk {},{} asynchronously, falling back to sync saving", chunkPos.x, chunkPos.z, e);
                chunk.setUnsaved(true);
                ((IThreadedAnvilChunkStorage) tacs).invokeSave(chunk);
                return Completable.complete();
            }
            return Single.fromCallable(() -> {
                        try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(((IThreadedAnvilChunkStorage) tacs).getWorld(), chunk.getPos(), this, false))) {
                            scope.open();
                            AsyncSerializationManager.push(scope);
                            try {
                                return SerializerAccess.getSerializer().serialize(((IThreadedAnvilChunkStorage) tacs).getWorld(), chunk, !chunkSaveEventFree);
                            } finally {
                                AsyncSerializationManager.pop(scope);
                            }
                        }
                    })
                    .subscribeOn(Schedulers.from(GlobalExecutors.prioritizedScheduler.executor(16) /* boost priority as we are serializing an unloaded chunk */))
                    .flatMapCompletable((either) -> {
                        try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(((IThreadedAnvilChunkStorage) tacs).getWorld(), chunk.getPos(), this, false))) {
                            if (either.left().isPresent()) {
                                if (chunkSaveEventFree) {
                                    tacs.write(chunkPos, either.left().get());
                                    return Completable.complete();
                                } else {
                                    CompoundTag nbt = either.left().get();
                                    // mirror the 1.20.1 Forge patch's event shape (src1201 ChunkMap:830)
                                    return Completable.fromRunnable(() -> MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk,
                                                    chunk.getWorldForge() != null ? chunk.getWorldForge() : ((IThreadedAnvilChunkStorage) tacs).getWorld(), nbt)))
                                            .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) tacs).getMainThreadExecutor()))
                                            .doOnComplete(() -> tacs.write(chunkPos, nbt));
                                }
                            } else {
                                if (!chunkSaveEventFree) {
                                    LOGGER.warn("Chunk serializer returned byte[] for {} directly despite not chunkSaveEventFree, events will not be called", chunkPos);
                                }
                                ((IDirectStorage) ((IVersionedChunkStorage) tacs).getWorker()).setRawChunkData(chunkPos, either.right().get());
                                return Completable.complete();
                            }
                        }
                    })
                    .onErrorResumeNext(throwable -> {
                        LOGGER.error("Failed to save chunk {},{} asynchronously, falling back to sync saving", chunkPos.x, chunkPos.z, throwable);
                        return Completable
                                .fromRunnable(() -> {
                                    chunk.setUnsaved(true);
                                    ((IThreadedAnvilChunkStorage) tacs).invokeSave(chunk);
                                })
                                .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) tacs).getMainThreadExecutor()));
                    });
        }
    }

    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependenciesToRemove(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        return EMPTY_DEPENDENCIES;
    }

    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependenciesToAdd(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        return EMPTY_DEPENDENCIES;
    }
}
