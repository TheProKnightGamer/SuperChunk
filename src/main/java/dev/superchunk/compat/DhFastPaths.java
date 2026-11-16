package dev.superchunk.compat;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gate for the patches that speed up Distant Horizons' own code ({@code mixin.compat.MixinDh*}
 * other than the generation-step seam): reads the installed DH's class files and asks
 * {@link DhFastPathTable} for each patch whether it runs, runs under self-check, or stays out.
 *
 * <p>Kill switches: {@code -Dsuperchunk.dh.fastPaths=false} leaves every patch out;
 * {@code -Dsuperchunk.dh.fastPaths.off=<names>} leaves out the named ones (the names the log
 * uses, comma-separated); {@code -Dsuperchunk.dh.selfCheck=false} leaves out the ones that would
 * run under self-check (only patches whose DH methods are byte for byte a checked version then run).
 */
public final class DhFastPaths {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.dh.fastPaths", "true"));

    private static final boolean SELF_CHECK_ALLOWED =
            Boolean.parseBoolean(System.getProperty("superchunk.dh.selfCheck", "true"));

    /** {@code -Dsuperchunk.dh.fastPaths.off=DhLodBuilderRuns,DhChunkTagLight}: patches to leave out, by the names the log uses. */
    private static final java.util.Set<String> OFF = offList();

    private static java.util.Set<String> offList() {
        java.util.Set<String> off = new java.util.HashSet<>();
        for (String name : System.getProperty("superchunk.dh.fastPaths.off", "").split(",")) {
            if (!name.isBlank()) {
                off.add(name.trim().replace("Mixin", ""));
            }
        }
        return off;
    }

    /** The newest DH version the table was checked against. */
    private static final int[] NEWEST_CHECKED = {3, 3, 3};

    private static final Map<String, byte[]> CLASS_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, DhSelfCheck> SELF_CHECKS = new ConcurrentHashMap<>();
    private static volatile Map<String, DhFastPathTable.Verdict> verdicts;

    private DhFastPaths() {
    }

    /** Whether the named mixin is one of these patches. */
    public static boolean governs(String mixinClassName) {
        return DhFastPathTable.governs(simpleName(mixinClassName));
    }

    /** Whether the named patch should apply to the installed DH. */
    public static boolean allows(String mixinClassName) {
        if (!ENABLED || !DistantHorizonsCompat.active()) {
            return false;
        }
        DhFastPathTable.Verdict verdict = verdicts().get(simpleName(mixinClassName));
        return verdict != null && verdict.trust() != DhFastPathTable.Trust.OUT;
    }

    /**
     * The self-check state of a patch that runs under self-check on the installed DH, or
     * {@code null} for a patch on a checked version (which then compares nothing).
     */
    public static DhSelfCheck selfCheck(String mixinSimpleName, String unit) {
        if (!ENABLED || !DistantHorizonsCompat.active()) {
            return null; // asked by a helper class on an install without Distant Horizons
        }
        try {
            DhFastPathTable.Verdict verdict = verdicts().get(mixinSimpleName);
            if (verdict == null || verdict.trust() != DhFastPathTable.Trust.SELF_CHECK) {
                return null;
            }
            return SELF_CHECKS.computeIfAbsent(mixinSimpleName, name -> new DhSelfCheck(name.replace("Mixin", ""), unit));
        } catch (Throwable t) {
            // Cannot tell: compare everything rather than nothing.
            return SELF_CHECKS.computeIfAbsent(mixinSimpleName, name -> new DhSelfCheck(name.replace("Mixin", ""), unit));
        }
    }

    /**
     * Whether the installed DH logs its "height maps created on non-DH thread" warning. Before
     * 3.3.3 the test was inverted and it never did; the height-map patch does as the installed DH.
     */
    public static boolean heightMapsWarnOffThread() {
        if (!DistantHorizonsCompat.active()) {
            return true;
        }
        try {
            String hash = DhFastPathTable.installedHash(DhFastPathTable.CHUNK_WRAPPER, "createDhHeightMaps", null, DhFastPaths::classBytes);
            return !DhFastPathTable.HEIGHTMAPS_BEFORE_3_3_3.equals(hash);
        } catch (Throwable t) {
            return true;
        }
    }

