package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.Deferred;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDisk;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDiskAsync;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessible;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerAccessibleChunkSending;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerBlockTicking;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.ServerEntityTicking;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate;
import dev.superchunk.com.ishland.flowsched.scheduler.Cancellable;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemStatus;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import io.reactivex.rxjava3.core.Completable;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.stream.IntStream;

/**
 * Represents the status of a chunk in the chunk system.
 *
 * @implNote Subclasses should be immutable and should not have any non-final fields.
 */
public abstract class NewChunkStatus implements ItemStatus<ChunkPos, ChunkState, ChunkLoadingContext> {

    public static final NewChunkStatus[] ALL_STATUSES;

    public static final NewChunkStatus NEW;
    public static final NewChunkStatus DEFERRED;
    public static final NewChunkStatus DISK;
    private static final NewChunkStatus[] VANILLA_WORLDGEN_PIPELINE;
    public static final NewChunkStatus SERVER_ACCESSIBLE;
    public static final NewChunkStatus SERVER_ACCESSIBLE_CHUNK_SENDING;
    public static final NewChunkStatus BLOCK_TICKING;
    public static final NewChunkStatus ENTITY_TICKING;
    public static final NewChunkStatus[] vanillaLevelToStatus;

    static {
        ArrayList<NewChunkStatus> statuses = new ArrayList<>();
        NEW = new NewChunkStatus(statuses.size(), ChunkStatus.EMPTY) {
            @Override
            public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Completable postUpgradeToThis(ChunkLoadingContext context) {
                return Completable.complete();
            }

            @Override
            public Completable preDowngradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
                return Completable.complete();
            }

            @Override
            public Completable downgradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int toVanillaLevel() {
                return ChunkLevel.MAX_LEVEL + 1;
            }

            @Override
            public String toString() {
                return "unloaded";
            }
        };
        statuses.add(NEW);
        DEFERRED = new Deferred(statuses.size());
        statuses.add(DEFERRED);
        DISK = Config.asyncSerialization ? new ReadFromDiskAsync(statuses.size()) : new ReadFromDisk(statuses.size());
        statuses.add(DISK);
        VANILLA_WORLDGEN_PIPELINE = new NewChunkStatus[ChunkStatus.FULL.getIndex() + 1];
        for (ChunkStatus status : ChunkStatus.getStatusList()) {
            if (status == ChunkStatus.EMPTY) {
                VANILLA_WORLDGEN_PIPELINE[status.getIndex()] = DISK;
                continue;
            } else if (status == ChunkStatus.FULL) {
                continue;
            }

            final NewChunkStatus newChunkStatus = new VanillaWorldGenerationDelegate(statuses.size(), status);
            statuses.add(newChunkStatus);
            VANILLA_WORLDGEN_PIPELINE[status.getIndex()] = newChunkStatus;
        }
        SERVER_ACCESSIBLE = new ServerAccessible(statuses.size());
        statuses.add(SERVER_ACCESSIBLE);
        VANILLA_WORLDGEN_PIPELINE[ChunkStatus.FULL.getIndex()] = SERVER_ACCESSIBLE;
        SERVER_ACCESSIBLE_CHUNK_SENDING = new ServerAccessibleChunkSending(statuses.size());
        statuses.add(SERVER_ACCESSIBLE_CHUNK_SENDING);
        BLOCK_TICKING = new ServerBlockTicking(statuses.size());
        statuses.add(BLOCK_TICKING);
        ENTITY_TICKING = new ServerEntityTicking(statuses.size());
        statuses.add(ENTITY_TICKING);

        vanillaLevelToStatus = IntStream.range(0, ChunkLevel.MAX_LEVEL + 2)
                .mapToObj(NewChunkStatus::fromVanillaStatus0).toArray(NewChunkStatus[]::new);
        ALL_STATUSES = statuses.toArray(NewChunkStatus[]::new);
    }

    private static NewChunkStatus fromVanillaStatus0(int level) {
        return switch (ChunkLevel.fullStatus(level)) {
            case INACCESSIBLE -> {
                final ChunkStatus vanillaStatus = ChunkLevel.generationStatus(level);
                if (vanillaStatus == null || vanillaStatus == ChunkStatus.EMPTY) {
                    if (level > ChunkLevel.MAX_LEVEL) {
                        yield NEW;
                    } else {
                        yield DISK;
                    }
                } else {
                    yield VANILLA_WORLDGEN_PIPELINE[vanillaStatus.getIndex()];
                }
            }
            case FULL -> SERVER_ACCESSIBLE;
            case BLOCK_TICKING -> BLOCK_TICKING;
            case ENTITY_TICKING -> ENTITY_TICKING;
        };
    }

    public static NewChunkStatus fromVanillaLevel(int level) {
        if (vanillaLevelToStatus == null) { // special case for static initialization
            return fromVanillaStatus0(level);
        }
        return vanillaLevelToStatus[Mth.clamp(level, 0, vanillaLevelToStatus.length - 1)];
    }

    public static NewChunkStatus fromVanillaStatus(ChunkStatus status) {
        if (status == null) {
            return NEW;
        } else {
            return VANILLA_WORLDGEN_PIPELINE[status.getIndex()];
        }
    }

    private final int ordinal;
    private final ChunkStatus effectiveVanillaStatus;

    protected NewChunkStatus(int ordinal, ChunkStatus effectiveVanillaStatus) {
        this.ordinal = ordinal;
        this.effectiveVanillaStatus = effectiveVanillaStatus;
    }

    @Override
    public ItemStatus<ChunkPos, ChunkState, ChunkLoadingContext>[] getAllStatuses() {
        return ALL_STATUSES;
    }

    @Override
    public int ordinal() {
        return this.ordinal;
    }

    public ChunkStatus getEffectiveVanillaStatus() {
        return this.effectiveVanillaStatus;
    }

    public FullChunkStatus toChunkLevelType() {
        if (this.ordinal() < SERVER_ACCESSIBLE.ordinal()) return FullChunkStatus.INACCESSIBLE;
        if (this.ordinal() <= SERVER_ACCESSIBLE.ordinal()) return FullChunkStatus.FULL;
        if (this.ordinal() <= BLOCK_TICKING.ordinal()) return FullChunkStatus.BLOCK_TICKING;
        if (this.ordinal() <= ENTITY_TICKING.ordinal()) return FullChunkStatus.ENTITY_TICKING;
        throw new IncompatibleClassChangeError();
    }

    public int toVanillaLevel() {
        final FullChunkStatus chunkLevelType = this.toChunkLevelType();
        if (chunkLevelType == FullChunkStatus.INACCESSIBLE) {
            return ChunkLevel.byStatus(this.getEffectiveVanillaStatus());
        } else {
            return ChunkLevel.byStatus(chunkLevelType);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependencies(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        return EMPTY_DEPENDENCIES;
    }

    protected static KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] relativeToAbsoluteDependencies(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder, KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] relativeDependencies) {
        if (relativeDependencies.length == 0) return EMPTY_DEPENDENCIES;
        final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] dependencies = new KeyStatusPair[relativeDependencies.length];
        for (int i = 0; i < relativeDependencies.length; i++) {
            final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext> pair = relativeDependencies[i];
            dependencies[i] = new KeyStatusPair<>(new ChunkPos(pair.key().x + holder.getKey().x, pair.key().z + holder.getKey().z), pair.status());
        }
        return dependencies;
    }
}
