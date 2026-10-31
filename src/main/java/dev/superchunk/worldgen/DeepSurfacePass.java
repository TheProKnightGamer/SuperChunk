package dev.superchunk.worldgen;

import dev.superchunk.compat.LithiumBlockTracking;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bottom of {@code MixinSurfaceSystemFast}'s surface pass, a section at a time. Below the preliminary surface, at a
 * height whose {@link DeepSurfaceRules} answer is known, all that pass does is replace the default block (stone) with
 * the answer. So each column is walked only down to {@link #walkLow}, and the rest is done here after the last column:
 * <ul>
 *   <li>a section whose heights all answer the same state D for every column swaps the palette entry of stone for D
 *       (the storage is untouched, so every stone becomes D) when D is not in the palette yet;</li>
 *   <li>a section where D is most heights' answer and the rest always give a state (the bedrock floor) swaps, then
 *       writes the other answers over the former stone of those heights;</li>
 *   <li>any other section writes each stone block with an answer through {@code LevelChunkSection.setBlockState}.</li>
 * </ul>
 * Sections whose answers are all nothing, or stone itself (the end's end stone), are not read at all.
 *
 * <p>The same final blocks, counters and heightmaps as the column walk; only the in-memory palette order differs, and
 * saving repacks it. Why the order of the writes doesn't matter:
 * <ul>
 *   <li>Nothing else in the pass reads or writes these blocks after the walk passes them: the walk stops above them,
 *       the extensions touch only blocks at or above the preliminary surface, and no rule reads blocks.</li>
 *   <li>Answers are solid, fluid-free states that count in every heightmap the pass updates, as stone does, and every
 *       height done here is below each of the column's heightmaps when its walk starts. A heightmap only moves down
 *       to the highest counting block left, which these remain, so the column walk's writes here never moved a
 *       heightmap, and these don't either.</li>
 *   <li>No answer has a fluid, so there are no post-processing marks.</li>
 * </ul>
 * A swap keeps the section's counters (it is told how many blocks changed) and Lithium's block tracking
 * ({@code trackBulk}); it stands down where another mod hooks section writes. Kill switch
 * {@code -Dsuperchunk.worldgen.deepSurfacePass=false}.
 */
public final class DeepSurfacePass {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.deepSurfacePass", "true"));
    public static final boolean STATS = Boolean.getBoolean("superchunk.worldgen.deepSurfacePass.stats");

    private static volatile byte hooks;
    private static final AtomicLong SWAPPED = new AtomicLong();
    private static final AtomicLong SWAPPED_MIXED = new AtomicLong();
    private static final AtomicLong BY_BLOCK = new AtomicLong();
    private static final AtomicLong WRITES = new AtomicLong();

    private final ProtoChunk chunk;
    private final DeepSurfaceRules.Table table;
    private final BlockState stone;
    private final DeepSurfaceRules.Randoms randoms;
    private final Heightmap[] maps;
    private final boolean swaps;
    // per column (x << 4 | z): the lowest height its walk does; heights below it are done here
    private final int[] walkLow = new int[256];
    private int minWalkLow = Integer.MAX_VALUE;
    private int maxWalkLow = Integer.MIN_VALUE;

    private DeepSurfacePass(ProtoChunk chunk, DeepSurfaceRules.Table table, BlockState stone, DeepSurfaceRules.Randoms randoms,
                            Heightmap[] maps, boolean swaps) {
        this.chunk = chunk;
        this.table = table;
        this.stone = stone;
        this.randoms = randoms;
        this.maps = maps;
        this.swaps = swaps;
    }

    private static boolean unhooked() {
        byte state = hooks;
        if (state == ForeignHooks.UNKNOWN) {
            state = ForeignHooks.state("The deep surface pass's palette swap",
                    "net.minecraft.world.level.chunk.LevelChunkSection#setBlockState",
                    "net.minecraft.world.level.chunk.PalettedContainer#getAndSet",
                    "net.minecraft.world.level.chunk.PalettedContainer#getAndSetUnchecked",
                    "net.minecraft.world.level.chunk.PalettedContainer#set");
            if (state != ForeignHooks.UNKNOWN) {
                hooks = state;
            }
        }
        return state == ForeignHooks.CLEAR;
    }

    private static boolean solid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /**
     * The pass for this chunk, or {@code null} to walk every column to the bottom. {@code maps} are the heightmaps
     * {@link ProtoChunkWrites#bind} returned, in the order of the chunk status's heightmap set.
     */
    public static DeepSurfacePass begin(ProtoChunk chunk, Heightmap[] maps, DeepSurfaceRules.Table table, BlockState stone,
                                        DeepSurfaceRules.Randoms randoms) {
        if (!ENABLED || (SurfaceTiming.off() & SurfaceTiming.DEEP_PASS) != 0 || table == null || table.answers() == null
                || LithiumBlockTracking.directWritesUnsafe() || !solid(stone)) {
            return null;
        }
        int m = 0;
        for (Heightmap.Types type : chunk.getStatus().heightmapsAfter()) {
            if (m >= maps.length || !type.isOpaque().test(stone)) {
                return null;
            }
            for (BlockState state : table.states()) {
                if (!type.isOpaque().test(state)) {
                    return null;
                }
            }
            m++;
        }
        for (BlockState state : table.states()) {
            if (!solid(state)) {
                return null;
            }
        }
        return new DeepSurfacePass(chunk, table, stone, randoms, maps, unhooked());
    }

    /**
     * The lowest height the walk of column (x, z) (chunk-local) does: below the preliminary surface ({@code deepBelow}),
     * below every unknown answer, and below each of the column's heightmaps as they stand now.
     */
    public int walkLow(int x, int z, int deepBelow, int minBuild) {
        int low = Math.min(deepBelow, this.table.minUnknown());
        for (Heightmap map : this.maps) {
            low = Math.min(low, map.getFirstAvailable(x, z));
        }
        low = Math.max(low, minBuild);
        this.walkLow[x << 4 | z] = low;
        this.minWalkLow = Math.min(this.minWalkLow, low);
        this.maxWalkLow = Math.max(this.maxWalkLow, low);
        return low;
    }

    /** After the last column: every height below its column's {@link #walkLow}. */
    public void run() {
        int minBuild = this.chunk.getMinBuildHeight();
        for (int si = this.chunk.getSectionIndex(Math.max(minBuild, this.maxWalkLow - 1)); si >= 0; si--) {
            int sy = this.chunk.getSectionYFromSectionIndex(si) << 4;
            int top = sy + 15;
            Object[] answers = new Object[16];
            boolean any = false;
            for (int k = 0; k < 16; k++) {
                answers[k] = this.table.at(sy + k);
                any |= answers[k] != DeepSurfaceRules.NONE && answers[k] != this.stone;
            }
            if (!any || this.maxWalkLow <= sy) {
                continue; // nothing to write, or every column walked this whole section
            }
            LevelChunkSection section = this.chunk.getSection(si);
            if (section.hasOnlyAir()) {
                continue;
            }
            Palette<BlockState> palette = section.states.data.palette();
            int stoneId = -1;
            boolean global = palette instanceof GlobalPalette;
            if (!global) {
                for (int i = 0; i < palette.getSize(); i++) {
                    if (palette.valueFor(i) == this.stone) {
                        stoneId = i;
                        break;
                    }
                }
                if (stoneId < 0) {
                    continue; // no stone in this section
                }
            }
            BlockState d = this.swaps && !global && this.minWalkLow > top ? swapState(answers, palette) : null;
            if (d != null) {
                swap(section, stoneId, d);
                boolean mixed = false;
                for (int k = 0; k < 16; k++) {
                    if (answers[k] != d) {
                        mixed = true;
                        writeHeight(section, sy + k, answers[k], d);
                    }
                }
                if (STATS) {
                    (mixed ? SWAPPED_MIXED : SWAPPED).incrementAndGet();
                }
            } else {
                for (int k = 0; k < 16; k++) {
                    if (answers[k] != DeepSurfaceRules.NONE && answers[k] != this.stone) {
                        writeHeight(section, sy + k, answers[k], this.stone);
                    }
                }
                if (STATS) {
                    BY_BLOCK.incrementAndGet();
                }
            }
        }
    }

    /**
     * The state to swap stone for in a section every column leaves to this pass: the most common answer, if it is a
     * state that is not stone and not in the palette yet, answers at least half the heights, and every other height
     * always gives a state (so no former stone has to become stone again).
     */
    private BlockState swapState(Object[] answers, Palette<BlockState> palette) {
        BlockState best = null;
        int bestCount = 0;
        for (int k = 0; k < 16; k++) {
            if (answers[k] instanceof BlockState state && state != best) {
                int count = 0;
                for (Object answer : answers) {
                    count += answer == state ? 1 : 0;
                }
                if (count > bestCount) {
                    best = state;
                    bestCount = count;
                }
            }
        }
        if (best == null || best == this.stone || bestCount < 8) {
            return null;
        }
        for (Object answer : answers) {
            if (answer != best && !DeepSurfaceRules.alwaysState(answer)) {
                return null;
            }
        }
        for (int i = 0; i < palette.getSize(); i++) {
            if (palette.valueFor(i) == best) {
                return null;
            }
        }
        return best;
    }

    /** Every stone in the section becomes {@code d}: stone's palette entry is replaced, ids and storage stay. */
    private void swap(LevelChunkSection section, int stoneId, BlockState d) {
        PalettedContainer<BlockState> states = section.states;
        PalettedContainer.Data<BlockState> data = states.data;
        Palette<BlockState> palette = data.palette();
        List<BlockState> values = new ArrayList<>(palette.getSize());
        for (int i = 0; i < palette.getSize(); i++) {
            values.add(i == stoneId ? d : palette.valueFor(i));
        }
        int[] count = new int[1];
        data.storage().getAll(id -> count[0] += id == stoneId ? 1 : 0);
        PalettedContainer.Configuration<BlockState> configuration = data.configuration();
        Palette<BlockState> swapped = configuration.factory().create(configuration.bits(), Block.BLOCK_STATE_REGISTRY, states, values);
        states.data = new PalettedContainer.Data<>(configuration, data.storage(), swapped);
        // setBlockState's counters: both are solid and fluid-free, so only random ticking can differ
        if (this.stone.isRandomlyTicking() != d.isRandomlyTicking()) {
            section.tickingBlockCount += d.isRandomlyTicking() ? count[0] : -count[0];
        }
        LithiumBlockTracking.trackBulk(section, d, this.stone, count[0]);
        if (STATS) {
            WRITES.addAndGet(count[0]);
        }
    }

    /**
     * Each column's block at height {@code y} that is {@code from} and below the column's walk gets the answer there,
     * unless that is nothing or {@code from} itself. {@code from} is stone, or the state a swap put in its place.
     */
    private void writeHeight(LevelChunkSection section, int y, Object answer, BlockState from) {
        int ly = y & 15;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (y >= this.walkLow[x << 4 | z] || section.getBlockState(x, ly, z) != from) {
                    continue;
                }
                Object state = DeepSurfaceRules.resolve(answer, this.chunk.getPos().getMinBlockX() + x, y,
                        this.chunk.getPos().getMinBlockZ() + z, this.randoms);
                if (state != DeepSurfaceRules.NONE && state != from) {
                    section.setBlockState(x, ly, z, (BlockState) state);
                    if (STATS) {
                        WRITES.incrementAndGet();
                    }
                }
            }
        }
    }

    public static void report() {
        if (STATS) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Surface").info(
                    "[deep-surface-pass] sections swapped={} swapped+written={} written by block={} blocks changed={}",
                    SWAPPED.get(), SWAPPED_MIXED.get(), BY_BLOCK.get(), WRITES.get());
        }
    }
}
