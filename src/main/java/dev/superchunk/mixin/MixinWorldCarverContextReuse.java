package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.ScMutableFunctionContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk: reuse a thread-local {@link DensityFunction.FunctionContext} in
 * {@code WorldCarver.getCarveState} instead of allocating a fresh
 * {@code DensityFunction.SinglePointContext} for <b>every carved block</b>.
 *
 * <p>Vanilla: {@code aquifer.computeSubstance(new SinglePointContext(x, y, z), 0.0)}. For the vanilla
 * cave/canyon carvers {@code lavaLevel} sits at world-bottom, so essentially every replaceable
 * carved block takes this branch — order 10^4-10^5 throwaway 3-int records per fully-carved chunk.
 * (This is carver-unique: the noise stage passes the {@code NoiseChunk} itself as the context, with
 * no per-block allocation.)
 *
 * <p><b>Bit-parity.</b> {@code computeSubstance} (C2ME's {@code @Overwrite}) reads only
 * {@code blockX/Y/Z()} and forwards the reference synchronously to its density-function evals; it
 * never stores it. So a single {@link ScMutableFunctionContext} whose coordinates are set on the
 * line before the call is byte-identical to a fresh {@code SinglePointContext} — both also share the
 * same default {@code getBlender()}. The context is <b>thread-local</b>: {@code WorldCarver.CAVE}/
 * {@code CANYON} are static singletons shared across worldgen worker threads.
 *
 * <p><b>Mechanism.</b> Two composing {@code @WrapOperation}s (they coexist with
 * {@code MixinWorldCarverLavaState}'s wrap on {@code createLegacyBlock} — all three target distinct
 * instructions): the {@code NEW} wrap stashes the coordinates and skips the allocation (returns
 * {@code null}); the {@code computeSubstance} wrap substitutes the reused context. The wraps are
 * decoupled by a {@code null} discriminator — if the (format-sensitive) {@code NEW} wrap does not
 * apply, the real {@code SinglePointContext} flows through and is used unchanged, so the result is
 * always correct (only the allocation saving is lost). Runtime kill switch
 * {@code -Dsuperchunk.worldgen.carverContextReuse=false}.
 */
@Mixin(WorldCarver.class)
public abstract class MixinWorldCarverContextReuse {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.carverContextReuse", "true"));

    @Unique
    private static final ThreadLocal<ScMutableFunctionContext> superchunk$carveCtx =
            ThreadLocal.withInitial(ScMutableFunctionContext::new);

    @WrapOperation(
            method = "getCarveState",
            at = @At(value = "NEW",
                    target = "(III)Lnet/minecraft/world/level/levelgen/DensityFunction$SinglePointContext;"),
            require = 0)
    private DensityFunction.SinglePointContext superchunk$stashCarveCtx(
            int x, int y, int z, Operation<DensityFunction.SinglePointContext> original) {
        if (!SUPERCHUNK$ENABLED) {
            return original.call(x, y, z);
        }
        superchunk$carveCtx.get().set(x, y, z);
        return null; // allocation skipped; the reused context is supplied at the computeSubstance call below
    }

    @WrapOperation(
            method = "getCarveState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/Aquifer;computeSubstance("
                            + "Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;D)"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 1)
    private BlockState superchunk$reuseCarveCtx(
            Aquifer aquifer, DensityFunction.FunctionContext ctx, double density, Operation<BlockState> original) {
        if (!SUPERCHUNK$ENABLED) {
            return original.call(aquifer, ctx, density);
        }
        // ctx == null iff the NEW wrap fired (allocation skipped) -> supply the reused context;
        // otherwise the real SinglePointContext flows through unchanged (still bit-correct).
        return original.call(aquifer, ctx != null ? ctx : superchunk$carveCtx.get(), density);
    }
}
