package dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IBelowZeroRetrogen;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IBlendingData;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkSection;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkTickScheduler;
import dev.superchunk.com.ishland.c2me.base.mixin.access.ISimpleTickScheduler;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IState;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IStructurePiece;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IStructureStart;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IUpgradeData;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.utils.LithiumUtil;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.utils.StarLightUtil;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction8;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.SerializableTickContainer;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.LongStream;

@SuppressWarnings("JavadocReference")
public final class ChunkDataSerializer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final byte[] STRING_DATA_VERSION = NbtWriter.getAsciiStringBytes("DataVersion");
    private static final byte[] STRING_X_POS = NbtWriter.getAsciiStringBytes("xPos");
    private static final byte[] STRING_Y_POS = NbtWriter.getAsciiStringBytes("yPos");
    private static final byte[] STRING_Z_POS = NbtWriter.getAsciiStringBytes("zPos");
    private static final byte[] STRING_LAST_UPDATE = NbtWriter.getAsciiStringBytes("LastUpdate");
    private static final byte[] STRING_INHABITED_TIME = NbtWriter.getAsciiStringBytes("InhabitedTime");
    private static final byte[] STRING_STATUS = NbtWriter.getAsciiStringBytes("Status");
    private static final byte[] STRING_BLENDING_DATA = NbtWriter.getAsciiStringBytes("blending_data");
    private static final byte[] STRING_BELOW_ZERO_RETROGEN = NbtWriter.getAsciiStringBytes("below_zero_retrogen");
    private static final byte[] STRING_UPGRADE_DATA = NbtWriter.getAsciiStringBytes("upgrade_data");
    private static final byte[] STRING_IS_LIGHT_ON = NbtWriter.getAsciiStringBytes("isLightOn");
    private static final byte[] STRING_BLOCK_ENTITIES = NbtWriter.getAsciiStringBytes("block_entities");
    private static final byte[] STRING_PALETTE = NbtWriter.getAsciiStringBytes("palette");
    private static final byte[] STRING_DATA = NbtWriter.getAsciiStringBytes("data");
    private static final byte[] STRING_SECTIONS = NbtWriter.getAsciiStringBytes("sections");
    private static final byte[] STRING_BLOCK_STATES = NbtWriter.getAsciiStringBytes("block_states");
    private static final byte[] STRING_BIOMES = NbtWriter.getAsciiStringBytes("biomes");
    private static final byte[] STRING_BLOCK_LIGHT = NbtWriter.getAsciiStringBytes("BlockLight");
    private static final byte[] STRING_SKY_LIGHT = NbtWriter.getAsciiStringBytes("SkyLight");
    private static final byte[] STRING_OLD_NOISE = NbtWriter.getAsciiStringBytes("old_noise");
    private static final byte[] STRING_HEIGHTS = NbtWriter.getAsciiStringBytes("heights");
    private static final byte[] STRING_MIN_SECTION = NbtWriter.getAsciiStringBytes("min_section");
    private static final byte[] STRING_MAX_SECTION = NbtWriter.getAsciiStringBytes("max_section");
    private static final byte[] STRING_TARGET_STATUS = NbtWriter.getAsciiStringBytes("target_status");
    private static final byte[] STRING_MISSING_BEDROCK = NbtWriter.getAsciiStringBytes("missing_bedrock");
    private static final byte[] STRING_INDICES = NbtWriter.getAsciiStringBytes("Indices");
    private static final byte[] STRING_SIDES = NbtWriter.getAsciiStringBytes("Sides");
    private static final byte[] STRING_ENTITIES = NbtWriter.getAsciiStringBytes("entities");
    private static final byte[] STRING_LIGHTS = NbtWriter.getAsciiStringBytes("Lights");
    private static final byte[] STRING_CARVING_MASKS = NbtWriter.getAsciiStringBytes("CarvingMasks");
    private static final byte[] STRING_HEIGHTMAPS = NbtWriter.getAsciiStringBytes("Heightmaps");
    private static final byte[] STRING_POST_PROCESSING = NbtWriter.getAsciiStringBytes("PostProcessing");
    private static final byte[] STRING_BLOCK_TICKS = NbtWriter.getAsciiStringBytes("block_ticks");
    private static final byte[] STRING_FLUID_TICKS = NbtWriter.getAsciiStringBytes("fluid_ticks");
    private static final byte[] STRING_STRUCTURES = NbtWriter.getAsciiStringBytes("structures");
    private static final byte[] STRING_STARTS = NbtWriter.getAsciiStringBytes("starts");
    private static final byte[] STRING_BIG_REFERENCES = NbtWriter.getAsciiStringBytes("References");
    private static final byte[] STRING_ID = NbtWriter.getAsciiStringBytes("id");
    private static final byte[] STRING_CHUNK_X = NbtWriter.getAsciiStringBytes("ChunkX");
    private static final byte[] STRING_CHUNK_Z = NbtWriter.getAsciiStringBytes("ChunkZ");
    private static final byte[] STRING_SMALL_REFERENCES = NbtWriter.getAsciiStringBytes("references");
    private static final byte[] STRING_CHILDREN = NbtWriter.getAsciiStringBytes("Children");
    private static final byte[] STRING_INVALID = NbtWriter.getAsciiStringBytes("INVALID");
    private static final byte[] STRING_BB = NbtWriter.getAsciiStringBytes("BB");
    private static final byte[] STRING_O = NbtWriter.getAsciiStringBytes("O");
    private static final byte[] STRING_GD = NbtWriter.getAsciiStringBytes("GD");
    private static final byte[] STRING_NAME = NbtWriter.getAsciiStringBytes("Name");
    private static final byte[] STRING_PROPERTIES = NbtWriter.getAsciiStringBytes("Properties");

    private static final byte[] STRING_CHAR_BIG_Y = NbtWriter.getAsciiStringBytes("Y");
    private static final byte[] STRING_CHAR_SMALL_I = NbtWriter.getAsciiStringBytes("i");
    private static final byte[] STRING_CHAR_SMALL_P = NbtWriter.getAsciiStringBytes("p");
    private static final byte[] STRING_CHAR_SMALL_T = NbtWriter.getAsciiStringBytes("t");
    private static final byte[] STRING_CHAR_SMALL_X = NbtWriter.getAsciiStringBytes("x");
    private static final byte[] STRING_CHAR_SMALL_Y = NbtWriter.getAsciiStringBytes("y");
    private static final byte[] STRING_CHAR_SMALL_Z = NbtWriter.getAsciiStringBytes("z");

    private static final byte[] STRING_C2ME = NbtWriter.getAsciiStringBytes("C2ME");
    private static final byte[] STRING_KROPPEB = NbtWriter.getAsciiStringBytes("Kroppeb was here :); Version: 0.3.0");

    private static final byte[] STRING_C2ME_MARK_A = NbtWriter.getAsciiStringBytes("C2ME::MarkA");
    private static final byte[] STRING_MARKER_FLUID_PROTO = NbtWriter.getAsciiStringBytes("fluid:proto");
    private static final byte[] STRING_MARKER_FLUID_FULL = NbtWriter.getAsciiStringBytes("fluid:full");
    private static final byte[] STRING_MARKER_FLUID_FALLBACK = NbtWriter.getAsciiStringBytes("fluid:fallback");

    // STARLIGHT
    private static final byte[] STRING_BLOCKLIGHT_STATE_TAG = NbtWriter.getAsciiStringBytes("starlight.blocklight_state");
    private static final byte[] STRING_SKYLIGHT_STATE_TAG = NbtWriter.getAsciiStringBytes("starlight.skylight_state");
    private static final byte[] STRING_STARLIGHT_VERSION_TAG = NbtWriter.getAsciiStringBytes("starlight.light_version");
    // MUST match the vendored ScalableLux load gate (SaveUtil requires ==getLightVersion(),
    // else every chunk reloads UNLIT and is fully relit). Upstream C2ME hardcoded 8 against
    // old Starlight; read the engine's own constant so the two can never drift again.
    private static final int STARLIGHT_LIGHT_VERSION =
            dev.superchunk.ca.spottedleaf.starlight.common.util.SaveUtil.getLightVersion();

    // ScalableLux (Starlight) is compiled into this merged jar and always provides the
    // SWMRNibbleArray light format — hard-wired, no class-presence detection needed.
    private static final boolean STARLIGHT = true;

    /**
     * Mirror of {@link net.minecraft.world.level.chunk.storage.ChunkSerializer#write(ServerLevel, ChunkAccess)}
     */
    public static void write(ServerLevel world, ChunkAccess chunk, NbtWriter writer) {
        ChunkPos chunkPos = chunk.getPos();

//        System.out.printf("Serializing chunk at: %d %d%n", chunkPos.x, chunkPos.z);

        writer.putString(STRING_C2ME, STRING_KROPPEB);
        writer.putInt(STRING_DATA_VERSION, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        writer.putInt(STRING_X_POS, chunkPos.x);
        writer.putInt(STRING_Y_POS, chunk.getMinSection());
        writer.putInt(STRING_Z_POS, chunkPos.z);
        writer.putLong(STRING_LAST_UPDATE, world.getGameTime());
        writer.putLong(STRING_INHABITED_TIME, chunk.getInhabitedTime());
        writer.putString(STRING_STATUS, ((ChunkStatusAccessor) chunk.getPersistedStatus()).getIdBytes());

        BlendingData blendingData = chunk.getBlendingData();
        if (blendingData != null) {
            // Inline codec
            writer.startCompound(STRING_BLENDING_DATA);
            writeBlendingData(writer, (IBlendingData) blendingData);
            writer.finishCompound();
        }

        BelowZeroRetrogen belowZeroRetrogen = chunk.getBelowZeroRetrogen();
        if (belowZeroRetrogen != null) {
            // Inline codec
            writer.startCompound(STRING_BELOW_ZERO_RETROGEN);
            writeBelowZeroRetrogen(writer, (IBelowZeroRetrogen) (Object) belowZeroRetrogen);
            writer.finishCompound();
        }

        UpgradeData upgradeData = chunk.getUpgradeData();
        if (!upgradeData.isEmpty()) {
            // Inline serialization
            writer.startCompound(STRING_UPGRADE_DATA);
            writeUpgradeData(writer, (IUpgradeData) upgradeData);
            writer.finishCompound();
        }

        LevelChunkSection[] chunkSections = chunk.getSections();
        LevelLightEngine lightingProvider = world.getChunkSource().getLightEngine();
        Registry<Biome> biomeRegistry = world.registryAccess().registryOrThrow(Registries.BIOME);

        checkLightFlag(chunk, writer, world);

        writeSectionData(writer, chunk, chunkPos, chunkSections, lightingProvider, biomeRegistry);


        long blockEntitiesStart = writer.startList(STRING_BLOCK_ENTITIES, Tag.TAG_COMPOUND);
        int blockEntitiesCount = 0;

        // TODO: there is already a redirect here, have to copy it over
        for (BlockPos bl2 : chunk.getBlockEntitiesPos()) {
            // TODO: optimize
            CompoundTag chunkNibbleArray = chunk.getBlockEntityNbtForSaving(bl2, world.registryAccess());
            if (chunkNibbleArray != null) {
                writer.putElementEntry(chunkNibbleArray);
                blockEntitiesCount++;
            }
        }

        writer.finishList(blockEntitiesStart, blockEntitiesCount);


        if (chunk.getPersistedStatus().getChunkType() == ChunkType.PROTOCHUNK) {
            ProtoChunk j = (ProtoChunk) chunk;
            final List<CompoundTag> entities = j.getEntities();
            writer.startFixedList(STRING_ENTITIES, entities.size(), Tag.TAG_COMPOUND);
            for (CompoundTag entity : entities) {
                //noinspection deprecation
                writer.putElementEntry(entity);
            }

//            putShortListArray(j.getLightSourcesBySection(), writer, STRING_LIGHTS); // no longer exists after lighting update

            writer.startCompound(STRING_CARVING_MASKS);

            for (GenerationStep.Carving carver : GenerationStep.Carving.values()) {
                CarvingMask carvingMask = j.getCarvingMask(carver);
                if (carvingMask != null) {
                    writer.putLongArray(
                            ((GenerationStepCarverAccessor) (Object) carver).getNameBytes(),
                            carvingMask.toArray());
                }
            }

            writer.finishCompound();
        }

        serializeTicks(writer, world, chunk.getTicksForSerialization());
        ShortList[] postProcessingLists = chunk.getPostProcessing();
        putShortListArray(postProcessingLists, writer, STRING_POST_PROCESSING);


        writer.startCompound(STRING_HEIGHTMAPS);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
                writer.putLongArray(
                        ((HeightMapTypeAccessor) (Object) entry.getKey()).getNameBytes(),
                        entry.getValue().getRawData());

            }
        }
        writer.finishCompound();


        writeStructures(writer, StructurePieceSerializationContext.fromLevel(world), chunkPos, chunk.getAllStarts(), chunk.getAllReferences());
    }

    private static void checkLightFlag(ChunkAccess chunk, NbtWriter writer, ServerLevel world) {
        if (STARLIGHT) {
            // starlight also has a check to see if the "level" isn't a "serverlevel"???
            if (chunk.isLightCorrect()) {
                writer.putBoolean(STRING_IS_LIGHT_ON, false);
            }
        } else {
            if (chunk.isLightCorrect()) {
                writer.putBoolean(STRING_IS_LIGHT_ON, true);
            }
        }
    }

    private static void putShortListArray(ShortList[] data, NbtWriter writer, byte[] name) {
        writer.startFixedList(name, data.length, Tag.TAG_LIST);

        for (ShortList shortList : data) {
            if (shortList != null) {
                writer.startFixedListEntry(shortList.size(), Tag.TAG_SHORT);
                for (Short short_ : shortList) {
                    writer.putShortEntry(short_);
                }
            } else {
                writer.startFixedListEntry(0, Tag.TAG_END);
            }

        }
    }

    /**
     * Mirror section of {@link net.minecraft.world.level.chunk.storage.ChunkSerializer#write(ServerLevel, ChunkAccess)}
     */
    private static void writeSectionData(
            NbtWriter writer,
            ChunkAccess chunk,
            ChunkPos chunkPos,
            LevelChunkSection[] chunkSections,
            LevelLightEngine lightingProvider,
            Registry<Biome> biomeRegistry
    ) {
        if (STARLIGHT) {
            writeSectionDataStarlight(writer, chunk, chunkPos, chunkSections, lightingProvider, biomeRegistry);
        } else {
            writeSectionDataVanilla(writer, chunk, chunkPos, chunkSections, lightingProvider, biomeRegistry);
        }
    }

    /**
     * Mirror section of {@link net.minecraft.world.level.chunk.storage.ChunkSerializer#write(ServerLevel, ChunkAccess)}
     */
    private static void writeSectionDataVanilla(
            NbtWriter writer,
            ChunkAccess chunk,
            ChunkPos chunkPos,
            LevelChunkSection[] chunkSections,
            LevelLightEngine lightingProvider,
            Registry<Biome> biomeRegistry
    ) {
        long sectionsStart = writer.startList(STRING_SECTIONS, Tag.TAG_COMPOUND);
        int sectionCount = 0;

        for (int i = lightingProvider.getMinLightSection(); i < lightingProvider.getMaxLightSection(); ++i) {
            int index = chunk.getSectionIndexFromSectionY(i);
            boolean bl2 = index >= 0 && index < chunkSections.length;

            DataLayer blockLight = lightingProvider.getLayerListener(LightLayer.BLOCK)
                    .getDataLayerData(SectionPos.of(chunkPos, i));
            DataLayer skyLight = lightingProvider.getLayerListener(LightLayer.SKY)
                    .getDataLayerData(SectionPos.of(chunkPos, i));

            if (bl2 || blockLight != null || skyLight != null) {
                boolean hasInner = false;
                if (bl2) {
                    hasInner = true;
                    writer.compoundEntryStart();
                    IChunkSection chunkSection = (IChunkSection) chunkSections[index];

                    writeBlockStates(writer, chunkSection.getBlockStateContainer());
                    writeBiomes(writer, chunkSection.getBiomeContainer(), biomeRegistry);
                }

                if (blockLight != null && !blockLight.isEmpty()) {
                    if (!hasInner) {
                        writer.compoundEntryStart();
                        hasInner = true;
                    }
                    writer.putByteArray(STRING_BLOCK_LIGHT, blockLight.getData());
                }

                if (skyLight != null && !skyLight.isEmpty()) {
                    if (!hasInner) {
                        writer.compoundEntryStart();
                        hasInner = true;
                    }
                    writer.putByteArray(STRING_SKY_LIGHT, skyLight.getData());
                }

                if (hasInner) {
                    writer.putByte(STRING_CHAR_BIG_Y, (byte) i);
                    writer.finishCompound();
                    sectionCount++;
                }
            }
        }

        writer.finishList(sectionsStart, sectionCount);
    }

    /**
     * Mirror section of {@link net.minecraft.world.level.chunk.storage.ChunkSerializer#write(ServerLevel, ChunkAccess)}
     * with the changes by StarLight applied inline
     */
    private static void writeSectionDataStarlight(
            NbtWriter writer,
            ChunkAccess chunk,
            ChunkPos chunkPos,
            LevelChunkSection[] chunkSections,
            LevelLightEngine lightingProvider,
            Registry<Biome> biomeRegistry
    ) {
        // START DIFF
        boolean lit = chunk.isLightCorrect();
        ChunkStatus status = chunk.getPersistedStatus();
        boolean shouldWrite = lit && status.isOrAfter(ChunkStatus.LIGHT);
        var blockNibbles = StarLightUtil.getBlockNibbles(chunk);
        var skyNibbles = StarLightUtil.getSkyNibbles(chunk);
        int minSection;
        // END DIFF

        long sectionsStart = writer.startList(STRING_SECTIONS, Tag.TAG_COMPOUND);
        int sectionCount = 0;

        for (int i = minSection = lightingProvider.getMinLightSection(); i < lightingProvider.getMaxLightSection(); ++i) {
            int index = chunk.getSectionIndexFromSectionY(i);
            boolean bl2 = index >= 0 && index < chunkSections.length;

            // START DIFF
//
//            DataLayer blockLight = lightingProvider.getLayerListener(LightLayer.BLOCK)
//                    .getDataLayerData(SectionPos.of(chunkPos, i));
//            DataLayer skyLight = lightingProvider.getLayerListener(LightLayer.SKY)
//                    .getDataLayerData(SectionPos.of(chunkPos, i));

            var blockNibble = shouldWrite ? StarLightUtil.getSaveState(blockNibbles[i - minSection]) : null;
            var skyNibble = shouldWrite ? StarLightUtil.getSaveState(skyNibbles[i - minSection]) : null;

            if (bl2 || blockNibble != null || skyNibble != null) {
                // END DIFF
                boolean hasInner = false;
                if (bl2) {
                    hasInner = true;
                    writer.compoundEntryStart();
                    IChunkSection chunkSection = (IChunkSection) chunkSections[index];

                    writeBlockStates(writer, chunkSection.getBlockStateContainer());
                    writeBiomes(writer, chunkSection.getBiomeContainer(), biomeRegistry);
                }

                // START DIFF
//                if (blockLight != null && !blockLight.isEmpty()) {
//                    if (!hasInner) {
//                        writer.compoundEntryStart();
//                        hasInner = true;
//                    }
//                    writer.putByteArray(STRING_BLOCK_LIGHT, blockLight.getData());
//                }

//                if (skyLight != null && !skyLight.isEmpty()) {
//                    if (!hasInner) {
//                        writer.compoundEntryStart();
//                        hasInner = true;
//                    }
//                    writer.putByteArray(STRING_SKY_LIGHT, skyLight.getData());
//                }

                // Light-only boundary sections (below/above world, bl2==false) MUST still open a
                // section compound before any named tag is written — mirroring SaveUtil, which
                // creates a {Y}-only compound for exactly this case, and the vanilla branch above,
                // which guards every light write with compoundEntryStart. Without the guard the
                // nibble tags leak into the TAG_LIST payload outside any compound: leading strays
                // merge into the FIRST real section (misattributed Y) and trailing strays parse as
                // junk root keys — silently corrupting boundary light on every save.
                if (blockNibble != null) {
                    if (!hasInner) {
                        writer.compoundEntryStart();
                        hasInner = true;
                    }
                    if (blockNibble.getData() != null) {
                        writer.putByteArray(STRING_BLOCK_LIGHT, blockNibble.getData());
                    }
                    writer.putInt(STRING_BLOCKLIGHT_STATE_TAG, blockNibble.getState());
                }

                if (skyNibble != null) {
                    if (!hasInner) {
                        writer.compoundEntryStart();
                        hasInner = true;
                    }
                    if (skyNibble.getData() != null) {
                        writer.putByteArray(STRING_SKY_LIGHT, skyNibble.getData());
                    }
                    writer.putInt(STRING_SKYLIGHT_STATE_TAG, skyNibble.getState());
                }

                // END DIFF


                if (hasInner) {
                    writer.putByte(STRING_CHAR_BIG_Y, (byte) i);
                    writer.finishCompound();
                    sectionCount++;
                }
            }
        }

        writer.finishList(sectionsStart, sectionCount);

        if (lit) {
            writer.putInt(STRING_STARLIGHT_VERSION_TAG, STARLIGHT_LIGHT_VERSION);
        }
    }

    /**
     * mirror of the block_states palette codec.
     */
    @SuppressWarnings("unchecked")
    private static void writeBlockStates(NbtWriter writer, PalettedContainer<BlockState> blockStateContainer) {
        writer.startCompound(STRING_BLOCK_STATES);
        // todo can this be optimized?
        // todo: does this conflict with lithium by any chance?
        var data = blockStateContainer.pack(
                Block.BLOCK_STATE_REGISTRY,
                PalettedContainer.Strategy.SECTION_STATES);

        List<BlockState> paletteEntries = data.paletteEntries();
        writer.startFixedList(STRING_PALETTE, paletteEntries.size(), Tag.TAG_COMPOUND);

        for (BlockState paletteEntry : paletteEntries) {
            writer.compoundEntryStart();
            writer.putRegistry(STRING_NAME, BuiltInRegistries.BLOCK, paletteEntry.getBlock());
            if (!paletteEntry.getValues().isEmpty()) {
                // TODO: optimize this
                writer.putElement(STRING_PROPERTIES, ((IState<BlockState>) paletteEntry).getCodec().codec()
                        .encodeStart(NbtOps.INSTANCE, paletteEntry)
                        .getOrThrow(net.minecraft.world.level.chunk.storage.ChunkSerializer.ChunkReadException::new));
            }
            writer.finishCompound();
        }

        Optional<LongStream> storage = data.storage();
        //noinspection OptionalIsPresent
        if (storage.isPresent()) {
            writer.putLongArray(STRING_DATA, storage.get().toArray());
        }
        writer.finishCompound();
    }

    /**
     * mirror of the biomes palette codec.
     */
    private static void writeBiomes(NbtWriter writer, PalettedContainerRO<Holder<Biome>> biomeContainer, Registry<Biome> biomeRegistry) {
        writer.startCompound(STRING_BIOMES);
        // todo can this be optimized?
        // todo: does this conflict with lithium by any chance?
        var data = biomeContainer.pack(
                biomeRegistry.asHolderIdMap(),
                PalettedContainer.Strategy.SECTION_BIOMES);

        List<Holder<Biome>> paletteEntries = data.paletteEntries();
        writer.startFixedList(STRING_PALETTE, paletteEntries.size(), Tag.TAG_STRING);

        for (Holder<Biome> paletteEntry : paletteEntries) {
            writer.putRegistryEntry(paletteEntry);
        }

        Optional<LongStream> storage = data.storage();
        //noinspection OptionalIsPresent
        if (storage.isPresent()) {
            writer.putLongArray(STRING_DATA, storage.get().toArray());
        }
        writer.finishCompound();
    }

    /**
     * mirror of {@link BlendingData#CODEC}
     */
    private static void writeBlendingData(NbtWriter writer, IBlendingData blendingData) {
        LevelHeightAccessor oldHeightLimit = blendingData.getOldHeightLimit();
        writer.putInt(STRING_MIN_SECTION, oldHeightLimit.getMinSection());
        writer.putInt(STRING_MAX_SECTION, oldHeightLimit.getMaxSection());

        double[] heights = blendingData.getSurfaceHeights();
        for (double d : heights) {
            if (d != Double.MAX_VALUE) {
                writer.putDoubles(STRING_HEIGHTS, heights);
                return;
            }
        }

        // set to empty list
        writer.startFixedList(STRING_HEIGHTS, 0, Tag.TAG_DOUBLE);
    }

    /**
     * mirror of {@link BelowZeroRetrogen#CODEC}
     */
    private static void writeBelowZeroRetrogen(NbtWriter writer, IBelowZeroRetrogen belowZeroRetrogen) {
        writer.putRegistry(STRING_TARGET_STATUS, BuiltInRegistries.CHUNK_STATUS, belowZeroRetrogen.invokeGetTargetStatus());

        BitSet missingBedrock = belowZeroRetrogen.getMissingBedrock();
        if (!missingBedrock.isEmpty()) {
            writer.putLongArray(STRING_MISSING_BEDROCK, missingBedrock.toLongArray());
        }
    }


    private static void writeUpgradeData(NbtWriter writer, IUpgradeData upgradeData) {
        long indicesStart = -1;
        int indicesCount = 0;

        int[][] centerIndicesToUpgrade = upgradeData.getCenterIndicesToUpgrade();

        for (int i = 0; i < centerIndicesToUpgrade.length; ++i) {
            if (centerIndicesToUpgrade[i] != null && centerIndicesToUpgrade[i].length != 0) {
                String string = String.valueOf(i);
                if (indicesStart == -1) {
                    indicesStart = writer.startList(STRING_INDICES, Tag.TAG_INT_ARRAY);
                }
                indicesCount++;
                // TODO: cache this
                writer.putIntArray(NbtWriter.getAsciiStringBytes(string), centerIndicesToUpgrade[i]);
            }
        }

        if (indicesStart != -1) {
            writer.finishList(indicesStart, indicesCount);
        }

        int i = 0;

        for (Direction8 eightWayDirection : upgradeData.getSidesToUpgrade()) {
            i |= 1 << eightWayDirection.ordinal();
        }

        writer.putByte(STRING_SIDES, (byte) i);
    }


    /**
     * mirror of {@link net.minecraft.world.level.chunk.storage.ChunkSerializer#saveTicks(ServerLevel, CompoundTag, ChunkAccess.TicksToSave)}
     */
    private static void serializeTicks(NbtWriter writer, ServerLevel world, ChunkAccess.TicksToSave tickSchedulers) {
        long time = world.getLevelData().getGameTime();

        writeTicks(writer, time, tickSchedulers.blocks(), BuiltInRegistries.BLOCK, STRING_BLOCK_TICKS);
        writeTicks(writer, time, tickSchedulers.fluids(), BuiltInRegistries.FLUID, STRING_FLUID_TICKS);
    }


    /**
     * mirrors of {@link net.minecraft.world.ticks.ProtoChunkTicks#save(long, Function)},
     * {@link net.minecraft.world.ticks.LevelChunkTicks#save(long, Function)} and
     * {@link SavedTick#save(Function)}
     */
    private static <T> void writeTicks(
            NbtWriter writer,
            long time,
            SerializableTickContainer<T> scheduler,
            Registry<T> reg,
            byte[] key
    ) {
        if (scheduler instanceof ISimpleTickScheduler<T> simpleTickSchedulerAccessor) {
            final List<SavedTick<T>> scheduledTicks = simpleTickSchedulerAccessor.getScheduledTicks();
            writer.startFixedList(key, scheduledTicks.size(), Tag.TAG_COMPOUND);
            for (SavedTick<T> scheduledTick : scheduledTicks) {
                writeTick(writer, scheduledTick, reg);
            }
        } else if (scheduler instanceof IChunkTickScheduler<T> chunkTickSchedulerAccess) {

            int size = 0;
            long list = writer.startList(key, Tag.TAG_COMPOUND);

            final @Nullable List<SavedTick<T>> scheduledTicks = chunkTickSchedulerAccess.getTicks();

            if (scheduledTicks != null) {
                size += scheduledTicks.size();

                for (SavedTick<T> scheduledTick : scheduledTicks) {
                    writeTick(writer, scheduledTick, reg);
                }
            }

            if (LithiumUtil.IS_LITHIUM_TICK_QUEUE_ACTIVE) {
                final Collection<Collection<ScheduledTick<T>>> tickQueues = LithiumUtil.getTickQueueCollection(chunkTickSchedulerAccess);

                for (Collection<ScheduledTick<T>> tickQueue : tickQueues) {
                    size += tickQueue.size();
                    for (ScheduledTick<T> orderedTick : tickQueue) {
                        writeOrderedTick(writer, orderedTick, time, reg);
                    }
                }
            } else {
                final Collection<ScheduledTick<T>> tickQueue = chunkTickSchedulerAccess.getTickQueue();
                size += tickQueue.size();

                for (ScheduledTick<T> orderedTick : tickQueue) {
                    writeOrderedTick(writer, orderedTick, time, reg);
                }
            }

            writer.finishList(list, size);
        } else {
            // FALLBACK?
            //noinspection deprecation
            writer.putElement(key, scheduler.save(time, block -> reg.getKey(block).toString()));
        }
    }

    private static <T> void writeOrderedTick(NbtWriter writer, ScheduledTick<T> orderedTick, long time, Registry<T> reg) {
        writer.compoundEntryStart();
        writer.putRegistry(STRING_CHAR_SMALL_I, reg, orderedTick.type());
        writeGenericTickData(writer, orderedTick.pos(), (int) (orderedTick.triggerTick() - time), orderedTick.priority());
        writer.finishCompound();
    }

    private static <T> void writeTick(NbtWriter writer, SavedTick<T> scheduledTick, Registry<T> reg) {
        writer.compoundEntryStart();
        writer.putRegistry(STRING_CHAR_SMALL_I, reg, scheduledTick.type());
        writeGenericTickData(writer, scheduledTick);
        writer.finishCompound();
    }

    private static void writeGenericTickData(
            NbtWriter writer,
            SavedTick<?> scheduledTick) {
        writeGenericTickData(writer, scheduledTick.pos(), scheduledTick.delay(), scheduledTick.priority());
    }

    private static void writeGenericTickData(
            NbtWriter writer,
            BlockPos pos,
            int delay,
            TickPriority priority) {
        writer.putInt(STRING_CHAR_SMALL_X, pos.getX());
        writer.putInt(STRING_CHAR_SMALL_Y, pos.getY());
        writer.putInt(STRING_CHAR_SMALL_Z, pos.getZ());
        writer.putInt(STRING_CHAR_SMALL_T, delay);
        writer.putInt(STRING_CHAR_SMALL_P, priority.getValue());
    }

    /**
     * mirror of {@link net.minecraft.world.level.chunk.storage.ChunkSerializer#packStructureData(StructurePieceSerializationContext, ChunkPos, Map, Map)}
     */
    private static void writeStructures(
            NbtWriter writer,
            StructurePieceSerializationContext context,
            ChunkPos pos,
            Map<Structure, StructureStart> starts,
            Map<Structure, LongSet> references
    ) {
        writer.startCompound(STRING_STRUCTURES);
        writer.startCompound(STRING_STARTS);

        Registry<Structure> configuredStructureFeatureRegistry = context.registryAccess().registryOrThrow(Registries.STRUCTURE);

        for (var entry : starts.entrySet()) {
            writer.startCompound(NbtWriter.getNameBytesFromRegistry(configuredStructureFeatureRegistry, entry.getKey()));
            IStructureStart value = cast(entry.getValue());
            writeStructureStart(writer, value, context, pos);
            writer.finishCompound();
        }
        writer.finishCompound();

        writer.startCompound(STRING_BIG_REFERENCES);
        for (var entry : references.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            writer.putLongArray(NbtWriter.getNameBytesFromRegistry(configuredStructureFeatureRegistry, entry.getKey()), entry.getValue());
        }
        writer.finishCompound();

        writer.finishCompound();
    }


    /**
     * mirror of {@link StructureStart#createTag(StructurePieceSerializationContext, ChunkPos)}
     * <p>
     * section mirror of {@link PiecesContainer#save(StructurePieceSerializationContext)}
     */
    private static void writeStructureStart(NbtWriter writer, IStructureStart structureStart, StructurePieceSerializationContext context, ChunkPos pos) {
        final PiecesContainer children = structureStart.getChildren();
        if (children.isEmpty()) {
            writer.putString(STRING_ID, STRING_INVALID);
            return;
        }

        writer.putRegistry(STRING_ID, context.registryAccess().registryOrThrow(Registries.STRUCTURE), structureStart.getStructure());
        writer.putInt(STRING_CHUNK_X, pos.x);
        writer.putInt(STRING_CHUNK_Z, pos.z);
        writer.putInt(STRING_SMALL_REFERENCES, structureStart.getReferences());

        // section: PiecesContainer#save(StructurePieceSerializationContext)
        writer.startFixedList(STRING_CHILDREN, children.pieces().size(), Tag.TAG_COMPOUND);
        for (StructurePiece piece : children.pieces()) {
            writer.putElementEntry(piece.createTag(context));
        }
    }


    @SuppressWarnings("unchecked")
    @Contract("null -> null; !null -> !null")
    private static <T> T cast(Object entry) {
        return (T) entry;
    }
}
