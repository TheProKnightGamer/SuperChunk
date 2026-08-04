package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.feature.SpringFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk: reuse one thread-local {@code MutableBlockPos} for the neighbour reads in
 * {@code SpringFeature.place} instead of allocating a fresh immutable {@code BlockPos} per neighbour.
 *
 * <p>Vanilla probes up to ~12 neighbours — {@code origin.above()}, {@code .below()} (×3),
 * {@code .west()/.east()/.north()/.south()} (×2 each, once for the rock count and once for the hole
 * count) — each an immutable {@code BlockPos} allocated purely as a {@code getBlockState}/
 * {@code isEmptyBlock} argument. Springs run at meaningful counts per chunk (~25 water + ~20 lava
 * overworld), so ~hundreds of throwaway {@code BlockPos} per chunk.
 *
 * <p><b>Bit-parity.</b> There is no RNG in {@code place}. Every neighbour position is consumed
 * synchronously by the very next {@code getBlockState}/{@code isEmptyBlock} call (the extracted
 * {@code BlockState} is a separate object), and no two neighbour positions are ever live at once, so a
 * single mutable set fresh at each access is identical. The {@code origin} used for the final
 * {@code setBlock}/{@code scheduleTick} comes straight from the context and is never wrapped, so it
 * stays immutable. {@code setWithOffset(pos, dir)} offsets by exactly 1, matching {@code above()} etc.
 * Thread-local (worldgen runs on many threads). Kill switch
 * {@code -Dsuperchunk.worldgen.springScratchReuse=false}.
 */
@Mixin(SpringFeature.class)
public abstract class MixinSpringFeatureScratch {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.springScratchReuse", "true"));

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> superchunk$neighbour =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private BlockPos superchunk$offset(BlockPos receiver, Direction dir, Operation<BlockPos> original) {
        return SUPERCHUNK$ENABLED ? superchunk$neighbour.get().setWithOffset(receiver, dir) : original.call(receiver);
    }

    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;above()Lnet/minecraft/core/BlockPos;"), require = 0)
    private BlockPos superchunk$above(BlockPos receiver, Operation<BlockPos> original) {
        return superchunk$offset(receiver, Direction.UP, original);
    }

    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;below()Lnet/minecraft/core/BlockPos;"), require = 0)
    private BlockPos superchunk$below(BlockPos receiver, Operation<BlockPos> original) {
        return superchunk$offset(receiver, Direction.DOWN, original);
    }

    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;west()Lnet/minecraft/core/BlockPos;"), require = 0)
    private BlockPos superchunk$west(BlockPos receiver, Operation<BlockPos> original) {
        return superchunk$offset(receiver, Direction.WEST, original);
    }

    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;east()Lnet/minecraft/core/BlockPos;"), require = 0)
    private BlockPos superchunk$east(BlockPos receiver, Operation<BlockPos> original) {
        return superchunk$offset(receiver, Direction.EAST, original);
    }

    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;north()Lnet/minecraft/core/BlockPos;"), require = 0)
    private BlockPos superchunk$north(BlockPos receiver, Operation<BlockPos> original) {
        return superchunk$offset(receiver, Direction.NORTH, original);
    }

    @WrapOperation(method = "place", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;south()Lnet/minecraft/core/BlockPos;"), require = 0)
    private BlockPos superchunk$south(BlockPos receiver, Operation<BlockPos> original) {
        return superchunk$offset(receiver, Direction.SOUTH, original);
    }
}
