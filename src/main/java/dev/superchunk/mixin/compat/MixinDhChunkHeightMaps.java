package dev.superchunk.mixin.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import dev.superchunk.compat.DhFastPaths;
import dev.superchunk.compat.DhSelfCheck;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Distant Horizons' per-chunk heightmaps read a column by runs of equal blocks, as its LOD builder
 * does ({@link MixinDhLodBuilderRuns}).
 *
 * <p>{@code ChunkWrapper.createDhHeightMaps} finds, for each column, the highest solid and the
 * highest light-blocking block by asking DH's wrapper for every block from the top of the highest
 * non-empty section down. DH builds these maps three or four times per generated chunk (before
 * generation, then after each of the up to four sub-regions the chunk is part of). This makes the
 * same maps with the same calls, but reads a block through DH once per section and palette id (the
 * same id is the same block state, whose wrapper DH looks up by the state alone), and asks a
 * wrapper whether it is solid or opaque once per run (plain getters).
 *
 * <p>For vanilla {@code ProtoChunk}s and vanilla {@code LevelChunk}s (the chunks of an existing
 * world and of the running server, with the precautions {@link MixinDhLodBuilderRuns} describes),
 * and while no other mod hooks the reads it leaves out. Applied when the DH methods this relies on
 * hash to a version it was checked against, under self-check when they differ only in how they
 * compute ({@link dev.superchunk.compat.DhFastPathTable}). Kill switch
 * {@code -Dsuperchunk.dh.fastPaths=false}; {@code -Dsuperchunk.dh.lodVerify=true} also builds DH's
 * own maps and logs any difference.
 */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_neoforge", remap = false)
public abstract class MixinDhChunkHeightMaps {

    @Shadow
    @Final
    private static DhLogger LOGGER;

    @Shadow
    private static boolean heightmapThreadWarningLogged;

    @Shadow
    @Final
    private ChunkAccess chunk;

    @Shadow
    private int[][] solidHeightMap;

    @Shadow
    private int[][] lightBlockingHeightMap;

    @Shadow
    public abstract int getMinNonEmptyHeight();

    @Shadow
    public abstract int getMaxNonEmptyHeight();

    @Shadow
    public abstract IBlockStateWrapper getBlockState(int relX, int relY, int relZ);

    @Shadow
    public abstract void createDhHeightMaps();

    @Unique
    private static final boolean SUPERCHUNK$VERIFY = Boolean.getBoolean("superchunk.dh.lodVerify");

    @Unique
    private static final Logger SUPERCHUNK$LOG = LoggerFactory.getLogger("SuperChunk-Compat");

    @Unique
    private static volatile byte superchunk$hooks;

    @Unique
    private static final ThreadLocal<Boolean> SUPERCHUNK$IN_DH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Unique
    private static final LongAdder SUPERCHUNK$CHECKED = new LongAdder();

    @Unique
    private static final LongAdder SUPERCHUNK$MISMATCHES = new LongAdder();

    @Unique
    private static final AtomicInteger SUPERCHUNK$REPORTED = new AtomicInteger();

    /** Marks a section whose remembered wrapper is the only-air one. */
    @Unique
    private static final Object SUPERCHUNK$ONLY_AIR = new Object();

    /** Not null when this patch runs under self-check on the installed DH. */
    @Unique
    private static final DhSelfCheck SUPERCHUNK$HEIGHTMAP_CHECK = DhFastPaths.selfCheck("MixinDhChunkHeightMaps", "chunks");

    /** Whether the installed DH logs its off-thread warning (before 3.3.3 its test was inverted and it never did). */
    @Unique
    private static final boolean SUPERCHUNK$WARNS = DhFastPaths.heightMapsWarnOffThread();

