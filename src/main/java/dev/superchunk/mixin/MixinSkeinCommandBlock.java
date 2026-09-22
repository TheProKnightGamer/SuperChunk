package dev.superchunk.mixin;

import dev.superchunk.compat.SkeinCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs a command block and its conditional chain after all dimension workers finish. */
@Mixin(CommandBlock.class)
public abstract class MixinSkeinCommandBlock {
    @Shadow
    protected abstract void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random);

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1)
    private void superchunk$deferCommandTick(BlockState state, ServerLevel level, BlockPos pos,
                                             RandomSource random, CallbackInfo ci) {
        if (!SkeinCompat.isDimensionPhaseActive()) {
            return;
        }
        // Selectors can read other levels before teleport/removal callbacks run. Defer
        // the whole tick so selection, success counts, conditional chains and the
        // repeating-block reschedule remain one transaction on the server thread.
        BlockPos immutablePos = pos.immutable();
        if (SkeinCompat.deferCommand(level, () -> this.tick(state, level, immutablePos, random))) {
            ci.cancel();
        }
    }
}
