package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.base.common.util.InvokingExecutorService;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ExecutorService;

@Mixin(ChunkGenerator.class)
public class MixinChunkGenerator {

    @Redirect(method = "createBiomes", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;backgroundExecutor()Ljava/util/concurrent/ExecutorService;"), require = 0)
    private ExecutorService redirectBiomeExecutor() {
        return InvokingExecutorService.INSTANCE;
    }

}
