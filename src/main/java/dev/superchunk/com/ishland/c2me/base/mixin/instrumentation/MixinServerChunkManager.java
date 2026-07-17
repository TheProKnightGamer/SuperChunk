package dev.superchunk.com.ishland.c2me.base.mixin.instrumentation;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.ISyncLoadManager;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.SyncLoadWork;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BooleanSupplier;

@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkManager implements ISyncLoadManager {

    @Shadow
    @Final
    Thread mainThread;

    @Shadow
    protected abstract boolean chunkAbsent(@Nullable ChunkHolder holder, int maxLevel);

    @Shadow
    @Nullable
    protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);

    @Shadow @Final public ChunkMap chunkMap;
    @Shadow @Final ServerLevel level;
    @Unique
    private volatile ChunkPos currentSyncLoadChunk = null;
    @Unique
    private volatile long syncLoadNanos = 0;

    @Dynamic
    @WrapOperation(method = {
            "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            },
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;managedBlock(Ljava/util/function/BooleanSupplier;)V"), require = 0)
    private void instrumentAwaitChunk(ServerChunkCache.MainThreadExecutor instance, BooleanSupplier stopCondition, Operation<Void> original, int x, int z, ChunkStatus leastStatus, boolean create) {
        if (Thread.currentThread() != this.mainThread || stopCondition.getAsBoolean()) return;

        this.currentSyncLoadChunk = new ChunkPos(x, z);
        syncLoadNanos = System.nanoTime();
        ((IVanillaChunkManager) this.chunkMap).c2me$getSchedulingManager().setCurrentSyncLoad(this.currentSyncLoadChunk);
        try (var ignored = ThreadInstrumentation.getCurrent().begin(new SyncLoadWork(this.level, new ChunkPos(x, z), leastStatus, create))) {
            original.call(instance, stopCondition);
        } finally {
            ((IVanillaChunkManager) this.chunkMap).c2me$getSchedulingManager().setCurrentSyncLoad(null);
            this.currentSyncLoadChunk = null;
        }
    }

    @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private ChunkAccess instrumentGetChunk(int x, int z, ChunkStatus leastStatus, boolean create, Operation<ChunkAccess> original) {
        if (Thread.currentThread() != this.mainThread) {
            try (var ignored = ThreadInstrumentation.getCurrent().begin(new SyncLoadWork(this.level, new ChunkPos(x, z), leastStatus, create))) {
                return original.call(x, z, leastStatus, create);
            }
        } else {
            return original.call(x, z, leastStatus, create);
        }
    }

    @Override
    public ChunkPos getCurrentSyncLoad() {
        return this.currentSyncLoadChunk;
    }
}
