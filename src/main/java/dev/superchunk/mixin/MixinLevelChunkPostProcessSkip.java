package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.PostProcessNeighbourShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

/**
 * SuperChunk: skip the provably-no-op {@code Block.updateFromNeighbourShapes} calls in
 * {@code LevelChunk.postProcessGeneration} — see {@link PostProcessNeighbourShapes} for the
 * bit-exactness argument.
 *
 * <p><b>Gating</b> (load-time, in {@code SuperChunkMixinPlugin.shouldApplyMixin}):
 * <ul>
 *   <li>Not applied when <b>Bye?Pregen!</b> is installed — its {@code LevelChunkPostProcessMixin}
 *       already {@code @Redirect}s this exact instruction, so applying ours too would be a
 *       duplicate injection into the same call site. SuperChunk defers to Bye Pregen there.</li>
 *   <li>Kill switch {@code -Dsuperchunk.worldgen.postProcessSkipNoOp=false} (default {@code true}).</li>
 * </ul>
 * The helper additionally offers an opt-in Tier-2 census-gated whitelist
 * ({@code -Dsuperchunk.worldgen.postProcessSkipWhitelist=true}, default off) — see
 * {@link PostProcessNeighbourShapes}.
 * Because the gate is load-time, when the mixin IS applied it always optimises (no per-call branch);
 * when gated off the method is byte-for-byte vanilla.
 */
@Mixin(LevelChunk.class)
public abstract class MixinLevelChunkPostProcessSkip {

    @WrapOperation(
            method = "postProcessGeneration",
            at = @org.spongepowered.asm.mixin.injection.At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;updateFromNeighbourShapes(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState superchunk$skipNoOpNeighbourShapes(BlockState state, LevelAccessor level, BlockPos pos,
                                                          Operation<BlockState> original) {
        // Load-time gated: when applied we always take the fast path. `original` (the verbatim
        // vanilla call) is intentionally not invoked — the helper returns the identical result.
        return PostProcessNeighbourShapes.updateFromNeighbourShapes(state, level, pos);
    }
}
