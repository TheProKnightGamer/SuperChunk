package dev.superchunk.compat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What each patch of Distant Horizons' own code ({@code mixin.compat.MixinDh*}) relies on, and the
 * decision whether it may be applied to the installed DH.
 *
 * <p>Each patch replaces a DH method, or skips work only because of what DH's code does. So each
 * lists the DH methods it relies on with their {@link ForeignBytecode} hashes in every DH version
 * it was checked against (read and compared, then run with its verify mode): DH 3.0.0 to 3.3.3.
 * Against the installed DH a patch is one of:
 * <ul>
 *   <li><b>checked</b>: every method it relies on is byte for byte one of the checked versions.
 *   The patch runs.</li>
 *   <li><b>self-check</b>: some method differs, but only in how it computes: it calls the same
 *   methods and writes the same fields ({@link ForeignBytecode#effects}), and the patch is one
 *   that can compare its result with DH's own. The patch runs and DH's own code runs beside it,
 *   on every call at first and then on a sample; the first difference (or any error) turns the
 *   patch off for the session and DH's result is the one used. This is what keeps a patch working
 *   across a DH update that did not really change the code under it, without trusting that it did
 *   not.</li>
 *   <li><b>out</b>: a method is gone, does something new, or the patch cannot check itself. DH runs
 *   its own code, as without SuperChunk.</li>
 * </ul>
 * In every case the patch is also left out if anything it refers to in DH is missing
 * ({@link ForeignLinks}), so an update cannot turn a patch into a {@code NoSuchMethodError}.
 *
 * <p>No loader classes here: {@link #main} prints the decisions for DH jars outside the game, which
 * is how a new DH release is looked at before its hashes are added.
 */
public final class DhFastPathTable {

    /** How far a patch is trusted on the installed Distant Horizons. */
    public enum Trust { CHECKED, SELF_CHECK, OUT }

    /** A decision, and for anything but {@link Trust#CHECKED} the DH method it turned on. */
    public record Verdict(Trust trust, String reason) {
    }

    /** A DH method a patch depends on: its hashes in the checked versions, and the effects hashes it may also have under self-check. */
    record Requirement(String owner, String name, List<String> descs, List<String> hashes, List<String> effects) {
    }

    /** A patch: whether it can compare its result with DH's own, and what it relies on. */
    record Patch(boolean selfChecking, List<Requirement> requirements) {
    }

    /** DH names its loader-specific classes with this suffix. */
    static final String LOADER = "_forge";

    static final String DH_PREFIX = "com/seibel/distanthorizons/";
    private static final String MIXIN_PACKAGE = "dev/superchunk/mixin/compat/";

    private static final String WRAPPERS = "com.seibel.distanthorizons.common.wrappers.";
    private static final String CORE = "com.seibel.distanthorizons.core.";
    static final String REGION = WRAPPERS + "worldGeneration.mimicObject.DhLitWorldGenRegion" + LOADER;
    static final String CHUNK_WRAPPER = WRAPPERS + "chunk.ChunkWrapper" + LOADER;
    private static final String BIOME_WRAPPER = WRAPPERS + "block.BiomeWrapper" + LOADER;
    private static final String BLOCK_WRAPPER = WRAPPERS + "block.BlockStateWrapper" + LOADER;
    private static final String ROUGH_SURFACE = WRAPPERS + "worldGeneration.DhRoughSurfaceGenerator" + LOADER;
    private static final String TAG_PARSER = WRAPPERS + "worldGeneration.chunkFileHandling.ChunkCompoundTagParser" + LOADER;
    private static final String DATA_SOURCE = CORE + "dataObjects.fullData.sources.FullDataSourceV2";
    private static final String ID_MAP = CORE + "dataObjects.fullData.FullDataPointIdMap";
    private static final String LOD_BUILDER = CORE + "dataObjects.transformers.LodDataBuilder";
    private static final String CULLER = CORE + "dataObjects.transformers.FullDataOcclusionCuller";
    private static final String DATA_INPUT = CORE + "util.objects.dataStreams.DhDataInputStream";
    private static final String LIGHT_STORAGE = CORE + "wrapperInterfaces.chunk.ChunkLightStorage";
    private static final String LIGHT_SECTION = LIGHT_STORAGE + "$LightSection";
    private static final String TAG_UTIL = WRAPPERS + "worldGeneration.chunkFileHandling.CompoundTagUtil" + LOADER;

    private static final String CHUNK_ACCESS = "Lnet/minecraft/world/level/chunk/ChunkAccess;";
    private static final String CHUNK_STATUS = "Lnet/minecraft/world/level/chunk/ChunkStatus;";
    private static final String BLOCK_STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String DH_BLOCK = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/block/IBlockStateWrapper;";
    private static final String DH_BIOME = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;";
    private static final String DH_LEVEL = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/ILevelWrapper;";
    private static final String DH_BLOCK_IMPL = "Lcom/seibel/distanthorizons/common/wrappers/block/BlockStateWrapper" + LOADER + ";";
    private static final String DH_STREAM = "Lcom/seibel/distanthorizons/core/util/objects/dataStreams/DhDataInputStream;";
    private static final String DH_COMPRESSION = "Lcom/seibel/distanthorizons/api/enums/config/EDhApiDataCompressionMode;";

    /** The region's three vanilla {@code getChunk} overloads, by their runtime names. */
    private static final String GET_CHUNK = "m_6325_";
    private static final String GET_CHUNK_STATUS = "m_46819_";
    private static final String GET_CHUNK_STATUS_LOAD = "m_6522_";

    /** {@code DhDataInputStream.create(byte[], mode, checkout)}; the checkout class moved in DH 3.2.0. */
    static final String STREAM_CREATE = "([B" + DH_COMPRESSION
            + "Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayList/PhantomArrayListCheckout;)" + DH_STREAM;
    static final String STREAM_CREATE_OLD = "([B" + DH_COMPRESSION
            + "Lcom/seibel/distanthorizons/core/util/objects/pooling/PhantomArrayListCheckout;)" + DH_STREAM;

    /**
     * A requirement. Of the strings, a plain one is the method's hash in a checked version; one
     * starting with {@code ~} is an effects hash a changed method may have under self-check.
     */
    private static Requirement req(String owner, String name, String desc, String... hashes) {
        return any(owner, name, desc == null ? List.of() : List.of(desc), hashes);
    }

    /** As {@link #req}, for a method whose descriptor differs between the checked versions. */
    private static Requirement any(String owner, String name, List<String> descs, String... hashes) {
        List<String> exact = new ArrayList<>();
        List<String> effects = new ArrayList<>();
        for (String hash : hashes) {
            if (hash.startsWith("~")) {
                effects.add(hash.substring(1));
            } else {
                exact.add(hash);
            }
        }
        return new Requirement(owner, name, descs, List.copyOf(exact), List.copyOf(effects));
    }

    // ---- Requirements shared by several patches. Version notes: "3.1.x" is the same source as its
    // ---- neighbours built for Java 21 (string concatenation compiles differently).

    /** A block's wrapper depends on its block state alone: DH keeps one per state. Not DH 3.0.1/3.0.2, which made one per call. */
    private static final Requirement WRAPPER_OF_STATE = req(BLOCK_WRAPPER, "fromBlockState", "(" + BLOCK_STATE + DH_LEVEL + ")" + DH_BLOCK_IMPL,
            "7b41062de9837eed" /* 3.0.3+ */, "bbb5e7b0e448f101" /* 3.0.0 */, "~79ed7d379abbe53a", "~844dc13ee572ad4c");
    private static final Requirement IS_AIR = req(BLOCK_WRAPPER, "isAir", "(" + BLOCK_STATE + ")Z",
            "cd573a3c997c5472" /* 3.1.0+ */, "7d593e6bd7c89c6a" /* 3.0.1-3.0.3 */, "3c25edfae996e5c6" /* 3.0.0 */, "~51eb27a738f9a008", "~316d8a6056b1d72c");
    /** For a chunk of the world: a wrapper is remembered for a palette id only when it wraps that palette entry. */
    private static final Requirement WRAPPED_STATE = req(BLOCK_WRAPPER, "getWrappedMcObject", null, "28eb1a30379959ae", "~9d1b1345bfa9caa0");
    private static final Requirement WRAPPER_IS_AIR = req(BLOCK_WRAPPER, "isAir", "()Z",
            "b62e554085331934" /* 3.0.1+ */, "326cabd3876eabd9" /* 3.0.0 */, "~d7ff666d21f3030a");
    /** The biome depends on the 4-block cell alone. */
    private static final Requirement BIOME_OF_CELL = req(CHUNK_WRAPPER, "getBiome", "(III)" + DH_BIOME, "4a631e41c7312886", "~83a0e7a47942a6d0");
    private static final Requirement BIOME_WRAPPER_OF = req(BIOME_WRAPPER, "getBiomeWrapper", null, "8dacfbb7c4c3d60d", "~d9da4943c52227e9");
    private static final Requirement CREATE_FROM_CHUNK = req(LOD_BUILDER, "createFromChunk", null,
            "bda1d18aeb564b06", "ae9817a631bd635f" /* 3.1.x */, "~c608362c7647263a");
    private static final Requirement ID_MAP_BLOCK = req(ID_MAP, "getBlockStateWrapper", null, "5d005b239ef242fa", "0ec5a5371831c44e" /* 3.1.x */);
    private static final Requirement ID_MAP_ENTRY = req(ID_MAP, "getEntry", null, "1548f370ea903aef", "ae557a2f5b56b414" /* 3.1.x */);

    /**
     * The culler's opacity table: the id map does not change once culling starts in a merge. In
     * every checked version {@code updateFromDataSource} merges the id maps, copies the columns,
     * remaps, and only then culls.
     */
    private static final List<Requirement> CULL_TABLE = List.of(
            req(CULLER, "isTranslucent", null, "bb424f5ae1bd1ca7"),
            req(CULLER, "cullHiddenDatapointsInColumn", null, "ba2fee5e493ea567"),
            req(CULLER, "checkOcclusion", null, "6239188834e3be3b"),
            req(DATA_SOURCE, "updateFromDataSource", null,
                    "71e2220ffb454618" /* 3.3.x */, "bfaece27e5da51c9" /* 3.0.x, 3.2.0 */, "f5f1bad02933ce9c" /* 3.1.x */),
            ID_MAP_BLOCK,
            ID_MAP_ENTRY);

    /** Mixin simple name -> what it relies on. */
    static final Map<String, Patch> PATCHES = patches();

    private static Map<String, Patch> patches() {
        Map<String, Patch> patches = new LinkedHashMap<>();
        // Needs dhHasChunk/dhGetChunk, which DH 3.0.x does not have.
        patches.put("MixinDhRegionFastChunk", new Patch(false, List.of(
                req(REGION, GET_CHUNK, "(II)" + CHUNK_ACCESS, "3e360bfb026b3ab5"),
                req(REGION, GET_CHUNK_STATUS, "(II" + CHUNK_STATUS + ")" + CHUNK_ACCESS, "d591e581879af093"),
                req(REGION, GET_CHUNK_STATUS_LOAD, "(II" + CHUNK_STATUS + "Z)" + CHUNK_ACCESS, "08e973dc21edaf67"),
                req(REGION, "getChunkAccess", null, "1a83f3e1025ddc70" /* 3.3.x */,
                        "34be4851affb27d4" /* 3.1.0-3.2.0: the empty-chunk callback's interface had another name */),
                req(REGION, "dhHasChunk", null, "0a98438f5bd2cf33"),
                req(REGION, "dhGetChunk", null, "bd0c2ae845a3c77f"),
                req(CHUNK_WRAPPER, "getStatus", "(" + CHUNK_ACCESS + ")" + CHUNK_STATUS, "c4e206aa511f6d3d"))));
        patches.put("MixinDhFullDataSourceRemap", new Patch(false, List.of(
                req(DATA_SOURCE, "removeUnusedIdsAndRemap", null, "f39390aae6c717c0"),
                req(DATA_SOURCE, "relativePosToIndex", null, "3ad6a18b60a86870", "f0f2b855727f368f" /* 3.1.x */),
                req(ID_MAP, "addIfNotPresentAndGetId", "(" + DH_BIOME + DH_BLOCK + ")I", "5d9cada962a1264f", "8937e16a73ed5764" /* 3.1.x */),
                req(ID_MAP, "addIfNotPresentAndGetId", "(Lcom/seibel/distanthorizons/core/dataObjects/BlockBiomeWrapperPair;)I",
                        "743b1ebbb5196e35", "57fd384d1b3c696a" /* 3.1.x */),
                req(ID_MAP, "getBiomeWrapper", null, "076560c51606abcc", "37342fe9d0b6bd00" /* 3.1.x */),
                ID_MAP_BLOCK,
                ID_MAP_ENTRY)));
        patches.put("MixinDhLodBuilderBiome", new Patch(true, List.of(
                CREATE_FROM_CHUNK,
                BIOME_OF_CELL,
                BIOME_WRAPPER_OF)));
        patches.put("MixinDhLodBuilderRuns", new Patch(true, List.of(
                CREATE_FROM_CHUNK,
                req(LOD_BUILDER, "getXOrZSectionPosFromChunkPos", null, "dcacfaea83844a2e", "~c62ce3744c6dce07"),
                // The wrapper DH is given as its guess is the one it would look up.
                req(CHUNK_WRAPPER, "getBlockState",
                        "(IIILcom/seibel/distanthorizons/core/wrapperInterfaces/misc/IMutableBlockPosWrapper;" + DH_BLOCK + ")" + DH_BLOCK,
                        "0d0a5b5b1812d6b7", "~14a21b6e3e9a3482"),
                req(CHUNK_WRAPPER, "getChunk", null, "cc4e8614beb3338a", "~2186b232f337a0c2"),
                req(BLOCK_WRAPPER, "fromBlockState", "(" + BLOCK_STATE + DH_LEVEL + DH_BLOCK + ")" + DH_BLOCK_IMPL,
                        "b5e66f4e8569f344" /* 3.3.3 */, "ee1a70d47a6953e6" /* 3.1.0-3.3.2: == for Objects.equals */,
                        "3fae98437f9f373f" /* 3.0.x */, "~f4cb47634294b3ed", "~013cda8fc103b52b"),
                WRAPPER_OF_STATE,
                IS_AIR,
                WRAPPED_STATE,
                WRAPPER_IS_AIR,
                BIOME_OF_CELL,
                BIOME_WRAPPER_OF,
                // Light reads only read (after the storage is created on the first one).
                req(CHUNK_WRAPPER, "getDhBlockLight", null, "87bde9cb8940212e", "~21f246ebd9fca463"),
                req(CHUNK_WRAPPER, "getDhSkyLight", null, "ab4f3c08c4548f03", "~894618e2c94d371e"),
                req(CHUNK_WRAPPER, "getBlockLightStorage", null, "91d607cbb0a264f8", "~953efffc10f47656"),
                req(CHUNK_WRAPPER, "getSkyLightStorage", null, "834dfdc360823b57", "~1ea201b211967cae"),
                req(LIGHT_STORAGE, "get", null, "d65b8ddeee057462", "ad09c35e4bddf6bd" /* 3.1.x */, "~349bb71062726910"),
                // Both equals start with this == obj.
                req(BLOCK_WRAPPER, "equals", null, "5870348c193507a1", "~05e768fea406d68e"),
                req(BIOME_WRAPPER, "equals", null, "f503ff6b051ed28f", "~e9844ebc39a4eef2"))));
        patches.put("MixinDhChunkHeightMaps", new Patch(true, List.of(
                req(CHUNK_WRAPPER, "createDhHeightMaps", null, "f30231ef7d64812e" /* 3.3.3 */,
                        HEIGHTMAPS_BEFORE_3_3_3 /* 3.0.0-3.3.2: the off-thread warning's test was inverted, so it never logged */, "~6c4a0702c4f54ace"),
                req(CHUNK_WRAPPER, "getBlockState", "(III)" + DH_BLOCK, "b3881b57e192f016", "~4ea85430851ca628"),
                WRAPPER_OF_STATE,
                IS_AIR,
                WRAPPED_STATE,
                WRAPPER_IS_AIR,
                // Plain getters, asked once per run.
                req(BLOCK_WRAPPER, "isSolid", null, "99c4374e5c4b3d77", "~81a363be664e7d80"),
                req(BLOCK_WRAPPER, "getOpacity", null, "6339635929eb9813", "~ff4300900fd75a9b"))));
        patches.put("MixinDhCullScope", new Patch(false, CULL_TABLE));
        patches.put("MixinDhOcclusionCuller", new Patch(false, CULL_TABLE));
        patches.put("MixinDhDataInputStream", new Patch(false, List.of(
                any(DATA_INPUT, "create", List.of(STREAM_CREATE, STREAM_CREATE_OLD),
                        "c087b67801ee3773" /* 3.2.0+ */, "5de17e2f5cb9b26f" /* 3.0.0-3.1.2 */))));
        // DH's codec for a saved section's block states is vanilla's, which the direct read stands in for.
        patches.put("MixinDhChunkTagPalette", new Patch(true, List.of(
                req(TAG_PARSER, "readAndPopulateSections", null, "4cae2a49320c6a17", "~6bb5641993741f3c"),
                req(TAG_PARSER, "getBlockStateCodec", null, "4ce07ab575ff2dc1", "~1487382e30161ca9"))));
        // What 4,096 set calls leave in a new light section depends on the values alone.
        patches.put("MixinDhChunkTagLight", new Patch(true, List.of(
                req(TAG_PARSER, "readLight", null, "5a42270a4f72a020" /* to 3.3.2 */, "41b3d09b33ce466f" /* 3.3.3 */, "~397aa8703e38ebb7"),
                req(TAG_PARSER, "getNibbleAtIndex", null, "83489cb31dfb6687", "~4cc9c1e8a2678472"),
                req(TAG_UTIL, "getByteArray", null, "3ca9248ca6e376af", "~45afff8dd7e4d6fb"),
                req(LIGHT_STORAGE, "set", null, "1810a4e54219d88f", "~a38422f9ea75f9e9"),
                req(LIGHT_SECTION, "set", null, "f6e830850b9d174f", "a607410b72c9fb7b" /* 3.1.x */, "~988a0a75cbfc3307"),
                req(LIGHT_SECTION, "<init>", null, "111dd19a2ccc95d0", "~2eaa324dea856eae"))));
        // Only the one call it redirects: the density stays DH's, evaluated through a per-thread copy.
        patches.put("MixinDhRoughSurfaceDensity", new Patch(true, List.of(
                req(ROUGH_SURFACE, "isNoiseSolidAtBlockPos", null, "e6df7990f00fdf5b", "~2618ffc1ffc391e8"))));
        return patches;
    }

    /** {@code ChunkWrapper.createDhHeightMaps} before DH 3.3.3. */
    static final String HEIGHTMAPS_BEFORE_3_3_3 = "fec1a604188ace9a";

    private DhFastPathTable() {
    }

    /** Whether the named mixin is one of these patches. */
    public static boolean governs(String mixinSimpleName) {
        return PATCHES.containsKey(mixinSimpleName);
    }

    /**
     * The decision for one patch.
     *
     * @param classes       DH's class files by internal name ({@code null}: DH has no such class)
     * @param allowSelfCheck whether a changed but checkable patch may run under self-check
     */
    public static Verdict evaluate(String mixinSimpleName, Function<String, byte[]> classes, boolean allowSelfCheck) {
        Patch patch = PATCHES.get(mixinSimpleName);
        String changed = null;
        for (Requirement r : patch.requirements()) {
            String where = simple(r.owner()) + "." + r.name();
            byte[] bytes = classes.apply(r.owner().replace('.', '/'));
            if (bytes == null || bytes.length == 0) {
                return new Verdict(Trust.OUT, "Distant Horizons has no class " + simple(r.owner()));
            }
            MethodNode method = null;
            if (r.descs().isEmpty()) {
                method = ForeignBytecode.find(bytes, r.name(), null);
            }
            for (String desc : r.descs()) {
                if (method == null) {
                    method = ForeignBytecode.find(bytes, r.name(), desc);
                }
            }
            if (method == null) {
                return new Verdict(Trust.OUT, "Distant Horizons' " + where + " is gone or has another signature");
            }
            String hash = ForeignBytecode.hash(method);
            if (r.hashes().contains(hash)) {
                continue;
            }
            if (!patch.selfChecking() || !allowSelfCheck) {
                return new Verdict(Trust.OUT, "Distant Horizons' " + where + " is not the code it was checked against (hash " + hash + ")");
            }
            String effects = ForeignBytecode.effects(method);
            if (!r.effects().contains(effects)) {
                return new Verdict(Trust.OUT, "Distant Horizons' " + where + " calls or writes something it did not in the checked versions (hash "
                        + hash + ", effects " + effects + ")");
            }
            if (changed == null) {
                changed = where + " (hash " + hash + ")";
            }
        }
        String unresolved = unresolved(mixinSimpleName, classes);
        if (unresolved != null && (changed != null || !unresolved.startsWith("?"))) {
            return new Verdict(Trust.OUT, unresolved.startsWith("?") ? "its references to Distant Horizons could not be checked"
                    : "it uses Distant Horizons' " + unresolved + ", which this version does not have");
        }
        return changed == null ? new Verdict(Trust.CHECKED, null) : new Verdict(Trust.SELF_CHECK, changed);
    }

    /** The installed hash of a method the table names, or {@code null}. */
    public static String installedHash(String owner, String name, String desc, Function<String, byte[]> classes) {
        byte[] bytes = classes.apply(owner.replace('.', '/'));
        MethodNode method = bytes == null || bytes.length == 0 ? null : ForeignBytecode.find(bytes, name, desc);
        return method == null ? null : ForeignBytecode.hash(method);
    }

    /** What {@link ForeignLinks} finds missing; {@code "?"} when the patch's own class file cannot be read. */
    private static String unresolved(String mixinSimpleName, Function<String, byte[]> classes) {
        byte[] mixin;
        try (InputStream in = DhFastPathTable.class.getResourceAsStream("/" + MIXIN_PACKAGE + mixinSimpleName + ".class")) {
            if (in == null) {
                return "?";
            }
            mixin = in.readAllBytes();
        } catch (Throwable t) {
            return "?";
        }
        try {
            for (String target : targets(mixin)) {
                byte[] bytes = classes.apply(target);
                if (bytes != null && bytes.length > 0) {
                    return ForeignLinks.firstUnresolved(mixin, target, DH_PREFIX, classes);
                }
            }
            return "class " + mixinSimpleName.replace("Mixin", "") + "'s target";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** The internal names of the classes a mixin class is aimed at. */
    @SuppressWarnings("unchecked")
    private static List<String> targets(byte[] mixin) {
        ClassNode node = new ClassNode();
        new ClassReader(mixin).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> targets = new ArrayList<>();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.invisibleAnnotations != null) {
            annotations.addAll(node.invisibleAnnotations);
        }
        if (node.visibleAnnotations != null) {
            annotations.addAll(node.visibleAnnotations);
        }
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                Object value = annotation.values.get(i + 1);
                if ("targets".equals(annotation.values.get(i))) {
                    for (String name : (List<String>) value) {
                        targets.add(name.replace('.', '/'));
                    }
                } else if ("value".equals(annotation.values.get(i))) {
                    for (Type type : (List<Type>) value) {
                        targets.add(type.getInternalName());
                    }
                }
            }
        }
        return targets;
    }

    /**
     * {@code --hashes}: for every method the table names, each distinct hash and effects hash among
     * the given jars with the jars that have it; {@code *} marks a hash the table lists. The input
     * for reading what a new DH release changed, and for adding its hashes once that is checked.
     */
    private static void printHashes(List<String> jars) throws Exception {
        Map<String, Requirement> requirements = new LinkedHashMap<>();
        for (Patch patch : PATCHES.values()) {
            for (Requirement r : patch.requirements()) {
                requirements.merge(r.owner() + "." + r.name() + r.descs(), r, (a, b) -> {
                    List<String> exact = new ArrayList<>(a.hashes());
                    b.hashes().stream().filter(h -> !exact.contains(h)).forEach(exact::add);
                    return new Requirement(a.owner(), a.name(), a.descs(), exact, a.effects());
                });
            }
        }
        for (Requirement r : requirements.values()) {
            Map<String, List<String>> groups = new LinkedHashMap<>();
            for (String jar : jars) {
                String label = jar.substring(jar.lastIndexOf('/') + 1).replaceAll("^DistantHorizons-", "").replaceAll("-1\\.2.*$", "");
                String key;
                try (ZipFile zip = new ZipFile(jar)) {
                    ZipEntry entry = zip.getEntry(r.owner().replace('.', '/') + ".class");
                    if (entry == null) {
                        key = "NO-CLASS";
                    } else {
                        byte[] bytes;
                        try (InputStream in = zip.getInputStream(entry)) {
                            bytes = in.readAllBytes();
                        }
                        MethodNode method = r.descs().isEmpty() ? ForeignBytecode.find(bytes, r.name(), null) : null;
                        for (String desc : r.descs()) {
                            if (method == null) {
                                method = ForeignBytecode.find(bytes, r.name(), desc);
                            }
                        }
                        key = method == null ? "NO-METHOD" : ForeignBytecode.hash(method) + " ~" + ForeignBytecode.effects(method);
                    }
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(label);
            }
            System.out.println("REQ " + simple(r.owner()) + "." + r.name() + " " + (r.descs().isEmpty() ? "-" : String.join("|", r.descs())));
            for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                boolean listed = r.hashes().contains(group.getKey().split(" ")[0]);
                System.out.println("  " + group.getKey() + " " + (listed ? "*" : "-") + " " + String.join(" ", group.getValue()));
            }
        }
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /**
     * Prints the decision for every patch against each Distant Horizons jar given: the check to run
     * when DH releases a version. {@code -v} also prints the reason for each patch not checked.
     */
    public static void main(String[] args) throws Exception {
        boolean verbose = false;
        boolean hashes = false;
        List<String> jars = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-v")) {
                verbose = true;
            } else if (arg.equals("--hashes")) {
                hashes = true;
            } else {
                jars.add(arg);
            }
        }
        if (hashes) {
            printHashes(jars);
            return;
        }
        for (String jar : jars) {
            try (ZipFile zip = new ZipFile(jar)) {
                Map<String, byte[]> cache = new HashMap<>();
                Function<String, byte[]> classes = name -> cache.computeIfAbsent(name, n -> {
                    ZipEntry entry = zip.getEntry(n + ".class");
                    if (entry == null) {
                        return new byte[0];
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        return in.readAllBytes();
                    } catch (java.io.IOException e) {
                        return new byte[0];
                    }
                });
                StringBuilder line = new StringBuilder(String.format("%-54s", jar.substring(jar.lastIndexOf('/') + 1)));
                StringBuilder reasons = new StringBuilder();
                for (String patch : PATCHES.keySet()) {
                    Verdict verdict = evaluate(patch, classes, true);
                    line.append(' ').append(patch.replace("MixinDh", "")).append('=')
                            .append(verdict.trust() == Trust.CHECKED ? "ok" : verdict.trust() == Trust.SELF_CHECK ? "SELF-CHECK" : "OUT");
                    if (verdict.trust() != Trust.CHECKED) {
                        reasons.append("    ").append(patch).append(": ").append(verdict.trust()).append(", ").append(verdict.reason()).append('\n');
                    }
                }
                System.out.println(line);
                if (verbose) {
                    System.out.print(reasons);
                }
            }
        }
    }
}
