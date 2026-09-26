package dev.superchunk.worldgen;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The air-cell skip in the CPU noise fill ({@code MixinNoiseFillAirCells}).
 *
 * <p>Most of a chunk's noise cells are sky: every block's final density is non-solid and the
 * noise aquifer answers {@code Blocks.AIR} for it through its adaptive air skip (at or above the
 * local water ceiling plus the barrier margin, where the global fluid is air). {@code doFill} then
 * writes nothing for the cell — but it still ran, for each of the cell's blocks, the interpolator
 * updates, the material-rule dispatch and the aquifer call.
 *
 * <p>After {@code selectCellYZ} has filled the cell's final-density values, a cell is skipped when
 * (a) every value is non-solid (not {@code > 0}, the aquifer's own test), (b) the aquifer's
 * adaptive-skip branch answers the identical {@code AIR} state for every block of the cell
 * ({@link AirPredicate}, evaluated block by block with the same functions), and (c) nothing else
 * hooks the fill ({@link BlockFillHooks}). Then each block's {@code getInterpolatedState} returns
 * that {@code AIR} directly and the per-block interpolator loops are skipped; the chunk's counters
 * and cell coordinates advance as before. The skipped work has no observable effect: the only
 * state it writes is the aquifer's {@code shouldScheduleFluidUpdate}, which {@code doFill} reads
 * only after a non-air result and every later block rewrites, and interpolator values, which each
 * cell recomputes from its own corners before any read.
 *
 * <p>Kill switch {@code -Dsuperchunk.worldgen.airCells=false}. Verify
 * {@code -Dsuperchunk.worldgen.airCells.verify=true} skips nothing and checks that every block of
 * every predicted-air cell really came out {@code AIR}.
 */
public final class AirCells {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.airCells", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.airCells.verify");

    /** Implemented on {@code NoiseChunk.CacheAllInCell}: the current cell's values. */
    public interface CellValues {
        double[] superchunk$cellValues();
    }

    /** Implemented on {@code Aquifer.NoiseBasedAquifer}. */
    public interface AirPredicate {
        /**
         * True iff {@code computeSubstance} with a non-solid density takes its adaptive air-skip
         * branch and returns {@code Blocks.AIR.defaultBlockState()} for every block of the
         * {@code w x h x w} cell at (x0, y0, z0).
         */
        boolean superchunk$airCell(int x0, int y0, int z0, int w, int h);
    }

    /** Implemented on {@code NoiseChunk}. */
    public interface Chunk {
        List<?> superchunk$cellCaches();

        void superchunk$setAirCell(boolean air);

        boolean superchunk$airCell();
    }

    private static final AtomicLong CELLS = new AtomicLong();
    private static final AtomicLong AIR_CELLS = new AtomicLong();
    private static final AtomicLong CHECKED_BLOCKS = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();

    private AirCells() {
    }

    /** Whether the cell {@code selectCellYZ} just filled is provably all air (see class doc). */
    public static boolean cellIsAir(NoiseChunk nc) {
        if (VERIFY) {
            CELLS.incrementAndGet();
        }
        List<?> caches = ((Chunk) nc).superchunk$cellCaches();
        if (caches.size() != 1 || !(caches.get(0) instanceof CellValues values)) {
            return false;
        }
        IChunkNoiseSampler cell = (IChunkNoiseSampler) nc;
        int w = cell.getHorizontalCellBlockCount();
        int h = cell.getVerticalCellBlockCount();
        double[] densities = values.superchunk$cellValues();
        if (densities == null || densities.length != w * w * h) {
            return false;
        }
        for (double density : densities) {
            if (density > 0.0) {
                return false;
            }
        }
        if (!(nc.aquifer() instanceof AirPredicate aquifer)
                || !aquifer.superchunk$airCell(cell.getStartBlockX(), cell.getStartBlockY(), cell.getStartBlockZ(), w, h)) {
            return false;
        }
        if (VERIFY) {
            AIR_CELLS.incrementAndGet();
        }
        return true;
    }

    /** Verify mode: one block of a predicted-air cell, as the full path produced it. */
    public static void verifyBlock(BlockState produced) {
        CHECKED_BLOCKS.incrementAndGet();
        if (produced != Blocks.AIR.defaultBlockState() && MISMATCHES.incrementAndGet() <= 16) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-AirCells").warn(
                    "[air-cells] VERIFY MISMATCH: a predicted-air cell produced {}", produced);
        }
    }

    public static void reportVerify() {
        if (VERIFY) {
            long m = MISMATCHES.get();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-AirCells").info(
                    "[air-cells] VERIFY: cells={} predicted air={} blocks checked={} MISMATCHES={} -> {}",
                    CELLS.get(), AIR_CELLS.get(), CHECKED_BLOCKS.get(), m, m == 0 ? "PASS" : "FAIL");
        }
    }
}