    @Inject(method = "createDhHeightMaps", at = @At("HEAD"), cancellable = true)
    private void superchunk$byRuns(CallbackInfo ci) {
        DhSelfCheck check = SUPERCHUNK$HEIGHTMAP_CHECK;
        if (check != null && check.failed()) {
            return;
        }
        ChunkAccess chunk = this.chunk;
        // A chunk of the world (or one DH read from a region file) rather than one DH is generating.
        boolean live = chunk != null && chunk.getClass() != ProtoChunk.class;
        if (live) {
            chunk = dev.superchunk.compat.DhLiveChunks.levelChunk(chunk);
        }
        if (chunk == null || !(live ? dev.superchunk.compat.DhLiveChunks.unhooked() : superchunk$unhooked())) {
            return;
        }
        if ((SUPERCHUNK$VERIFY || check != null) && SUPERCHUNK$IN_DH.get()) {
            return;
        }
        if (SUPERCHUNK$WARNS && !heightmapThreadWarningLogged && !DhApi.isDhThread()) {
            heightmapThreadWarningLogged = true;
            LOGGER.warn("ChunkWrapper Height maps created on non-DH thread [" + Thread.currentThread().getName() + "]. This may cause stuttering.");
        }
        int[][] solid = new int[16][16];
        int[][] blocking = new int[16][16];
        if (check == null) {
            this.superchunk$fill(chunk, solid, blocking, live);
        } else {
            try {
                this.superchunk$fill(chunk, solid, blocking, live);
            } catch (Throwable t) {
                check.fail("its code threw " + t, t);
                return;
            }
        }
        this.solidHeightMap = solid;
        this.lightBlockingHeightMap = blocking;
        ci.cancel();
        if (SUPERCHUNK$VERIFY || check != null && check.sample()) {
            boolean same = false;
            // A chunk of the world can change between the two readings; a real difference repeats.
            for (int attempt = 0; attempt < (live ? 3 : 1) && !same; attempt++) {
                if (attempt > 0) {
                    solid = new int[16][16];
                    blocking = new int[16][16];
                    try {
                        this.superchunk$fill(chunk, solid, blocking, live);
                    } catch (RuntimeException e) {
                        break;
                    }
                }
                SUPERCHUNK$IN_DH.set(Boolean.TRUE);
                try {
                    this.createDhHeightMaps();
                } finally {
                    SUPERCHUNK$IN_DH.set(Boolean.FALSE);
                }
                // DH's own maps stay; they are equal unless a line below says otherwise.
                same = Arrays.deepEquals(solid, this.solidHeightMap) && Arrays.deepEquals(blocking, this.lightBlockingHeightMap);
            }
            if (check != null) {
                if (same) {
                    // Maps of a chunk with nothing in it (every column at the bottom) prove nothing.
                    int bottom = this.getMinNonEmptyHeight();
                    boolean empty = true;
                    for (int x = 0; x < 16 && empty; x++) {
                        for (int z = 0; z < 16 && empty; z++) {
                            empty = solid[x][z] == bottom && blocking[x][z] == bottom;
                        }
                    }
                    if (empty) {
                        check.passedEmpty();
                    } else {
                        check.passed();
                    }
                } else {
                    check.fail("chunk " + chunk.getPos() + ": height maps differ", null);
                }
            }
            if (SUPERCHUNK$VERIFY) {
                SUPERCHUNK$CHECKED.increment();
                if (!same) {
                    SUPERCHUNK$MISMATCHES.increment();
                    if (SUPERCHUNK$REPORTED.getAndIncrement() < 10) {
                        SUPERCHUNK$LOG.warn("[SuperChunk-Compat] [dh] heightmap runs VERIFY MISMATCH at chunk {}", chunk.getPos());
                    }
                }
                if (SUPERCHUNK$CHECKED.sum() % 16384 == 0) {
                    long m = SUPERCHUNK$MISMATCHES.sum();
                    SUPERCHUNK$LOG.info("[SuperChunk-Compat] [dh] heightmap runs VERIFY: checked={} mismatches={} -> {}",
                            SUPERCHUNK$CHECKED.sum(), m, m == 0 ? "PASS" : "FAIL");
                }
            }
        }
    }

