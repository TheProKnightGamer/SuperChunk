package dev.superchunk.compat;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
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
            "com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion_neoforge";
    private static final String CHUNK_WRAPPER = "com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper_neoforge";
    private static final String DATA_SOURCE = "com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2";
    private static final String ID_MAP = "com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap";
    private static final String LOD_BUILDER = "com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder";
    private static final String BIOME_WRAPPER = "com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper_neoforge";
    private static final String DATA_INPUT = "com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream";
    private static final String BLOCK_WRAPPER = "com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper_neoforge";
    private static final String LIGHT_STORAGE = "com.seibel.distanthorizons.core.wrapperInterfaces.chunk.ChunkLightStorage";
    private static final String CULLER = "com.seibel.distanthorizons.core.dataObjects.transformers.FullDataOcclusionCuller";

    private static final String CHUNK_ACCESS = "Lnet/minecraft/world/level/chunk/ChunkAccess;";
    private static final String CHUNK_STATUS = "Lnet/minecraft/world/level/chunk/status/ChunkStatus;";
    private static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String DH_BLOCK = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/block/IBlockStateWrapper;";
    private static final String DH_LEVEL = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/ILevelWrapper;";
    private static final String DH_BLOCK_IMPL = "Lcom/seibel/distanthorizons/common/wrappers/block/BlockStateWrapper_neoforge;";

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

    /** Mixin simple name -> what it relies on. Hashes: DH 3.3.3 (NeoForge 1.21.1). */
    private static final Map<String, List<Requirement>> REQUIREMENTS = Map.of(
            "MixinDhRegionFastChunk", List.of(
                    req(REGION, "getChunk", "(II)" + CHUNK_ACCESS, "efe1b4db3ca4a5a8"),
                    req(REGION, "getChunk", "(II" + CHUNK_STATUS + ")" + CHUNK_ACCESS, "4fad9a22aedeab03"),
                    req(REGION, "getChunk", "(II" + CHUNK_STATUS + "Z)" + CHUNK_ACCESS, "02a6527a78c9ee36"),
                    req(REGION, "getChunkAccess", null, "d4e1b96491c89336"),
                    req(REGION, "dhHasChunk", null, "d61dd5f2358c4d5b"),
                    req(REGION, "dhGetChunk", null, "7485d53c68a97b8a"),
                    req(CHUNK_WRAPPER, "getStatus", "(" + CHUNK_ACCESS + ")" + CHUNK_STATUS, "51973a98f218ef3c")),
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
                            "91e464d68955c9a5"),
                    req(BIOME_WRAPPER, "getBiomeWrapper", null, "99b5f1b6a43b4229")),
            "MixinDhLodBuilderRuns", List.of(
                    req(LOD_BUILDER, "createFromChunk", null, "bda1d18aeb564b06"),
                    req(LOD_BUILDER, "getXOrZSectionPosFromChunkPos", null, "dcacfaea83844a2e"),
                    // A block's wrapper depends on its block state alone, and is the guess when the state is the guess's.
                    req(CHUNK_WRAPPER, "getBlockState",
                            "(IIILcom/seibel/distanthorizons/core/wrapperInterfaces/misc/IMutableBlockPosWrapper;" + DH_BLOCK + ")" + DH_BLOCK,
                            "99e0ea28b3dbe6b9"),
                    req(CHUNK_WRAPPER, "getChunk", null, "ca9579eea9d5792a"),
                    req(BLOCK_WRAPPER, "fromBlockState", "(" + BLOCK_STATE + DH_LEVEL + DH_BLOCK + ")" + DH_BLOCK_IMPL, "45e8eb9becd7efc9"),
                    req(BLOCK_WRAPPER, "fromBlockState", "(" + BLOCK_STATE + DH_LEVEL + ")" + DH_BLOCK_IMPL, "15e8dd7304a04ffb"),
                    req(BLOCK_WRAPPER, "isAir", "(" + BLOCK_STATE + ")Z", "43feef9cf6ae363f"),
                    // The biome depends on the 4-block cell alone.
                    req(CHUNK_WRAPPER, "getBiome", "(III)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;",
                            "91e464d68955c9a5"),
                    req(BIOME_WRAPPER, "getBiomeWrapper", null, "99b5f1b6a43b4229"),
                    // Light reads only read (after the storage is created on the first one).
                    req(CHUNK_WRAPPER, "getDhBlockLight", null, "90566d91c3a436a9"),
                    req(CHUNK_WRAPPER, "getDhSkyLight", null, "88b5589d6f465394"),
                    req(CHUNK_WRAPPER, "getBlockLightStorage", null, "58a3ece66bd085d9"),
                    req(CHUNK_WRAPPER, "getSkyLightStorage", null, "42f4f6aeb187bf0b"),
                    req(LIGHT_STORAGE, "get", null, "d65b8ddeee057462"),
                    // Both equals start with this == obj.
                    req(BLOCK_WRAPPER, "equals", null, "cff6cf7afcf3a9a7"),
                    req(BIOME_WRAPPER, "equals", null, "8b0d8ec8ee24f1d0")),
            "MixinDhChunkHeightMaps", List.of(
                    req(CHUNK_WRAPPER, "createDhHeightMaps", null, "4196dfa8466878a7"),
                    req(CHUNK_WRAPPER, "getBlockState", "(III)" + DH_BLOCK, "b95a4307573a68b8"),
                    req(BLOCK_WRAPPER, "fromBlockState", "(" + BLOCK_STATE + DH_LEVEL + ")" + DH_BLOCK_IMPL, "15e8dd7304a04ffb"),
                    req(BLOCK_WRAPPER, "isAir", "(" + BLOCK_STATE + ")Z", "43feef9cf6ae363f"),
                    // Plain getters, asked once per run.
                    req(BLOCK_WRAPPER, "isSolid", null, "55bd2824c92351ae"),
                    req(BLOCK_WRAPPER, "getOpacity", null, "fd6acb8fc4ef3455")),
            "MixinDhCullScope", CULL_TABLE,
            "MixinDhOcclusionCuller", CULL_TABLE,
            "MixinDhDataInputStream", List.of(
                    req(DATA_INPUT, "create", "([BLcom/seibel/distanthorizons/api/enums/config/EDhApiDataCompressionMode;"
                                    + "Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayList/PhantomArrayListCheckout;)"
                                    + "Lcom/seibel/distanthorizons/core/util/objects/dataStreams/DhDataInputStream;",
                            "c087b67801ee3773")));

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
