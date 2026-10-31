package dev.superchunk.mixin.compat;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkProcessingEvent;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_forge;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Distant Horizons' LOD builder reads a generated chunk by runs of equal blocks instead of block by block.
 *
 * <p>{@code LodDataBuilder.createFromChunk} turns each of a chunk's 256 columns into DH data points:
 * from the surface down it asks DH's chunk wrapper for every block, its biome and two light values,
 * and starts a data point where the block or the biome changes. A block read goes through the chunk,
 * its section and palette into DH's wrapper map, and each light read takes a lock: about a tenth of
 * DH's world-gen CPU, for columns that are mostly long runs of stone. This builds the same data
 * source with DH's own calls, and leaves out only reads whose answer it already has:
 * <ul>
 *   <li>a block is read through DH once per section and palette id: the same id is the same block
 *   state, and DH's wrapper for a block depends on its state alone (the wrapper it was given as its
 *   guess is the one it would look up);</li>
 *   <li>the biome once per 4x4x4 cell, which 16 columns share (DH's answer depends on the cell alone);</li>
 *   <li>a data point's id once per biome and block wrapper pair (DH's id map gives a pair the id it
 *   was first given);</li>
 *   <li>the two light values only where a data point starts, the one place DH uses them, and on a
 *   column's first step, where DH's first read creates the chunk's sky light storage.</li>
 * </ul>
 * Where the block and biome are the very objects of the current data point, DH's {@code equals}
 * would return true at once ({@code this == obj}), so the step is skipped. Every data point, id,
 * light value and chunk-processing event is DH's, in DH's order.
 *
 * <p>Only for vanilla {@code ProtoChunk}s (what DH's world generator builds), and only while no other
 * mod hooks the vanilla block reads it leaves out. Applied only when the DH methods this relies on
 * hash to the version it was checked against ({@link dev.superchunk.compat.DhFastPaths}). Kill
 * switch: {@code -Dsuperchunk.dh.fastPaths=false}; {@code -Dsuperchunk.dh.lodVerify=true} also runs
 * DH's own builder on every chunk and logs any difference.
 */
// @Pseudo: the target is a Distant Horizons class, absent from the compile classpath.
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder", remap = false)
public abstract class MixinDhLodBuilderRuns {

    @Shadow
    @Final
    private static DhLogger LOGGER;

    @Shadow
    @Final
    private static IBlockStateWrapper AIR;

    @Shadow
    private static boolean getTopErrorLogged;

    @Shadow
    private static int getXOrZSectionPosFromChunkPos(int chunkXOrZPos) {
        throw new AssertionError();
    }

    @Shadow
    public static FullDataSourceV2 createFromChunk(ILevelWrapper levelWrapper, IChunkWrapper chunkWrapper) {
        throw new AssertionError();
    }

    @Unique
    private static final boolean SUPERCHUNK$VERIFY = Boolean.getBoolean("superchunk.dh.lodVerify");

    @Unique
    private static final Logger SUPERCHUNK$LOG = LoggerFactory.getLogger("SuperChunk-Compat");

    /** Wrapper pairs remembered per chunk (a power of two); later pairs ask DH's id map each time. */
    @Unique
    private static final int SUPERCHUNK$PAIRS = 128;

    /** Palettes up to this size keep DH's wrapper per id; a section on the global palette asks DH per block. */
    @Unique
    private static final int SUPERCHUNK$MAX_PALETTE = 256;

    /** ForeignHooks state for the vanilla reads this leaves out. */
    @Unique
    private static volatile byte superchunk$hooks;

    /** Set while verify mode runs DH's own builder, which then must not take this path. */
    @Unique
    private static final ThreadLocal<Boolean> SUPERCHUNK$IN_DH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Unique
    private static final LongAdder SUPERCHUNK$CHECKED = new LongAdder();

    @Unique
    private static final LongAdder SUPERCHUNK$MISMATCHES = new LongAdder();

    @Unique
    private static final AtomicInteger SUPERCHUNK$REPORTED = new AtomicInteger();

    @Inject(method = "createFromChunk", at = @At("HEAD"), cancellable = true)
    private static void superchunk$byRuns(ILevelWrapper levelWrapper, IChunkWrapper chunkWrapper,
                                          CallbackInfoReturnable<FullDataSourceV2> cir) {
        // DH asserts this first; its own code throws for an unlit chunk.
        if (!(chunkWrapper instanceof ChunkWrapper_forge wrapper) || !chunkWrapper.isDhBlockLightingCorrect()) {
            return;
        }
        ChunkAccess chunk = wrapper.getChunk();
        if (chunk == null || chunk.getClass() != ProtoChunk.class || !superchunk$unhooked()) {
            return;
        }
        if (!SUPERCHUNK$VERIFY) {
            cir.setReturnValue(superchunk$build(levelWrapper, chunkWrapper, chunk));
            return;
        }
        if (SUPERCHUNK$IN_DH.get()) {
            return;
        }
        FullDataSourceV2 fast = superchunk$build(levelWrapper, chunkWrapper, chunk);
        FullDataSourceV2 dh;
        SUPERCHUNK$IN_DH.set(Boolean.TRUE);
        try {
            dh = createFromChunk(levelWrapper, chunkWrapper);
        } finally {
            SUPERCHUNK$IN_DH.set(Boolean.FALSE);
        }
        SUPERCHUNK$CHECKED.increment();
        String difference = superchunk$difference(fast, dh);
        if (difference != null) {
            SUPERCHUNK$MISMATCHES.increment();
            if (SUPERCHUNK$REPORTED.getAndIncrement() < 10) {
                SUPERCHUNK$LOG.warn("[SuperChunk-Compat] [dh] LOD runs VERIFY MISMATCH at chunk {}: {}", chunkWrapper.getChunkPos(), difference);
            }
        }
        if (SUPERCHUNK$CHECKED.sum() % 4096 == 0) {
            long m = SUPERCHUNK$MISMATCHES.sum();
            SUPERCHUNK$LOG.info("[SuperChunk-Compat] [dh] LOD runs VERIFY: checked={} mismatches={} -> {}",
                    SUPERCHUNK$CHECKED.sum(), m, m == 0 ? "PASS" : "FAIL");
        }
        if (dh != null) {
            dh.close();
        }
        cir.setReturnValue(fast);
    }

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("Distant Horizons' LOD builder by runs (ProtoChunk.getBlockState)",
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

    /** DH's {@code createFromChunk}, reading only what it does not already know (see the class comment). */
    @Unique
    private static FullDataSourceV2 superchunk$build(ILevelWrapper levelWrapper, IChunkWrapper chunkWrapper, ChunkAccess chunk) {
        int chunkPosX = chunkWrapper.getChunkPos().getX();
        int chunkPosZ = chunkWrapper.getChunkPos().getZ();
        long pos = DhSectionPos.encode((byte) 6, getXOrZSectionPosFromChunkPos(chunkPosX), getXOrZSectionPosFromChunkPos(chunkPosZ));
        FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos);
        dataSource.isEmpty = false;
        dataSource.applyToParent = true;
        int chunkOffsetX = (chunkPosX & 3) * 16;
        int chunkOffsetZ = (chunkPosZ & 3) * 16;
        EDhApiWorldCompressionMode worldCompressionMode = Config.Common.LodBuilding.worldCompression.get();
        try {
            IMutableBlockPosWrapper mcBlockPos = chunkWrapper.getMutableBlockPosWrapper();
            IBlockStateWrapper previousBlockState = null;
            DhApiChunkProcessingEvent.EventParam event = new DhApiChunkProcessingEvent.EventParam(levelWrapper, chunkPosX, chunkPosZ);
            int minBuildHeight = chunkWrapper.getMinNonEmptyHeight();
            int exclusiveMaxBuildHeight = chunkWrapper.getExclusiveMaxBuildHeight();
            int inclusiveMinBuildHeight = chunkWrapper.getInclusiveMinBuildHeight();
            int dataCapacity = chunkWrapper.getHeight() / 4;
            LevelChunkSection[] sections = chunk.getSections();
            int minSection = chunk.getMinSection();
            FullDataPointIdMap mapping = dataSource.mapping;
            // Per chunk: DH's biome per cell, its wrapper per section and palette id, its id per wrapper pair.
            int minCellY = inclusiveMinBuildHeight >> 2;
            int cellsY = (exclusiveMaxBuildHeight >> 2) - minCellY;
            IBiomeWrapper[] cellBiomes = new IBiomeWrapper[16 * Math.max(cellsY, 0)];
            IBlockStateWrapper[][] sectionWrappers = new IBlockStateWrapper[sections.length][];
            IBiomeWrapper[] pairBiomes = new IBiomeWrapper[SUPERCHUNK$PAIRS];
            IBlockStateWrapper[] pairBlocks = new IBlockStateWrapper[SUPERCHUNK$PAIRS];
            int[] pairIds = new int[SUPERCHUNK$PAIRS];
            int pairCount = 0;

            for (int relX = 0; relX < 16; relX++) {
                for (int relZ = 0; relZ < 16; relZ++) {
                    int columnX = relX + chunkOffsetX;
                    int columnZ = relZ + chunkOffsetZ;
                    LongArrayList longs = dataSource.getColumnAtRelPos(columnX, columnZ);
                    if (longs == null) {
                        longs = new LongArrayList(dataCapacity);
                    } else {
                        longs.clear();
                    }
                    int lastY = exclusiveMaxBuildHeight;
                    IBiomeWrapper currentBiome = chunkWrapper.getBiome(relX, exclusiveMaxBuildHeight, relZ);
                    IBlockStateWrapper currentBlockState = AIR;
                    int mappedId = mapping.addIfNotPresentAndGetId(currentBiome, currentBlockState);
                    byte blockLight = 0;
                    byte skyLight = 15;
                    int y = Math.max(chunkWrapper.getLightBlockingHeightMapValue(relX, relZ), chunkWrapper.getSolidHeightMapValue(relX, relZ));
                    IBlockStateWrapper topBlockState = previousBlockState = chunkWrapper.getBlockState(relX, y, relZ, mcBlockPos, previousBlockState);
                    while (!topBlockState.isAir() && y < exclusiveMaxBuildHeight) {
                        try {
                            topBlockState = previousBlockState = chunkWrapper.getBlockState(relX, ++y, relZ, mcBlockPos, previousBlockState);
                        } catch (Exception e) {
                            if (!getTopErrorLogged) {
                                LOGGER.warn("Unexpected issue in LodDataBuilder, future errors won't be logged. Chunk [" + chunkWrapper.getChunkPos()
                                        + "] with max height: [" + exclusiveMaxBuildHeight + "] had issue getting block at pos [" + relX + "," + y
                                        + "," + relZ + "] error: " + e.getMessage(), e);
                                getTopErrorLogged = true;
                            }
                            y--;
                            break;
                        }
                    }

                    // DH reads both light values on every step; the first step's read is kept for a data point starting there.
                    int firstY = y;
                    byte firstBlockLight = 0;
                    byte firstSkyLight = 0;
                    if (y >= minBuildHeight) {
                        firstBlockLight = (byte) chunkWrapper.getDhBlockLight(relX, y + 1, relZ);
                        firstSkyLight = (byte) chunkWrapper.getDhSkyLight(relX, y + 1, relZ);
                    }
                    int cellY = Integer.MIN_VALUE;
                    IBiomeWrapper cellBiome = null;
                    int cellColumn = ((relX >> 2) << 2 | relZ >> 2) * cellsY - minCellY;
                    int section = Integer.MIN_VALUE;
                    IBlockStateWrapper[] wrappers = null; // DH's wrapper per palette id in this section; null outside or for a large palette
                    BitStorage storage = null;
                    PalettedContainer.Strategy strategy = null;
                    boolean forceSingleBlock = false;
                    for (; y >= minBuildHeight; y--) {
                        if (y >> 2 != cellY) {
                            cellY = y >> 2;
                            if (y >= inclusiveMinBuildHeight && y < exclusiveMaxBuildHeight) {
                                int cell = cellColumn + cellY;
                                cellBiome = cellBiomes[cell];
                                if (cellBiome == null) {
                                    cellBiome = cellBiomes[cell] = chunkWrapper.getBiome(relX, y, relZ);
                                }
                            } else {
                                cellBiome = chunkWrapper.getBiome(relX, y, relZ);
                            }
                        }
                        IBiomeWrapper newBiome = cellBiome;

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
                                        if (size <= SUPERCHUNK$MAX_PALETTE) {
                                            wrappers = sectionWrappers[s] = new IBlockStateWrapper[size];
                                        }
                                    }
                                }
                            }
                        }
                        int id = storage == null ? 0 : storage.get(strategy.getIndex(relX, y & 15, relZ));
                        IBlockStateWrapper newBlockState;
                        if (wrappers != null && id < wrappers.length && wrappers[id] != null) {
                            newBlockState = wrappers[id];
                        } else {
                            newBlockState = previousBlockState = chunkWrapper.getBlockState(relX, y, relZ, mcBlockPos, previousBlockState);
                            if (wrappers != null && id < wrappers.length) {
                                wrappers[id] = newBlockState;
                            }
                        }

                        if (!forceSingleBlock && newBiome == currentBiome && newBlockState == currentBlockState && newBiome != null) {
                            continue;
                        }
                        if (newBiome.equals(currentBiome) && newBlockState.equals(currentBlockState) && !forceSingleBlock) {
                            continue;
                        }
                        byte newBlockLight;
                        byte newSkyLight;
                        if (y == firstY) {
                            newBlockLight = firstBlockLight;
                            newSkyLight = firstSkyLight;
                        } else {
                            newBlockLight = (byte) chunkWrapper.getDhBlockLight(relX, y + 1, relZ);
                            newSkyLight = (byte) chunkWrapper.getDhSkyLight(relX, y + 1, relZ);
                        }
                        forceSingleBlock = !currentBlockState.isAir()
                                && !currentBlockState.isSolid()
                                && !currentBlockState.isLiquid()
                                && currentBlockState.getOpacity() != 16;
                        event.updateForPosition(relX, y, relZ, newBlockState, newBiome);
                        ApiEventInjector.INSTANCE.fireAllEvents(DhApiChunkProcessingEvent.class, event);
                        if (event.getBlockOverride() != null) {
                            newBlockState = (IBlockStateWrapper) event.getBlockOverride();
                        }
                        if (event.getBiomeOverride() != null) {
                            newBiome = (IBiomeWrapper) event.getBiomeOverride();
                        }
                        longs.add(FullDataPointUtil.encode(mappedId, lastY - y, y + 1 - inclusiveMinBuildHeight, blockLight, skyLight));
                        currentBiome = newBiome;
                        currentBlockState = newBlockState;
                        int slot = (System.identityHashCode(newBlockState) * 31 + System.identityHashCode(newBiome)) & (SUPERCHUNK$PAIRS - 1);
                        while (true) {
                            if (pairBlocks[slot] == null) {
                                mappedId = mapping.addIfNotPresentAndGetId(newBiome, newBlockState);
                                if (pairCount < SUPERCHUNK$PAIRS * 3 / 4) {
                                    pairBiomes[slot] = newBiome;
                                    pairBlocks[slot] = newBlockState;
                                    pairIds[slot] = mappedId;
                                    pairCount++;
                                }
                                break;
                            }
                            if (pairBlocks[slot] == newBlockState && pairBiomes[slot] == newBiome) {
                                mappedId = pairIds[slot];
                                break;
                            }
                            slot = (slot + 1) & (SUPERCHUNK$PAIRS - 1);
                        }
                        blockLight = newBlockLight;
                        skyLight = newSkyLight;
                        lastY = y;
                        if (dataSource.isEmpty && newBlockState != null && !newBlockState.isAir()) {
                            dataSource.isEmpty = false;
                        }
                    }
                    longs.add(FullDataPointUtil.encode(mappedId, lastY - y, y + 1 - inclusiveMinBuildHeight, blockLight, skyLight));
                    dataSource.setSingleColumn(longs, columnX, columnZ, EDhApiWorldGenerationStep.LIGHT, worldCompressionMode);
                }
            }
            return dataSource;
        } catch (DataCorruptedException e) {
            LOGGER.error("Unable to convert chunk at pos [" + chunkWrapper.getChunkPos() + "] to an LOD. Error: " + e.getMessage(), e);
            return null;
        }
    }

    /** Where two built data sources differ, or null when they are the same. */
    @Unique
    private static String superchunk$difference(FullDataSourceV2 a, FullDataSourceV2 b) {
        if (a == null || b == null) {
            return a == b ? null : "one is null";
        }
        if (a.isEmpty != b.isEmpty || !Objects.equals(a.applyToParent, b.applyToParent)) {
            return "flags";
        }
        if (!a.columnGenerationSteps.equals(b.columnGenerationSteps) || !a.columnWorldCompressionMode.equals(b.columnWorldCompressionMode)) {
            return "column steps or compression";
        }
        for (int i = 0; i < a.dataPoints.length; i++) {
            if (!Objects.equals(a.dataPoints[i], b.dataPoints[i])) {
                return "column " + i + ": " + a.dataPoints[i] + " vs " + b.dataPoints[i];
            }
        }
        if (a.mapping.size() != b.mapping.size()) {
            return "mapping size " + a.mapping.size() + " vs " + b.mapping.size();
        }
        for (int id = 0; id < a.mapping.size(); id++) {
            if (!Objects.equals(a.mapping.getBiomeWrapper(id), b.mapping.getBiomeWrapper(id))
                    || !Objects.equals(a.mapping.getBlockStateWrapper(id), b.mapping.getBlockStateWrapper(id))) {
                return "mapping id " + id;
            }
        }
        return null;
    }
}
