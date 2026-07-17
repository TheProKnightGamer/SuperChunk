package dev.superchunk.net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.crafter;

import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.net.caffeinemc.mods.lithium.common.block.entity.SetChangedHandlingBlockEntity;
import dev.superchunk.net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity;
import dev.superchunk.net.caffeinemc.mods.lithium.mixin.world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrafterBlockEntity.class)
public class CrafterBlockEntityMixin extends BlockEntity implements SleepingBlockEntity, SetChangedHandlingBlockEntity {

    @Shadow
    private int craftingTicksRemaining;
    @Unique
    private WrappedBlockEntityTickInvokerAccessor tickWrapper = null;
    @Unique
    private TickingBlockEntity sleepingTicker = null;

    public CrafterBlockEntityMixin(BlockEntityType<?> blockEntityType, BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);
    }

    @Override
    public WrappedBlockEntityTickInvokerAccessor lithium$getTickWrapper() {
        return this.tickWrapper;
    }

    @Override
    public void lithium$setTickWrapper(WrappedBlockEntityTickInvokerAccessor tickWrapper) {
        this.tickWrapper = tickWrapper;
        this.lithium$setSleepingTicker(null);
    }

    @Override
    public TickingBlockEntity lithium$getSleepingTicker() {
        return this.sleepingTicker;
    }

    @Override
    public void lithium$setSleepingTicker(TickingBlockEntity sleepingTicker) {
        this.sleepingTicker = sleepingTicker;
    }

    @Inject(method = "serverTick", at = @At("RETURN"))
    private static void checkSleep(CallbackInfo ci, @Local(argsOnly = true) CrafterBlockEntity crafterBlockEntity, @Local int remainingTicks) {
        if (remainingTicks < 0) {
            ((CrafterBlockEntityMixin) (Object) crafterBlockEntity).checkSleep();
        }
    }

    @Unique
    private void checkSleep() {
        if (this.craftingTicksRemaining == 0) {
            this.lithium$startSleeping();
        }
    }

    @Inject(
            method = {
                    "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V",
                    "setCraftingTicksRemaining"
            },
            at = @At("RETURN")
    )
    private void wakeUpAfterRemainingTicksChanged(CallbackInfo ci) {
        if (this.isSleeping() && this.level != null && !this.level.isClientSide) {
            this.wakeUpNow();
        }
    }

    @Override
    public void lithium$handleSetChanged() {
        if (this.isSleeping() && this.level != null && !this.level.isClientSide) {
            this.wakeUpNow();
        }
    }
}
