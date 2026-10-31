package dev.superchunk.compat;

import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gate for the patches that speed up Distant Horizons' own code ({@code mixin.compat.MixinDh*}
 * other than the generation-step seam).
 *
 * <p>Each patch replaces a DH method, or skips work only because of what DH's code does today. So
 * each one lists the DH methods it relies on with their {@link ForeignBytecode} hashes in the DH
 * versions it was checked against, and applies only when every one of them matches. A DH update
 * that changes any of those methods leaves the patch out (one INFO line names it) and DH runs its
 * own code, as without SuperChunk. Kill switch: {@code -Dsuperchunk.dh.fastPaths=false}.
 */
public final class DhFastPaths {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.dh.fastPaths", "true"));

    private static final String REGION =
            "com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion_forge";
    private static final String CHUNK_WRAPPER = "com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_forge";
    private static final String DATA_SOURCE = "com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2";
    private static final String ID_MAP = "com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap";
    private static final String LOD_BUILDER = "com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder";
    private static final String BIOME_WRAPPER = "com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper_forge";
    private static final String DATA_INPUT = "com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream";
    private static final String BLOCK_WRAPPER = "com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper_forge";
    private static final String LIGHT_STORAGE = "com.seibel.distanthorizons.core.wrapperInterfaces.chunk.ChunkLightStorage";
    private static final String ROUGH_SURFACE = "com.seibel.distanthorizons.common.wrappers.worldGeneration.DhRoughSurfaceGenerator_forge";
    private static final String CULLER = "com.seibel.distanthorizons.core.dataObjects.transformers.FullDataOcclusionCuller";

    private static final String CHUNK_ACCESS = "Lnet/minecraft/world/level/chunk/ChunkAccess;";
    private static final String CHUNK_STATUS = "Lnet/minecraft/world/level/chunk/ChunkStatus;";
    private static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String DH_BLOCK = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/block/IBlockStateWrapper;";
    private static final String DH_LEVEL = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/ILevelWrapper;";
    private static final String DH_BLOCK_IMPL = "Lcom/seibel/distanthorizons/common/wrappers/block/BlockStateWrapper_forge;";

    /** A DH method a patch depends on, and its hash in each checked DH version. */
    private record Requirement(String owner, String name, String desc, List<String> hashes) {
    }

    private static Requirement req(String owner, String name, String desc, String... hashes) {
        return new Requirement(owner, name, desc, List.of(hashes));
    }

    /** The culler's opacity table: the id map does not change once culling starts in a merge. Same on both loaders. */
    private static final List<Requirement> CULL_TABLE = List.of(
            req(CULLER, "isTranslucent", null, "bb424f5ae1bd1ca7"),
            req(CULLER, "cullHiddenDatapointsInColumn", null, "ba2fee5e493ea567"),
            req(CULLER, "checkOcclusion", null, "6239188834e3be3b"),
            req(DATA_SOURCE, "updateFromDataSource", null, "71e2220ffb454618"),
            req(ID_MAP, "getBlockStateWrapper", null, "5d005b239ef242fa"),
            req(ID_MAP, "getEntry", null, "1548f370ea903aef"));

