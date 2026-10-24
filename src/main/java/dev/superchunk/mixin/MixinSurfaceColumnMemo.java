package dev.superchunk.mixin;

import dev.superchunk.worldgen.SurfaceTiming;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
 *
 * <p><b>A section at a time.</b> A miss reads the column's 16 blocks of that section at once, the way
 * {@code ProtoChunk.getBlockState} reads each ({@code hasOnlyAir}, then the section's {@code getBlockState}), into
 * the entries the memo does not hold yet. The invariant above makes the order of reads irrelevant: no block of the
 * column changes except through {@code setBlock}, which updates the memo. It stands down when another mod hooks
 * those reads; kill switch {@code -Dsuperchunk.worldgen.surfaceColumnMemo.sections=false}.
 *
 * <p>{@code @Redirect} rather than {@code @WrapOperation}: these run for every block of every column, and a wrapped
 * call passes its arguments to the original as an array.
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
    private static final boolean SUPERCHUNK$SECTIONS = SUPERCHUNK$ENABLED
            && Boolean.parseBoolean(System.getProperty("superchunk.worldgen.surfaceColumnMemo.sections", "true"));
    @Unique
    private static byte superchunk$sectionHooks;
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
    /** Misses read the column's whole section ({@link #superchunk$readSection}). */
    @Unique
    private boolean superchunk$sections;
    /** {@link dev.superchunk.worldgen.ProtoChunkWrites}: the chunk's heightmaps, or null for the original write. */
    @Unique
    private net.minecraft.world.level.levelgen.Heightmap[] superchunk$heightmaps;

    @Unique
    private void superchunk$initFor(ChunkAccess chunk) {
        this.superchunk$chunk = chunk;
        this.superchunk$usable = chunk.getClass() == ProtoChunk.class
                && ((ProtoChunk) chunk).getBelowZeroRetrogen() == null;
        this.superchunk$heightmaps = this.superchunk$usable
                ? dev.superchunk.worldgen.ProtoChunkWrites.bind((ProtoChunk) chunk) : null;
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
            this.superchunk$sections = SUPERCHUNK$SECTIONS && superchunk$sectionsUnhooked();
        }
    }

    @Unique
    private static boolean superchunk$sectionsUnhooked() {
        byte state = superchunk$sectionHooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("The surface column memo's section reads (ProtoChunk.getBlockState)",
                    "net.minecraft.world.level.chunk.ProtoChunk#getBlockState",
                    "net.minecraft.world.level.chunk.ChunkAccess#getSection",
                    "net.minecraft.world.level.chunk.LevelChunkSection#hasOnlyAir");
            superchunk$sectionHooks = state;
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    /**
     * {@code ProtoChunk.getBlockState} for the column's 16 blocks of the section holding memo entry {@code idx}, into
     * the entries not held yet; returns entry {@code idx}. The memo spans whole sections: build limits are multiples of 16.
     */
    @Unique
    private BlockState superchunk$readSection(ChunkAccess chunk, int x, int y, int z, int idx) {
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        BlockState[] memo = this.superchunk$memo;
        int[] epochs = this.superchunk$epochs;
        int epoch = this.superchunk$epoch;
        int base = idx & ~15;
        if (section.hasOnlyAir()) {
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int i = base; i < base + 16; i++) {
                if (epochs[i] != epoch) {
                    memo[i] = air;
                    epochs[i] = epoch;
                }
            }
        } else {
            int lx = x & 15;
            int lz = z & 15;
            for (int i = base; i < base + 16; i++) {
                if (epochs[i] != epoch) {
                    memo[i] = section.getBlockState(lx, i - base, lz);
                    epochs[i] = epoch;
                }
            }
        }
        return memo[idx];
    }

    @Redirect(
            method = "getBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private BlockState superchunk$memoGet(ChunkAccess chunk, BlockPos pos) {
        if (!SUPERCHUNK$ENABLED) {
            return chunk.getBlockState(pos);
        }
        if (chunk != this.superchunk$chunk) {
            this.superchunk$initFor(chunk);
        }
        if (!this.superchunk$usable) {
            return chunk.getBlockState(pos);
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
            return chunk.getBlockState(pos); // out-of-range: vanilla VOID_AIR path
        }
        if (this.superchunk$epochs[idx] == this.superchunk$epoch) {
            BlockState cached = memo[idx];
            if (SUPERCHUNK$VERIFY) {
                BlockState actual = chunk.getBlockState(pos);
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
        if (this.superchunk$sections && (SurfaceTiming.off() & SurfaceTiming.COLUMN_SECTIONS) == 0) {
            return this.superchunk$readSection(chunk, x, pos.getY(), z, idx);
        }
        BlockState s = chunk.getBlockState(pos);
        memo[idx] = s;
        this.superchunk$epochs[idx] = this.superchunk$epoch;
        return s;
    }

    @Redirect(
            method = "setBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private BlockState superchunk$memoSet(ChunkAccess chunk, BlockPos pos, BlockState state, boolean isMoving) {
        net.minecraft.world.level.levelgen.Heightmap[] maps = this.superchunk$heightmaps;
        int y = pos.getY();
        BlockState old = maps != null && chunk == this.superchunk$chunk
                && y >= this.superchunk$minBuild && y - this.superchunk$minBuild < this.superchunk$memo.length
                ? dev.superchunk.worldgen.ProtoChunkWrites.set((ProtoChunk) chunk, maps, pos, state)
                : chunk.setBlockState(pos, state, isMoving);
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
