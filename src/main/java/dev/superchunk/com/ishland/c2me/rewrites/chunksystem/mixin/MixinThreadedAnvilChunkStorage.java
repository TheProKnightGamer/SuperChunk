package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
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
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    public ChunkHolder updateChunkScheduling(long pos, int level, @Nullable ChunkHolder holder, int i) {
        return this.newSystem.vanillaIf$setLevel(pos, level);
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

//    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/poi/PoiManager;close()V", shift = At.Shift.AFTER))
//    private void closeNewSystem(CallbackInfo ci) {
//        this.newSystem.shutdown();
//    }

    @Redirect(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/status/ChunkStatus;getChunkType()Lnet/minecraft/world/level/chunk/status/ChunkType;"), require = 0)
    private ChunkType alwaysSaveChunk(ChunkStatus instance) {
        return ChunkType.LEVELCHUNK;
    }

    // NeoForge port: 1.21.1 ChunkMap has no `shouldDelayShutdown`; the closest is `hasWork()`.
    @ModifyReturnValue(method = "hasWork", at = @At("RETURN"))
    private boolean delayShutdown(boolean original) {
        return original || this.newSystem.itemCount() != 0;
    }

    @Override
    public TheChunkSystem c2me$getTheChunkSystem() {
        return this.newSystem;
    }
}