    private static Map<String, DhFastPathTable.Verdict> verdicts() {
        Map<String, DhFastPathTable.Verdict> result = verdicts;
        if (result == null) {
            synchronized (DhFastPaths.class) {
                result = verdicts;
                if (result == null) {
                    verdicts = result = decide();
                    CLASS_BYTES.clear();
                }
            }
        }
        return result;
    }

    private static Map<String, DhFastPathTable.Verdict> decide() {
        Map<String, DhFastPathTable.Verdict> result = new LinkedHashMap<>();
        List<String> selfChecking = new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String patch : DhFastPathTable.PATCHES.keySet()) {
            DhFastPathTable.Verdict verdict;
            try {
                verdict = OFF.contains(patch.replace("Mixin", ""))
                        ? new DhFastPathTable.Verdict(DhFastPathTable.Trust.OUT, "it is switched off (-Dsuperchunk.dh.fastPaths.off)")
                        : DhFastPathTable.evaluate(patch, DhFastPaths::classBytes, SELF_CHECK_ALLOWED);
            } catch (Throwable t) {
                LOGGER.debug("[SuperChunk-Compat] [dh] could not check {}", patch, t);
                verdict = new DhFastPathTable.Verdict(DhFastPathTable.Trust.OUT, "it could not be checked (" + t + ")");
            }
            result.put(patch, verdict);
            String name = patch.replace("Mixin", "");
            if (verdict.trust() == DhFastPathTable.Trust.SELF_CHECK) {
                selfChecking.add(name);
                LOGGER.info("[SuperChunk-Compat] [dh] {} runs under self-check: Distant Horizons' {} differs from the checked versions "
                        + "only in how it computes. Its results are compared with DH's own.", name, verdict.reason());
            } else if (verdict.trust() == DhFastPathTable.Trust.OUT) {
                out.add(name);
                LOGGER.info("[SuperChunk-Compat] [dh] {} left out: {}. DH runs its own code there.", name, verdict.reason());
            }
        }
        String version = installedVersion();
        int total = result.size();
        if (selfChecking.isEmpty() && out.isEmpty()) {
            LOGGER.info("[SuperChunk-Compat] [dh] Distant Horizons {}: all {} speed patches apply (a checked version).", version, total);
        } else if (newerThanChecked(version) || !selfChecking.isEmpty()) {
            // Shown at any log level: the install works, but is not at the speed it could be.
            LOGGER.warn("[SuperChunk-Compat] [dh] Distant Horizons {} is not a version SuperChunk's DH speed patches were checked against "
                            + "(3.0.0 to 3.3.3). Of {}: {} apply as checked, {} run under self-check {}, {} are left out {}. Nothing is broken: "
                            + "where a patch is out, DH runs its own code. A newer SuperChunk restores full speed.",
                    version, total, total - selfChecking.size() - out.size(), selfChecking.size(), selfChecking, out.size(), out);
        } else {
            LOGGER.info("[SuperChunk-Compat] [dh] Distant Horizons {}: {} of {} speed patches apply; left out for this DH version: {}.",
                    version, total - out.size(), total, out);
        }
        return result;
    }

    private static String installedVersion() {
        try {
            return LoadingModList.get().getModFileById(DistantHorizonsCompat.MOD_ID).getMods().get(0).getVersion().toString();
        } catch (Throwable t) {
            return "(unknown version)";
        }
    }

    /** Whether a version string's leading numbers are above the newest checked version. Unreadable counts as newer. */
    static boolean newerThanChecked(String version) {
        String[] parts = version.split("[^0-9]+");
        int at = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (at >= NEWEST_CHECKED.length) {
                break;
            }
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return true;
            }
            if (number != NEWEST_CHECKED[at]) {
                return number > NEWEST_CHECKED[at];
            }
            at++;
        }
        return at == 0;
    }

    /** DH's class file as shipped, by internal name; empty when no mod file has it. */
    private static byte[] classBytes(String internalName) {
        return CLASS_BYTES.computeIfAbsent(internalName, DhFastPaths::readClass);
    }

    /** Searched in every mod file as {@link OptionalTargets} does (no class loading). */
    private static byte[] readClass(String internalName) {
        String resource = internalName + ".class";
        try {
            for (ModFileInfo info : LoadingModList.get().getModFiles()) {
                Path path = info.getFile().findResource(resource);
                if (Files.exists(path)) {
                    return Files.readAllBytes(path);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-Compat] [dh] could not read {}", internalName, t);
        }
        return new byte[0];
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
