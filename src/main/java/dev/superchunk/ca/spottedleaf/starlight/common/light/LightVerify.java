package dev.superchunk.ca.spottedleaf.starlight.common.light;

import dev.superchunk.ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunkGetter;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk: {@code -Dsuperchunk.lighting.verify=true} lights every new chunk twice from the same state, once
 * with the engines' {@code FAST_*} shortcuts and once without, and compares what each left behind: every sky and
 * block nibble (state and data) of the chunk and its eight neighbours, the chunk's emptiness maps and the filled-in
 * emptiness changes. The run without the shortcuts is the one kept. Saved light cannot be compared between two
 * runs instead: which neighbours were lit when a chunk was, and which later edge fixes reached disk, depends on
 * scheduling, so two runs of the same build already differ.
 */
public final class LightVerify {

    public static final boolean ENABLED = Boolean.getBoolean("superchunk.lighting.verify");
    // the shortcuts the first run takes; 0 makes both runs the same, a control for the harness itself
    private static final int FAST = Integer.getInteger("superchunk.lighting.verifyFast", StarLightEngine.FAST_DEFAULT);

    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static final AtomicLong UNSTABLE = new AtomicLong();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SuperChunk-Lighting");

    private LightVerify() {
    }

    // one nibble's fields, with private copies of its arrays
    private static final class NibbleState {
        final int stateUpdating;
        final int stateVisible;
        final boolean updatingDirty;
        final boolean shared; // storageUpdating == storageVisible
        final byte[] updating;
        final byte[] visible;

        NibbleState(final SWMRNibbleArray nibble) {
            this.stateUpdating = nibble.stateUpdating;
            this.stateVisible = nibble.stateVisible;
            this.updatingDirty = nibble.updatingDirty;
            this.shared = nibble.storageUpdating == nibble.storageVisible;
            this.visible = nibble.storageVisible == null ? null : nibble.storageVisible.clone();
            this.updating = this.shared || nibble.storageUpdating == null ? null : nibble.storageUpdating.clone();
        }

        void restore(final SWMRNibbleArray nibble) {
            // fresh arrays: the ones the first run left may be back in the thread's pool
            final byte[] visible = this.visible == null ? null : this.visible.clone();
            nibble.storageVisible = visible;
            nibble.storageUpdating = this.shared ? visible : this.updating == null ? null : this.updating.clone();
            nibble.updatingDirty = this.updatingDirty;
            nibble.stateUpdating = this.stateUpdating;
            nibble.stateVisible = this.stateVisible;
        }

        boolean sameAs(final NibbleState other) {
            return this.stateUpdating == other.stateUpdating && this.stateVisible == other.stateVisible
                    && this.updatingDirty == other.updatingDirty
                    && Arrays.equals(this.visible, other.visible)
                    && Arrays.equals(this.shared ? this.visible : this.updating, other.shared ? other.visible : other.updating);
        }
    }

    // the light-engine state of one chunk: its nibble arrays and what is in them, and its emptiness maps
    private static final class ChunkState {
        final ExtendedChunk chunk;
        final SWMRNibbleArray[] sky;
        final SWMRNibbleArray[] block;
        final NibbleState[] skyStates;
        final NibbleState[] blockStates;
        final boolean[] skyEmptiness;
        final boolean[] blockEmptiness;
        final boolean[] skyEmptinessCopy;
        final boolean[] blockEmptinessCopy;

        ChunkState(final ExtendedChunk chunk) {
            this.chunk = chunk;
            this.sky = chunk.getSkyNibbles();
            this.block = chunk.getBlockNibbles();
            this.skyStates = states(this.sky);
            this.blockStates = states(this.block);
            this.skyEmptiness = chunk.getSkyEmptinessMap();
            this.blockEmptiness = chunk.getBlockEmptinessMap();
            this.skyEmptinessCopy = this.skyEmptiness == null ? null : this.skyEmptiness.clone();
            this.blockEmptinessCopy = this.blockEmptiness == null ? null : this.blockEmptiness.clone();
        }

        private static NibbleState[] states(final SWMRNibbleArray[] nibbles) {
            if (nibbles == null) {
                return null;
            }
            final NibbleState[] ret = new NibbleState[nibbles.length];
            for (int i = 0; i < nibbles.length; ++i) {
                ret[i] = nibbles[i] == null ? null : new NibbleState(nibbles[i]);
            }
            return ret;
        }

        void restore() {
            this.chunk.setSkyNibbles(this.sky);
            this.chunk.setBlockNibbles(this.block);
            restore(this.sky, this.skyStates);
            restore(this.block, this.blockStates);
            if (this.skyEmptiness != null) {
                System.arraycopy(this.skyEmptinessCopy, 0, this.skyEmptiness, 0, this.skyEmptiness.length);
            }
            if (this.blockEmptiness != null) {
                System.arraycopy(this.blockEmptinessCopy, 0, this.blockEmptiness, 0, this.blockEmptiness.length);
            }
            this.chunk.setSkyEmptinessMap(this.skyEmptiness);
            this.chunk.setBlockEmptinessMap(this.blockEmptiness);
        }

        private static void restore(final SWMRNibbleArray[] nibbles, final NibbleState[] states) {
            if (nibbles != null) {
                for (int i = 0; i < nibbles.length; ++i) {
                    if (nibbles[i] != null) {
                        states[i].restore(nibbles[i]);
                    }
                }
            }
        }

