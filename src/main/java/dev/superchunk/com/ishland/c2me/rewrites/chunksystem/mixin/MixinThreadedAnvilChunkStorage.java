package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge 1.20.1 deltas (plan §1.2 row 1): the {@code updateChunkScheduling} @Overwrite deletes the
 * FORGE PATCH {@code ForgeEventFactory.fireChunkTicketLevelUpdated(this.level, pos, oldLevel,
 * newLevel, holder)} (src1201 ChunkMap:401) — MANDATORY re-fire in the overwritten body, or
 * ticket-level-listening mods (chunk loaders etc.) silently break. getUpdating/getVisible are
 * {@code protected} in 1.20.1 — the forge AT widens both (m_140174_/m_140327_) so the public
 * overwrites bind. {@code getChunkType} descriptor uses the 1.20.1 inner-enum form. 1.21's
 * {@code shouldDelayShutdown} note stays as {@code hasWork} (same as neo).
 */
@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage implements IChunkSystemAccess {

    @Shadow @Final ServerLevel level;
    private TheChunkSystem newSystem;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        newSystem = new TheChunkSystem(
                (ChunkMap) (Object) this
        );
    }

    /**
     * @author ishland
     * @reason replace chunk system
     */
    @Overwrite
    @Nullable
    ChunkHolder updateChunkScheduling(long pos, int newLevel, @Nullable ChunkHolder holder, int oldLevel) {
        final ChunkHolder result = this.newSystem.vanillaIf$setLevel(pos, newLevel);
        // FORGE PATCH re-fire (src1201 ChunkMap:401): the patched vanilla body posts
        // ChunkTicketLevelUpdatedEvent via this exact factory call; fire with the holder the
        // chunk system returned (post vanillaIf$setLevel), matching the patch's post-update fire.
        // Vanilla returns before the fire when neither level is a loaded one; so does this.
        if (net.minecraft.server.level.ChunkLevel.isLoaded(oldLevel) || net.minecraft.server.level.ChunkLevel.isLoaded(newLevel)) {
            net.minecraftforge.event.ForgeEventFactory.fireChunkTicketLevelUpdated(this.level, pos, oldLevel, newLevel, result);
        }
        return result;
    }

    /**
     * @author ishland
     * @reason replace chunk system
     */
    @Overwrite
    @Nullable
    public ChunkHolder getUpdatingChunkIfPresent(long pos) {
        final ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder = this.newSystem.getHolder(new ChunkPos(pos));
        if (holder != null) {
            synchronized (holder) {
                if (!holder.isOpen()) {
                    return null;
                } else {
                    return holder.getUserData().get();
                }
            }
        } else {
            return null;
        }
    }

    /**
     * @author ishland
     * @reason replace chunk system
     */
    @Overwrite
    @Nullable
    public ChunkHolder getVisibleChunkIfPresent(long pos) {
        return this.getUpdatingChunkIfPresent(pos);
    }

    @Redirect(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkStatus;getChunkType()Lnet/minecraft/world/level/chunk/ChunkStatus$ChunkType;"), require = 0)
    private ChunkStatus.ChunkType alwaysSaveChunk(ChunkStatus instance) {
        return ChunkStatus.ChunkType.LEVELCHUNK;
    }

    /**
     * SuperChunk: a flush save (/save-all flush, shutdown) also writes the full chunks that left the
     * holder map on their way out, before the storage is flushed ({@link TheChunkSystem#saveFullChunksOutsideHolderMap}).
     */
    @Inject(method = "saveAllChunks(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;flushWorker()V"))
    private void superchunk$saveChunksLeavingHolderMap(boolean flush, CallbackInfo ci) {
        this.newSystem.saveFullChunksOutsideHolderMap();
    }

    // 1.20.1 (like 1.21.1) has no `shouldDelayShutdown`; the closest is `hasWork()`.
    @ModifyReturnValue(method = "hasWork", at = @At("RETURN"))
    private boolean delayShutdown(boolean original) {
        return original || this.newSystem.itemCount() != 0;
    }

    @Override
    public TheChunkSystem c2me$getTheChunkSystem() {
        return this.newSystem;
    }
}
