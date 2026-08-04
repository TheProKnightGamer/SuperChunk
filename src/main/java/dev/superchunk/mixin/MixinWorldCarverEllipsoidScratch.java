package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk: reuse thread-local scratch in {@code WorldCarver.carveEllipsoid} instead of allocating
 * two {@code BlockPos.MutableBlockPos} per ellipsoid and a {@code MutableBoolean} per carved column.
 *
 * <p>Vanilla allocates, per {@code carveEllipsoid} call, two {@code MutableBlockPos} (the loop cursor
 * and the neighbour {@code checkPos}); and, per in-radius (x,z) column, a fresh
 * {@code new MutableBoolean(false)} ({@code reachedSurface}). A carve is thousands of ellipsoids, each
 * many columns — order 10^3-10^4 throwaway objects per target chunk.
 *
 * <p><b>Bit-parity.</b> All three are pure scratch that vanilla writes before it reads: the cursor is
 * {@code .set(x,y,z)} before {@code carveBlock}; {@code checkPos} is {@code setWithOffset(...)} before
 * being read inside {@code carveBlock}; {@code reachedSurface} starts {@code false} each column (the
 * constructor arg) and is only {@code setTrue()}/{@code isTrue()} within that column. Reusing a
 * single instance of each — the cursor/checkPos returned as-is (overwritten before use), the boolean
 * reset to its constructor argument on each column — is identical. Everything is <b>thread-local</b>
 * ({@code WorldCarver.CAVE}/{@code CANYON} are static singletons; {@code carveEllipsoid} is not
 * re-entrant, so one instance per thread suffices). Runtime kill switch
 * {@code -Dsuperchunk.worldgen.carverScratchReuse=false}.
 */
@Mixin(WorldCarver.class)
public abstract class MixinWorldCarverEllipsoidScratch {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.carverScratchReuse", "true"));

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> superchunk$cursor =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<BlockPos.MutableBlockPos> superchunk$checkPos =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    @Unique
    private static final ThreadLocal<MutableBoolean> superchunk$reachedSurface =
            ThreadLocal.withInitial(MutableBoolean::new);

    @WrapOperation(
            method = "carveEllipsoid",
            at = @At(value = "NEW", target = "net/minecraft/core/BlockPos$MutableBlockPos", ordinal = 0),
            require = 0)
    private BlockPos.MutableBlockPos superchunk$reuseCursor(Operation<BlockPos.MutableBlockPos> original) {
        return SUPERCHUNK$ENABLED ? superchunk$cursor.get() : original.call();
    }

    @WrapOperation(
            method = "carveEllipsoid",
            at = @At(value = "NEW", target = "net/minecraft/core/BlockPos$MutableBlockPos", ordinal = 1),
            require = 0)
    private BlockPos.MutableBlockPos superchunk$reuseCheckPos(Operation<BlockPos.MutableBlockPos> original) {
        return SUPERCHUNK$ENABLED ? superchunk$checkPos.get() : original.call();
    }

    @WrapOperation(
            method = "carveEllipsoid",
            at = @At(value = "NEW", target = "(Z)Lorg/apache/commons/lang3/mutable/MutableBoolean;"),
            require = 0)
    private MutableBoolean superchunk$reuseReachedSurface(boolean initial, Operation<MutableBoolean> original) {
        if (!SUPERCHUNK$ENABLED) {
            return original.call(initial);
        }
        MutableBoolean b = superchunk$reachedSurface.get();
        b.setValue(initial); // matches new MutableBoolean(false) — reset per column
        return b;
    }
}