    /**
     * Mixin simple name -> what it relies on. Hashes: DH 3.3.3 (Forge 1.20.1). Its Forge classes keep
     * Minecraft's SRG names for the methods they override ({@code m_6325_} is {@code getChunk(int, int)}).
     */
    private static final Map<String, List<Requirement>> REQUIREMENTS = Map.of(
            "MixinDhRegionFastChunk", List.of(
                    req(REGION, "m_6325_", "(II)" + CHUNK_ACCESS, "3e360bfb026b3ab5"),
                    req(REGION, "m_46819_", "(II" + CHUNK_STATUS + ")" + CHUNK_ACCESS, "d591e581879af093"),
                    req(REGION, "m_6522_", "(II" + CHUNK_STATUS + "Z)" + CHUNK_ACCESS, "08e973dc21edaf67"),
                    req(REGION, "getChunkAccess", null, "1a83f3e1025ddc70"),
                    req(REGION, "dhHasChunk", null, "0a98438f5bd2cf33"),
                    req(REGION, "dhGetChunk", null, "bd0c2ae845a3c77f"),
                    req(CHUNK_WRAPPER, "getStatus", "(" + CHUNK_ACCESS + ")" + CHUNK_STATUS, "c4e206aa511f6d3d")),
            "MixinDhFullDataSourceRemap", List.of(
                    req(DATA_SOURCE, "removeUnusedIdsAndRemap", null, "f39390aae6c717c0"),
                    req(DATA_SOURCE, "relativePosToIndex", null, "3ad6a18b60a86870"),
                    req(ID_MAP, "addIfNotPresentAndGetId",
                            "(Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;"
                                    + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/block/IBlockStateWrapper;)I",
                            "5d9cada962a1264f"),
                    req(ID_MAP, "addIfNotPresentAndGetId", "(Lcom/seibel/distanthorizons/core/dataObjects/BlockBiomeWrapperPair;)I",
                            "743b1ebbb5196e35"),
                    req(ID_MAP, "getBiomeWrapper", null, "076560c51606abcc"),
                    req(ID_MAP, "getBlockStateWrapper", null, "5d005b239ef242fa"),
                    req(ID_MAP, "getEntry", null, "1548f370ea903aef")),
            "MixinDhLodBuilderBiome", List.of(
                    req(LOD_BUILDER, "createFromChunk", null, "bda1d18aeb564b06"),
                    req(CHUNK_WRAPPER, "getBiome", "(III)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;",
                            "4a631e41c7312886"),
                    req(BIOME_WRAPPER, "getBiomeWrapper", null, "8dacfbb7c4c3d60d")),
            "MixinDhLodBuilderRuns", List.of(
                    req(LOD_BUILDER, "createFromChunk", null, "bda1d18aeb564b06"),
                    req(LOD_BUILDER, "getXOrZSectionPosFromChunkPos", null, "dcacfaea83844a2e"),
                    // A block's wrapper depends on its block state alone, and is the guess when the state is the guess's.
                    req(CHUNK_WRAPPER, "getBlockState",
                            "(IIILcom/seibel/distanthorizons/core/wrapperInterfaces/misc/IMutableBlockPosWrapper;" + DH_BLOCK + ")" + DH_BLOCK,
                            "0d0a5b5b1812d6b7"),
                    req(CHUNK_WRAPPER, "getChunk", null, "cc4e8614beb3338a"),
                    req(BLOCK_WRAPPER, "fromBlockState", "(" + BLOCK_STATE + DH_LEVEL + DH_BLOCK + ")" + DH_BLOCK_IMPL, "b5e66f4e8569f344"),
                    req(BLOCK_WRAPPER, "fromBlockState", "(" + BLOCK_STATE + DH_LEVEL + ")" + DH_BLOCK_IMPL, "7b41062de9837eed"),
                    req(BLOCK_WRAPPER, "isAir", "(" + BLOCK_STATE + ")Z", "cd573a3c997c5472"),
                    // The biome depends on the 4-block cell alone.
                    req(CHUNK_WRAPPER, "getBiome", "(III)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;",
                            "4a631e41c7312886"),
                    req(BIOME_WRAPPER, "getBiomeWrapper", null, "8dacfbb7c4c3d60d"),
                    // Light reads only read (after the storage is created on the first one).
                    req(CHUNK_WRAPPER, "getDhBlockLight", null, "87bde9cb8940212e"),
                    req(CHUNK_WRAPPER, "getDhSkyLight", null, "ab4f3c08c4548f03"),
                    req(CHUNK_WRAPPER, "getBlockLightStorage", null, "91d607cbb0a264f8"),
                    req(CHUNK_WRAPPER, "getSkyLightStorage", null, "834dfdc360823b57"),
                    req(LIGHT_STORAGE, "get", null, "d65b8ddeee057462"),
                    // Both equals start with this == obj.
                    req(BLOCK_WRAPPER, "equals", null, "5870348c193507a1"),
                    req(BIOME_WRAPPER, "equals", null, "f503ff6b051ed28f")),
            "MixinDhChunkHeightMaps", List.of(
                    req(CHUNK_WRAPPER, "createDhHeightMaps", null, "f30231ef7d64812e"),
                    req(CHUNK_WRAPPER, "getBlockState", "(III)" + DH_BLOCK, "b3881b57e192f016"),
                    req(BLOCK_WRAPPER, "fromBlockState", "(" + BLOCK_STATE + DH_LEVEL + ")" + DH_BLOCK_IMPL, "7b41062de9837eed"),
                    req(BLOCK_WRAPPER, "isAir", "(" + BLOCK_STATE + ")Z", "cd573a3c997c5472"),
                    // Plain getters, asked once per run.
                    req(BLOCK_WRAPPER, "isSolid", null, "99c4374e5c4b3d77"),
                    req(BLOCK_WRAPPER, "getOpacity", null, "6339635929eb9813")),
            "MixinDhCullScope", CULL_TABLE,
            "MixinDhOcclusionCuller", CULL_TABLE,
            "MixinDhDataInputStream", List.of(
                    req(DATA_INPUT, "create", "([BLcom/seibel/distanthorizons/api/enums/config/EDhApiDataCompressionMode;"
                                    + "Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayList/PhantomArrayListCheckout;)"
                                    + "Lcom/seibel/distanthorizons/core/util/objects/dataStreams/DhDataInputStream;",
                            "c087b67801ee3773")),
            // Only the one call it redirects: the density stays DH's, evaluated through a per-thread copy.
            "MixinDhRoughSurfaceDensity", List.of(
                    req(ROUGH_SURFACE, "isNoiseSolidAtBlockPos", null, "e6df7990f00fdf5b")));

    private static final Map<String, byte[]> CLASS_BYTES = new ConcurrentHashMap<>();

    private DhFastPaths() {
    }

    /** Whether the named mixin is one of these patches. */
    public static boolean governs(String mixinClassName) {
        return REQUIREMENTS.containsKey(simpleName(mixinClassName));
    }

    /** Whether the named patch should apply to the installed DH. Logs the reason when it does not. */
    public static boolean allows(String mixinClassName) {
        String patch = simpleName(mixinClassName);
        if (!ENABLED || !DistantHorizonsCompat.active()) {
            return false;
        }
        for (Requirement r : REQUIREMENTS.get(patch)) {
            String hash = hashOf(r);
            if (hash == null || !r.hashes().contains(hash)) {
                LOGGER.info("[SuperChunk-Compat] [dh] {} left out: Distant Horizons' {}.{} is not the code it was "
                                + "checked against (hash {}). DH runs its own code there.",
                        patch, r.owner().substring(r.owner().lastIndexOf('.') + 1), r.name(), hash);
                return false;
            }
        }
        return true;
    }

    private static String hashOf(Requirement r) {
        try {
            byte[] bytes = CLASS_BYTES.computeIfAbsent(r.owner(), DhFastPaths::readClass);
            return bytes.length == 0 ? null : ForeignBytecode.hash(bytes, r.name(), r.desc());
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-Compat] [dh] could not hash {}.{}", r.owner(), r.name(), t);
            return null;
        }
    }

    /** The class file as shipped, searched in every mod file as {@link OptionalTargets} does (no class loading). */
    private static byte[] readClass(String className) {
        String resource = className.replace('.', '/') + ".class";
        try {
            for (ModFileInfo info : LoadingModList.get().getModFiles()) {
                Path path = info.getFile().findResource(resource);
                if (Files.exists(path)) {
                    return Files.readAllBytes(path);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-Compat] [dh] could not read {}", className, t);
        }
        return new byte[0];
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