        // what differs from other (the same chunk after the other run), or null
        String diff(final ChunkState other) {
            final String sky = diff("sky", this.skyStates, other.skyStates);
            if (sky != null) {
                return sky;
            }
            final String block = diff("block", this.blockStates, other.blockStates);
            if (block != null) {
                return block;
            }
            if (!Arrays.equals(this.skyEmptinessCopy, other.skyEmptinessCopy)) {
                return "sky emptiness map";
            }
            if (!Arrays.equals(this.blockEmptinessCopy, other.blockEmptinessCopy)) {
                return "block emptiness map";
            }
            return null;
        }

        private static String diff(final String layer, final NibbleState[] a, final NibbleState[] b) {
            if (a == null || b == null) {
                return a == b ? null : layer + " nibble array presence";
            }
            if (a.length != b.length) {
                return layer + " nibble array length";
            }
            for (int i = 0; i < a.length; ++i) {
                if (a[i] == null || b[i] == null ? a[i] != b[i] : !a[i].sameAs(b[i])) {
                    return layer + " nibble " + i;
                }
            }
            return null;
        }
    }

    private static ChunkState[] capture(final ChunkAccess[] chunks) {
        final ChunkState[] ret = new ChunkState[chunks.length];
        for (int i = 0; i < chunks.length; ++i) {
            ret[i] = chunks[i] == null ? null : new ChunkState((ExtendedChunk)chunks[i]);
        }
        return ret;
    }

    public static void lightChunk(final LightChunkGetter lightAccess, final ChunkAccess chunk, final Boolean[] emptySections,
                                  final SkyStarLightEngine skyEngine, final BlockStarLightEngine blockEngine) {
        final int chunkX = chunk.getPos().x;
        final int chunkZ = chunk.getPos().z;
        // index = (dx + 1) + 3 * (dz + 1); the centre is always the chunk being lit
        final ChunkAccess[] chunks = new ChunkAccess[9];
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                chunks[(dx + 1) + 3 * (dz + 1)] = (dx | dz) == 0 ? chunk : (ChunkAccess)lightAccess.getChunkForLighting(chunkX + dx, chunkZ + dz);
            }
        }
        final long usableBefore = usable(lightAccess, chunkX, chunkZ);
        final ChunkState[] before = capture(chunks);
        final Boolean[] emptySectionsBefore = emptySections.clone();

        run(lightAccess, chunk, emptySections, skyEngine, blockEngine, FAST);
        final ChunkState[] fast = capture(chunks);
        final Boolean[] emptySectionsFast = emptySections.clone();

        for (final ChunkState state : before) {
            if (state != null) {
                state.restore();
            }
        }
        System.arraycopy(emptySectionsBefore, 0, emptySections, 0, emptySections.length);

        run(lightAccess, chunk, emptySections, skyEngine, blockEngine, 0);
        final ChunkState[] plain = capture(chunks);

        CHUNKS.incrementAndGet();
        if (usable(lightAccess, chunkX, chunkZ) != usableBefore) {
            // a neighbour finished lighting meanwhile, so the two runs did not start from the same state
            UNSTABLE.incrementAndGet();
            return;
        }
        String diff = Arrays.equals(emptySectionsFast, emptySections) ? null : "emptiness changes";
        for (int i = 0; i < chunks.length && diff == null; ++i) {
            if (plain[i] != null) {
                final String d = plain[i].diff(fast[i]);
                if (d != null) {
                    diff = "chunk " + (chunkX + i % 3 - 1) + "," + (chunkZ + i / 3 - 1) + ": " + d;
                }
            }
        }
        if (diff != null && MISMATCHES.incrementAndGet() <= 20) {
            LOGGER.warn("[light-verify] VERIFY MISMATCH lighting chunk {},{}: {}", chunkX, chunkZ, diff);
        }
    }

    // which of the 5x5 chunks the engines may use (their canUseChunk), as bits
    private static long usable(final LightChunkGetter lightAccess, final int chunkX, final int chunkZ) {
        long ret = 0L;
        for (int dz = -2; dz <= 2; ++dz) {
            for (int dx = -2; dx <= 2; ++dx) {
                final ChunkAccess neighbour = (ChunkAccess)lightAccess.getChunkForLighting(chunkX + dx, chunkZ + dz);
                if (neighbour != null) {
                    ret |= 1L << (2 * ((dx + 2) + 5 * (dz + 2)));
                    if (neighbour.getStatus().isOrAfter(net.minecraft.world.level.chunk.ChunkStatus.LIGHT) && neighbour.isLightCorrect()) {
                        ret |= 2L << (2 * ((dx + 2) + 5 * (dz + 2)));
                    }
                }
            }
        }
        return ret;
    }

    private static void run(final LightChunkGetter lightAccess, final ChunkAccess chunk, final Boolean[] emptySections,
                            final SkyStarLightEngine skyEngine, final BlockStarLightEngine blockEngine, final int fastPaths) {
        if (skyEngine != null) {
            skyEngine.fastPaths = fastPaths;
            skyEngine.light(lightAccess, chunk, emptySections);
            skyEngine.fastPaths = StarLightEngine.FAST_DEFAULT;
        }
        if (blockEngine != null) {
            blockEngine.fastPaths = fastPaths;
            blockEngine.light(lightAccess, chunk, emptySections);
            blockEngine.fastPaths = StarLightEngine.FAST_DEFAULT;
        }
    }

    public static void report() {
        if (ENABLED) {
            LOGGER.info("[light-verify] chunks={} mismatches={} unstable={} (first run's shortcuts {})", CHUNKS.get(), MISMATCHES.get(),
                    UNSTABLE.get(), FAST);
        }
    }
}
