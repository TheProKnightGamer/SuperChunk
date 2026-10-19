package dev.superchunk.net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.world.block_entity_ticking.support_cache;

import dev.superchunk.net.caffeinemc.mods.lithium.common.world.blockentity.SupportCache;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements SupportCache {
    @Shadow
    public abstract BlockEntityType<?> getType();

    @Shadow
    public abstract BlockState getBlockState();

    // FORGE 1.20.1 PORT: tested on first use, not in the constructor as upstream does. 1.21's
    // constructor already calls isValid; 1.20.1's never does, and mods build block entities while
    // mods are still being constructed (1wholibs creates the item renderer, which makes a shulker
    // box), before other mods' isValid hooks can answer: Spawn's throws there and fails the launch.
    // 0 = not tested since the state was last set, 1 = supported, 2 = not supported.
    @Unique
    private byte supportTestResult;

    @Inject(method = "setBlockState(Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("RETURN"))
    private void updateSupportCache(BlockState cachedState, CallbackInfo ci) {
        this.supportTestResult = 0;
    }

    @Override
    public boolean lithium$isSupported() {
        byte result = this.supportTestResult;
        if (result == 0) {
            result = this.getType().isValid(this.getBlockState()) ? (byte) 1 : (byte) 2;
            this.supportTestResult = result;
        }
        return result == 1;
    }
}
