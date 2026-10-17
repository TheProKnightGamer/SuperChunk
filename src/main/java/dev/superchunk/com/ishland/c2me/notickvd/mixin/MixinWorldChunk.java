package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelChunk.class)
public class MixinWorldChunk {

    @ModifyArg(method = "postProcessGeneration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private int notifyListenersWhenPostProcessing(int flags) {
//        if (true) return flags;
        flags &= ~Block.UPDATE_INVISIBLE; // clear NO_REDRAW
        flags |= Block.UPDATE_CLIENTS; // set NOTIFY_LISTENERS
        return flags;
    }

}