    /**
     * Whether DH's wrapper is the one for a palette entry (the only-air section when {@code palette}
     * is null); see {@code MixinDhLodBuilderRuns}.
     */
    @Unique
    private static boolean superchunk$isWrapperOf(IBlockStateWrapper wrapper, Palette<BlockState> palette, int id) {
        try {
            if (palette == null) {
                return wrapper.isAir();
            }
            BlockState state = palette.valueFor(id);
            return wrapper.isAir() ? state.isAir() : wrapper.getWrappedMcObject() == state;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * DH's two height maps of the chunk, read by runs (see the class comment).
     *
     * @param live the chunk is a {@code LevelChunk}, which the server thread may be changing (see
     *             {@code MixinDhLodBuilderRuns})
     */
    @Unique
    private void superchunk$fill(ChunkAccess chunk, int[][] solid, int[][] blocking, boolean live) {
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        IBlockStateWrapper[][] sectionWrappers = new IBlockStateWrapper[sections.length][];
        Object[] sectionKeys = live ? new Object[sections.length] : null; // the palette data (or only-air mark) the wrappers are for
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int min = this.getMinNonEmptyHeight();
                int solidHeight = min;
                int blockingHeight = min;
                int y = this.getMaxNonEmptyHeight();
                int section = Integer.MIN_VALUE;
                IBlockStateWrapper[] wrappers = null; // as in MixinDhLodBuilderRuns: per palette id; null outside or for a large palette
                BitStorage storage = null;
                PalettedContainer.Strategy strategy = null;
                Palette<BlockState> palette = null;
                IBlockStateWrapper block;
                IBlockStateWrapper asked = null; // the wrapper whose answers are below
                boolean isSolid = false;
                boolean blocks = false;
                while (true) {
                    int s = (y >> 4) - minSection;
                    if (s != section) {
                        section = s;
                        storage = null;
                        palette = null;
                        wrappers = null;
                        if (s >= 0 && s < sections.length) {
                            wrappers = sectionWrappers[s];
                            LevelChunkSection levelSection = sections[s];
                            if (levelSection.hasOnlyAir()) {
                                if (live && sectionKeys[s] != SUPERCHUNK$ONLY_AIR) {
                                    sectionKeys[s] = SUPERCHUNK$ONLY_AIR;
                                    wrappers = sectionWrappers[s] = null;
                                }
                                if (wrappers == null) {
                                    wrappers = sectionWrappers[s] = new IBlockStateWrapper[1];
                                }
                            } else {
                                PalettedContainer<BlockState> states = levelSection.getStates();
                                PalettedContainer.Data<BlockState> data = states.data;
                                storage = data.storage();
                                strategy = states.strategy;
                                if (live) {
                                    palette = data.palette();
                                    if (sectionKeys[s] != data) {
                                        sectionKeys[s] = data;
                                        wrappers = sectionWrappers[s] = null;
                                    }
                                }
                                if (wrappers == null) {
                                    int size = data.palette().getSize();
                                    if (size <= 256) {
                                        wrappers = sectionWrappers[s] = new IBlockStateWrapper[size];
                                    }
                                }
                            }
                        }
                    }
                    int id = storage == null ? 0 : storage.get(strategy.getIndex(x, y & 15, z));
                    if (wrappers != null && id < wrappers.length && wrappers[id] != null) {
                        block = wrappers[id];
                    } else {
                        block = this.getBlockState(x, y, z);
                        if (wrappers != null && id < wrappers.length && (!live || superchunk$isWrapperOf(block, palette, id))) {
                            wrappers[id] = block;
                        }
                    }
                    if (y <= min || solidHeight != min && blockingHeight != min) {
                        break;
                    }
                    if (block != asked) {
                        asked = block;
                        isSolid = block.isSolid();
                        blocks = block.getOpacity() != 0;
                    }
                    if (solidHeight == min && isSolid) {
                        solidHeight = y;
                    }
                    if (blockingHeight == min && blocks) {
                        blockingHeight = y;
                    }
                    y--;
                }
                solid[x][z] = solidHeight;
                blocking[x][z] = blockingHeight;
            }
        }
    }

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("Distant Horizons' heightmaps by runs (ProtoChunk.getBlockState)",
                    "net.minecraft.world.level.chunk.ProtoChunk#getBlockState",
                    "net.minecraft.world.level.chunk.ChunkAccess#getSection",
                    "net.minecraft.world.level.chunk.LevelChunkSection#hasOnlyAir",
                    "net.minecraft.world.level.chunk.LevelChunkSection#getBlockState",
                    "net.minecraft.world.level.chunk.PalettedContainer#get");
            if (state != dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
                superchunk$hooks = state;
            }
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }
}
