package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk surface-build optimisation: write-through per-column block memo on the
 * anonymous {@code BlockColumn} inside {@code SurfaceSystem.buildSurface}
 * ({@code SurfaceSystem$1}).
 *
 * <p><b>Why.</b> The column scan reads every block from surface to minBuildHeight with a
 * ~1.9× amplification: each solid block is read once by the stone-depth LOOKAHEAD
 * (scanning down from the cursor) and again by the MAIN loop when the cursor reaches it;
 * the badlands/frozen-ocean extensions re-read on top. Every read pays the full
 * {@code ProtoChunk.getBlockState} palette decode (~23% of surface CPU). The memo makes
 * each (column, y) decode once.
 *
 * <p><b>Why bit-identical.</b> Within one column, every {@code BlockColumn} read of a
 * given y strictly precedes any write to that y (main scan writes only at the cursor
 * after reading it; the lookahead reads only below the cursor; both extensions
 * read-then-write descending) — and writes go through {@code setBlock}, which
 * write-through-updates the memo with the exact stored state ({@code PalettedContainer}
 * returns the identical canonical reference on re-read; the air-into-empty-section
 * early-return returns the same canonical AIR state a re-read would). Reads/writes are
 * wrapped AROUND the vanilla calls — heightmap updates, fluid post-processing marks and
 * light bookkeeping in {@code setBlockState} all run unchanged.
 *
 * <p><b>Safety gates.</b> Exact-class {@code ProtoChunk} only (excludes
 * {@code ImposterProtoChunk} and modded chunk classes), and no below-zero retrogen
 * (whose generation height-accessor differs from the chunk's). Column switch is detected
 * by (x,z) and invalidates via epoch bump (no per-column array clearing). The only
 * theoretical exposure is a modded {@code RuleSource} writing to the chunk directly
 * (bypassing the BlockColumn) mid-column — no known mod does this; kill-switch:
 * {@code -Dsuperchunk.worldgen.surfaceColumnMemo=false}.
 */
@Mixin(targets = "net/minecraft/world/level/levelgen/SurfaceSystem$1")
public abstract class MixinSurfaceColumnMemo {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.surfaceColumnMemo", "true"));
    /** Lockstep verify: every memo HIT is re-read from the chunk and compared by reference. */
    @Unique
    private static final boolean SUPERCHUNK$VERIFY =
            Boolean.getBoolean("superchunk.worldgen.surfaceColumnMemo.verify");
    @Unique
    private static final java.util.concurrent.atomic.AtomicLong SUPERCHUNK$HITS =
            new java.util.concurrent.atomic.AtomicLong();
    @Unique
    private static final java.util.concurrent.atomic.AtomicLong SUPERCHUNK$MISMATCHES =
            new java.util.concurrent.atomic.AtomicLong();

    @Unique
    private ChunkAccess superchunk$chunk;
    @Unique
    private BlockState[] superchunk$memo;
    @Unique
    private int[] superchunk$epochs;
    @Unique
    private int superchunk$epoch;
    @Unique
    private int superchunk$minBuild;
    @Unique
    private int superchunk$x = Integer.MIN_VALUE;
    @Unique
    private int superchunk$z = Integer.MIN_VALUE;
    @Unique
    private boolean superchunk$usable;

    @Unique
    private void superchunk$initFor(ChunkAccess chunk) {
        this.superchunk$chunk = chunk;
        this.superchunk$usable = chunk.getClass() == ProtoChunk.class
                && ((ProtoChunk) chunk).getBelowZeroRetrogen() == null;
        if (this.superchunk$usable) {
            this.superchunk$minBuild = chunk.getMinBuildHeight();
            int h = chunk.getHeight();
            if (this.superchunk$memo == null || this.superchunk$memo.length != h) {
                this.superchunk$memo = new BlockState[h];
                this.superchunk$epochs = new int[h];
            }
            // epoch is monotonic and NEVER reset: stale epochs[] entries from any earlier
            // chunk/column can never equal a future epoch (review hardening)
            this.superchunk$x = Integer.MIN_VALUE;
            this.superchunk$z = Integer.MIN_VALUE;
        }
    }

    @WrapOperation(
            method = "getBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private BlockState superchunk$memoGet(ChunkAccess chunk, BlockPos pos, Operation<BlockState> original) {
        if (!SUPERCHUNK$ENABLED) {
            return original.call(chunk, pos);
        }
        if (chunk != this.superchunk$chunk) {
            this.superchunk$initFor(chunk);
        }
        if (!this.superchunk$usable) {
            return original.call(chunk, pos);
        }
        int x = pos.getX();
        int z = pos.getZ();
        if (x != this.superchunk$x || z != this.superchunk$z) {
            this.superchunk$x = x;
            this.superchunk$z = z;
            this.superchunk$epoch++;
        }
        int idx = pos.getY() - this.superchunk$minBuild;
        BlockState[] memo = this.superchunk$memo;
        if (idx < 0 || idx >= memo.length) {
            return original.call(chunk, pos); // out-of-range: vanilla VOID_AIR path
        }
        if (this.superchunk$epochs[idx] == this.superchunk$epoch) {
            BlockState cached = memo[idx];
            if (SUPERCHUNK$VERIFY) {
                BlockState actual = original.call(chunk, pos);
                long h = SUPERCHUNK$HITS.incrementAndGet();
                if (actual != cached) {
                    long m = SUPERCHUNK$MISMATCHES.incrementAndGet();
                    if (m <= 5) {
                        org.slf4j.LoggerFactory.getLogger("SuperChunk-SurfaceMemoVerify").warn(
                                "[surfaceMemo-verify] MISMATCH #{} at {}: memo={} actual={}", m, pos, cached, actual);
                    }
                }
                if (h % 10_000_000L == 0L) {
                    org.slf4j.LoggerFactory.getLogger("SuperChunk-SurfaceMemoVerify").info(
                            "[surfaceMemo-verify] hits={} mismatches={}", h, SUPERCHUNK$MISMATCHES.get());
                }
            }
            return cached;
        }
        BlockState s = original.call(chunk, pos);
        memo[idx] = s;
        this.superchunk$epochs[idx] = this.superchunk$epoch;
        return s;
    }

    @WrapOperation(
            method = "setBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private BlockState superchunk$memoSet(ChunkAccess chunk, BlockPos pos, BlockState state, boolean isMoving,
                                          Operation<BlockState> original) {
        BlockState old = original.call(chunk, pos, state, isMoving);
        if (SUPERCHUNK$ENABLED && this.superchunk$usable && chunk == this.superchunk$chunk
                && pos.getX() == this.superchunk$x && pos.getZ() == this.superchunk$z) {
            int idx = pos.getY() - this.superchunk$minBuild;
            if (idx >= 0 && idx < this.superchunk$memo.length) {
                this.superchunk$memo[idx] = state;
                this.superchunk$epochs[idx] = this.superchunk$epoch;
            }
        }
        return old;
    }
}
