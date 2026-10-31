package dev.superchunk.mixin.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
 * <p>Only for vanilla {@code ProtoChunk}s and while no other mod hooks the reads it leaves out.
 * Applied only when the DH methods this relies on hash to the version it was checked against
 * ({@link dev.superchunk.compat.DhFastPaths}). Kill switch {@code -Dsuperchunk.dh.fastPaths=false};
 * {@code -Dsuperchunk.dh.lodVerify=true} also builds DH's own maps and logs any difference.
 */
// @Pseudo: the target is a Distant Horizons class, absent from the compile classpath.
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_forge", remap = false)
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

    @Inject(method = "createDhHeightMaps", at = @At("HEAD"), cancellable = true)
    private void superchunk$byRuns(CallbackInfo ci) {
        ChunkAccess chunk = this.chunk;
        if (chunk == null || chunk.getClass() != ProtoChunk.class || !superchunk$unhooked()) {
            return;
        }
        if (SUPERCHUNK$VERIFY && SUPERCHUNK$IN_DH.get()) {
            return;
        }
        if (!heightmapThreadWarningLogged && !DhApi.isDhThread()) {
            heightmapThreadWarningLogged = true;
            LOGGER.warn("ChunkWrapper Height maps created on non-DH thread [" + Thread.currentThread().getName() + "]. This may cause stuttering.");
        }
        int[][] solid = new int[16][16];
        int[][] blocking = new int[16][16];
        this.solidHeightMap = solid;
        this.lightBlockingHeightMap = blocking;
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSection();
        IBlockStateWrapper[][] sectionWrappers = new IBlockStateWrapper[sections.length][];
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
                IBlockStateWrapper block;
                IBlockStateWrapper asked = null; // the wrapper whose answers are below
                boolean isSolid = false;
                boolean blocks = false;
                while (true) {
                    int s = (y >> 4) - minSection;
                    if (s != section) {
                        section = s;
                        storage = null;
                        wrappers = null;
                        if (s >= 0 && s < sections.length) {
                            wrappers = sectionWrappers[s];
                            LevelChunkSection levelSection = sections[s];
                            if (levelSection.hasOnlyAir()) {
                                if (wrappers == null) {
                                    wrappers = sectionWrappers[s] = new IBlockStateWrapper[1];
                                }
                            } else {
                                PalettedContainer<BlockState> states = levelSection.getStates();
                                PalettedContainer.Data<BlockState> data = states.data;
                                storage = data.storage();
                                strategy = states.strategy;
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
                        if (wrappers != null && id < wrappers.length) {
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
        ci.cancel();
        if (SUPERCHUNK$VERIFY) {
            SUPERCHUNK$IN_DH.set(Boolean.TRUE);
            try {
                this.createDhHeightMaps();
            } finally {
                SUPERCHUNK$IN_DH.set(Boolean.FALSE);
            }
            SUPERCHUNK$CHECKED.increment();
            if (!Arrays.deepEquals(solid, this.solidHeightMap) || !Arrays.deepEquals(blocking, this.lightBlockingHeightMap)) {
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
            // DH's own maps stay; they are equal unless the line above says otherwise.
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
