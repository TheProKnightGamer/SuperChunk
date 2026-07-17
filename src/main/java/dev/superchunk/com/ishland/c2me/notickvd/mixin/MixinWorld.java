package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Level.class)
public class MixinWorld {

    @Shadow @Final public boolean isClientSide;

    @ModifyArg(method = "markAndNotifyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;II)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/FullChunkStatus;isOrAfter(Lnet/minecraft/server/level/FullChunkStatus;)Z"))
    private FullChunkStatus modifyLeastStatus(FullChunkStatus levelType) {
        return levelType.ordinal() > FullChunkStatus.FULL.ordinal() ? FullChunkStatus.FULL : levelType;
    }

}
