package dev.superchunk.worldgen;

import dev.superchunk.compat.LithiumBlockTracking;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Whole-cell handling of the cells the noise fill has already decided
 * ({@code MixinNoiseFillAirCells}).
 *
 * <p>For a cell proven all air ({@link AirCells}) or all default block ({@link CellSignSkip}),
 * {@code doFill} still walked the cell's blocks one by one (128 in the overworld): the loop
 * counters, three {@code updateFor} calls, the state lookup and, for a solid block, one palette
 * write and two heightmap updates each. About 9 cells in 10 are decided, so that walk was most of
 * the fill's per-block time.
 *
 * <p>An air cell writes nothing, so its walk is skipped. A solid cell is written here in one go,
 * with the result the walk produces:
 * <ul>
 *   <li>every block of the cell is set to the default block through the same direct palette write
 *       as the per-block path (Noisium's redirect), with the section counters and Lithium's block
 *       tracking advanced once per block;</li>
 *   <li>both worldgen heightmaps see the cell's blocks column by column from the top down. The
 *       walk's order is layer by layer, but a heightmap column only depends on its own blocks, and
 *       they arrive top down either way. {@code Heightmap.update} returns before doing anything
 *       once {@code y <= firstAvailable - 2}, and a call that returns there changes nothing, so
 *       the rest of the column is not offered.</li>
 * </ul>
 * The default block has no fluid (a condition of the solid proof), so no fluid update is marked.
 *
 * <p>Kill switch {@code -Dsuperchunk.worldgen.wholeCells=false}. The check is the noise-fill hash
 * ({@code -Dsuperchunk.debug.noiseFillHash}) with the switch on and off.
 */
public final class WholeCells {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.wholeCells", "true"));

    private WholeCells() {
    }

    /** Places {@code state} in every block of the {@code w x h x w} cell at (x0, y0, z0). */
    public static void fillSolid(ChunkAccess chunk, int x0, int y0, int z0, int w, int h, BlockState state) {
        if (state == Blocks.AIR.defaultBlockState()) {
            return; // doFill places nothing for AIR
        }
        final int lx0 = x0 & 15;
        final int lz0 = z0 & 15;
        final boolean realSetBlock = LithiumBlockTracking.directWritesUnsafe();
        final boolean tracked = LithiumBlockTracking.active();
        final boolean fluid = !state.getFluidState().isEmpty();
        final boolean ticking = state.isRandomlyTicking();
        final int perLayer = w * w;
        for (int cy = h - 1; cy >= 0; cy--) {
            final int y = y0 + cy;
            final int ly = y & 15;
            final LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            if (realSetBlock) {
                // A block-counting mixin we could not bind: its hooks live in setBlockState.
                for (int cx = 0; cx < w; cx++) {
                    for (int cz = 0; cz < w; cz++) {
                        section.setBlockState(lx0 + cx, ly, lz0 + cz, state, false);
                    }
                }
                continue;
            }
            // As NoiseChunkGeneratorMixin's direct write, once per block of the layer.
            section.nonEmptyBlockCount += perLayer;
            if (fluid) {
                section.tickingFluidCount += perLayer;
            }
            if (ticking) {
                section.tickingBlockCount += perLayer;
            }
            final var states = section.states;
            final int id = states.data.palette().idFor(state);
            final var data = states.data; // idFor can resize and replace it
            final var storage = data.storage();
            for (int cx = 0; cx < w; cx++) {
                for (int cz = 0; cz < w; cz++) {
                    final int index = states.strategy.getIndex(lx0 + cx, ly, lz0 + cz);
                    if (tracked) {
                        final int previous = storage.getAndSet(index, id);
                        LithiumBlockTracking.track(section, state, data.palette().valueFor(previous));
                    } else {
                        storage.set(index, id);
                    }
                }
            }
        }
        final Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        final Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        final int top = y0 + h - 1;
        for (int cx = 0; cx < w; cx++) {
            for (int cz = 0; cz < w; cz++) {
                column(oceanFloor, lx0 + cx, lz0 + cz, top, y0, state);
                column(surface, lx0 + cx, lz0 + cz, top, y0, state);
            }
        }
    }

    private static void column(Heightmap map, int x, int z, int top, int bottom, BlockState state) {
        for (int y = top; y >= bottom; y--) {
            if (y <= map.getFirstAvailable(x, z) - 2) {
                return; // update() returns at once here and for every block below
            }
            map.update(x, y, z, state);
        }
    }
}
