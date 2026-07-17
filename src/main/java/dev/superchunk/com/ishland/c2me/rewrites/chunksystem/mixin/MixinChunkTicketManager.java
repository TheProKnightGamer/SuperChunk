package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.Config;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.TicketDistanceLevelPropagatorExtension;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ChunkMap;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.Executor;

@Mixin(value = DistanceManager.class, priority = 1051)
public abstract class MixinChunkTicketManager {

    @Shadow
    protected abstract @Nullable ChunkHolder updateChunkScheduling(long pos, int level, @Nullable ChunkHolder holder, int i);

    @Shadow
    @Final
    private DistanceManager.ChunkTicketTracker ticketTracker;

    @Dynamic
    @TargetHandler(
            mixin = "dev.superchunk.com.ishland.vmp.mixins.ticketsystem.ticketpropagator.MixinChunkTicketManager",
            name = "tickTickets"
    )
    @Redirect(method = "@MixinSquared:Handler", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getLevel()I"), require = 0)
    private int fakeLevel(ChunkHolder instance) {
        return Integer.MAX_VALUE;
    }

    @Dynamic
    @TargetHandler(
            mixin = "dev.superchunk.com.ishland.vmp.mixins.ticketsystem.ticketpropagator.MixinChunkTicketManager",
            name = "tickTickets"
    )
    @Redirect(method = "@MixinSquared:Handler", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/DistanceManager;getChunkHolder(J)Lnet/minecraft/server/level/ChunkHolder;"), require = 0)
    private ChunkHolder fakeLevel(DistanceManager instance, long l) {
        return null;
    }

    @Dynamic
    @TargetHandler(
            mixin = "dev.superchunk.com.ishland.vmp.mixins.ticketsystem.ticketpropagator.MixinChunkTicketManager",
            name = "tickTickets"
    )
    @Redirect(method = "@MixinSquared:Handler", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ChunkLevel;INACCESSIBLE:I", opcode = Opcodes.GETSTATIC, ordinal = 0), require = 0)
    private int fakeLevel() {
        return Integer.MAX_VALUE - 1;
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW", target = "(Ljava/util/List;Ljava/util/concurrent/Executor;I)Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;"))
    private ChunkTaskPriorityQueueSorter syncPlayerTickets(List actors, Executor executor, int maxQueues, Operation<ChunkTaskPriorityQueueSorter> original) {
        if (Config.syncPlayerTickets) {
            return original.call(actors, (Executor) Runnable::run, maxQueues); // improve player ticket consistency
        } else {
            return original.call(actors, executor, maxQueues);
        }
    }

    @Inject(method = "runAllUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager$ChunkTicketTracker;runDistanceUpdates(I)I", shift = At.Shift.AFTER))
    private void postTicketPropagator(ChunkMap chunkLoadingManager, CallbackInfoReturnable<Boolean> cir) {
        if (this.ticketTracker != null) { // ignore if replaced
            Long2IntLinkedOpenHashMap updates = ((TicketDistanceLevelPropagatorExtension) this.ticketTracker).c2me$getTicketLevelUpdates();
            while (!updates.isEmpty()) {
                long pos = updates.firstLongKey();
                int level = updates.removeFirstInt();
                this.updateChunkScheduling(pos, level, null, Integer.MAX_VALUE - 1); // holder and old level is ignored
            }
        }
    }

}
