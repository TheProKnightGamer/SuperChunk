package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.ordering.player_move;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerPlayNetworkHandler {

    @Shadow public ServerPlayer player;

//    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V", shift = At.Shift.BEFORE))
//    private void movePacketBeforePlayerMove(CallbackInfo ci) {
//        this.player.level().getChunkSource().move(this.player);
//    }
//
//    @Redirect(
//            method = "handleMovePlayer",
//            slice = @Slice(
//                    from = @At(
//                            value = "INVOKE",
//                            target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
//                    )
//            ),
//            at = @At(
//                    value = "INVOKE",
//                    target = "Lnet/minecraft/server/level/ServerChunkCache;move(Lnet/minecraft/server/level/ServerPlayer;)V"
//            )
//    )
//    private void movePacketAvoidDoubleUpdate(ServerChunkCache instance, ServerPlayer player) {
//        // no-op
//    }

}
