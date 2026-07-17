package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.IntSupplier;

@Mixin(ThreadedLevelLightEngine.class)
public interface IServerLightingProvider {

    @Invoker("updateChunkStatus")
    void invokeUpdateChunkStatus(ChunkPos pos);

    @Invoker("addTask")
    void invokeEnqueue(int x, int z, IntSupplier completedLevelSupplier, ThreadedLevelLightEngine.TaskType stage, Runnable task);

}
