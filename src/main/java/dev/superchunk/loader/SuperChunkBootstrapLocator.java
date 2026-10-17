package dev.superchunk.loader;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.EarlyLoadingException;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileDependencyLocator;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.forgespi.locating.IModLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * SuperChunk's SINGLE-JAR bootstrap for Forge 1.20.1 — the mirror of the neo-1.21.1
 * shell design on the fmlloader 47.x APIs. ONE distributed jar that works as the mod on
 * both dists AND provides LWJGL core exactly when the running instance lacks it
 * (dedicated servers; a client's own {@code org.lwjgl} is left untouched).
 *
 * <p><b>Shell architecture.</b> The distributed {@code superchunk-<ver>.jar} is a thin
 * SHELL: only this locator (package {@code dev.superchunk.loader}), its top-level
 * {@code META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator}
 * registration (injected at jar-build time, so dev classpaths never carry it), and two
 * side-stashed jars under {@code META-INF/jarjar/} that appear in no jarJar metadata:
 * the REAL mod jar ({@value #CORE_MOD_RESOURCE} — the reobf'd {@code -all} artifact:
 * mods.toml, mixins + refmap, nested jarJar libraries, LWJGL natives) and an
 * {@code lwjgl-<ver>.jar} (module {@code org.lwjgl}).
 *
 * <p>{@code ModDirTransformerDiscoverer} hoists any mods-folder jar that declares a
 * loader SPI service at its top level into the modlauncher SERVICE layer and excludes it
 * from mods-folder discovery ({@code allExcluded()}) — the hook this class rides. During
 * dependency discovery ({@code ModDiscoverer} hands each locator a fresh snapshot at its
 * own turn, and SERVICE-layer providers enumerate before the BOOT-layer built-ins, so
 * the JarInJar locator that runs after this one picks up the contributed core's nested
 * libraries in its single global selection):
 * <ol>
 *   <li>the nested core jar is contributed as the regular SuperChunk MOD file
 *       (both dists);</li>
 *   <li>on a DEDICATED SERVER only, the stashed LWJGL core is contributed as a LIBRARY
 *       so the core's always-bundled {@code org.lwjgl.opencl}
 *       ({@code requires transitive org.lwjgl}) resolves. Bundling it on a client would
 *       make the layer read two modules named {@code org.lwjgl} (boot crash), which is
 *       why the choice is a runtime dist decision and not packaging.</li>
 * </ol>
 *
 * <p>Why a shell and not the whole mod jar hoisted: a hoisted jar is an AUTOMATIC module
 * exporting every package it contains; re-contributing the same jar as the GAME-layer
 * mod then has two layers exporting the mod's packages and any automatic game-layer
 * module reads both — {@code ResolutionException} (observed on the neo build's first
 * single-jar attempt). The shell holds ONLY {@code dev.superchunk.loader}, which the
 * core jar excludes, and pins its SERVICE-layer module name via
 * {@code Automatic-Module-Name} away from the GAME-layer mod module (named after the
 * modid by {@code ModJarMetadata}).
 *
 * <p>Nested jars are EXTRACTED to {@code <gamedir>/.superchunk-bootstrap/}
 * (content-hashed names, stale copies pruned) and contributed from disk — chosen over
 * the {@code jij:} filesystem after the latter refused zipfs-backed paths on neo; a
 * plain file path is bulletproof through {@code SecureJar}/{@code createMod}.
 *
 * <p>A core-mod contribution failure throws {@link EarlyLoadingException} (which the
 * discoverer surfaces as a proper loading error) — silently booting a world WITHOUT its
 * worldgen mod would be far worse. The LWJGL step degrades gracefully instead (GPU
 * backend unavailable, everything else runs).
 */
public final class SuperChunkBootstrapLocator extends AbstractJarFileDependencyLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Bootstrap");

    /** The real SuperChunk mod jar, stashed in the shell (stable, version-free name). */
    private static final String CORE_MOD_RESOURCE = "META-INF/jarjar/superchunk-core.jar";

    /** Directory inside the shell scanned for the LWJGL core jar ({@code lwjgl-<ver>.jar}, no natives). */
    private static final String STASH_DIR = "META-INF/jarjar";

    /** A class that exists (only) in the core mod jar — identifies an already-loaded host (dev flows). */
    private static final String HOST_MARKER_RESOURCE = "dev/superchunk/SuperChunk.class";

    /** The service file whose presence at a jar's top level marks it as this bootstrap shell. */
    private static final String SERVICE_RESOURCE = "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator";

    @Override
    public String name() {
        return "superchunk-bootstrap";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }

    /** The lwjgl core (no mods.toml, no FMLModType) must claim LIBRARY, exactly like jarJar'd libraries. */
    @Override
    protected String getDefaultJarModType() {
        return IModFile.Type.LIBRARY.name();
    }

    @Override
    public List<IModFile> scanMods(Iterable<IModFile> loadedMods) {
        List<IModFile> results = new ArrayList<>();
        // If the SuperChunk mod is already among the loaded mods (dev/userdev flows load the
        // classes directly and never see this shell), only the LWJGL duty remains.
        IModFile host = findLoadedHost(loadedMods);
        FileSystem shellFs = null;
        try {
            if (host == null) {
                Path shell = locateShellJar();
                if (shell == null) {
                    // Hoisted service jars are excluded from mods-folder discovery, so failing here
                    // would mean the mod silently does not load — HARD error, surfaced by FML.
                    throw earlyError("SuperChunk bootstrap could not locate its own jar file; "
                            + "the SuperChunk mod cannot be loaded.", null);
                }
                try {
                    shellFs = FileSystems.newFileSystem(shell);
                    Path coreJar = shellFs.getPath(CORE_MOD_RESOURCE);
                    if (!Files.isRegularFile(coreJar)) {
                        throw new IllegalStateException("shell jar " + shell.getFileName()
                                + " carries no " + CORE_MOD_RESOURCE);
                    }
                    Path extracted = extractToCache(coreJar, "superchunk-core");
                    IModLocator.ModFileOrException mfe = createMod(extracted);
                    if (mfe.ex() != null) {
                        throw mfe.ex();
                    }
                    host = mfe.file();
                    results.add(host);
                    LOGGER.debug("Single-jar bootstrap: contributed the SuperChunk mod from {}!{} (via {}).",
                            shell.getFileName(), CORE_MOD_RESOURCE, extracted.getFileName());
                } catch (EarlyLoadingException e) {
                    throw e;
                } catch (Throwable t) {
                    LOGGER.error("SuperChunk bootstrap failed to contribute the core mod from {}.", shell, t);
                    throw earlyError("SuperChunk bootstrap failed to load the mod from "
                            + shell.getFileName() + ": " + t, t);
                }
            } else {
                LOGGER.debug("SuperChunk mod already present ({}); bootstrap only handles LWJGL.",
                        host.getFileName());
            }

            // LWJGL core: only when the instance does not already have it (= dedicated server).
            if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
                IModFile lwjgl = contributeLwjglCore(shellFs, host);
                if (lwjgl != null) {
                    results.add(lwjgl);
                }
            }
        } finally {
            // The zipfs only backed extraction; everything contributed lives on real disk paths.
            if (shellFs != null) {
                try {
                    shellFs.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    /**
     * Locates + extracts the stashed LWJGL core (from the shell when available, else stashed
     * inside the host mod file) and claims it as a LIBRARY (module {@code org.lwjgl}), landing
     * on the same layer as the jarJar'd {@code org.lwjgl.opencl}. Never throws: on failure the
     * GPU/OpenCL backend is simply unavailable and org.lwjgl.opencl's own requirement produces
     * the canonical resolution error.
     */
    private IModFile contributeLwjglCore(FileSystem shellFs, IModFile host) {
        try {
            Path coreJar = null;
            if (shellFs != null) {
                try (Stream<Path> stash = Files.list(shellFs.getPath(STASH_DIR))) {
                    coreJar = stash.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("lwjgl-") && n.endsWith(".jar") && !n.contains("natives");
                    }).findFirst().orElse(null);
                }
            }
            if (coreJar == null && host != null) {
                Path inHost = host.findResource("META-INF/jarjar/lwjgl-3.3.3.jar");
                if (inHost != null && Files.exists(inHost)) {
                    coreJar = inHost;
                }
            }
            if (coreJar == null) {
                LOGGER.warn("Dedicated server: no stashed lwjgl core jar found; module org.lwjgl will NOT "
                        + "be contributed. The GPU/OpenCL backend may be unavailable.");
                return null;
            }
            Path extracted = extractToCache(coreJar, "lwjgl-core");
            IModLocator.ModFileOrException mfe = createMod(extracted);
            if (mfe.ex() != null || mfe.file() == null) {
                LOGGER.warn("Dedicated server: stashed lwjgl core was not accepted as a library.", mfe.ex());
                return null;
            }
            LOGGER.debug("Dedicated server: contributed bundled lwjgl-core (module org.lwjgl) to satisfy "
                    + "org.lwjgl.opencl.");
            return mfe.file();
        } catch (Throwable t) {
            LOGGER.error("Dedicated server: failed to contribute bundled lwjgl-core.", t);
            return null;
        }
    }

    /** Content-hashed extraction (never reuses a stale copy across mod updates); prunes old siblings. */
    private static Path extractToCache(Path nestedJar, String label) throws Exception {
        byte[] bytes = Files.readAllBytes(nestedJar);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        Path dir = FMLPaths.GAMEDIR.get().resolve(".superchunk-bootstrap");
        Files.createDirectories(dir);
        Path target = dir.resolve(label + "-" + hash + ".jar");
        if (!Files.exists(target) || Files.size(target) != bytes.length) {
            Path tmp = Files.createTempFile(dir, label, ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try (Stream<Path> siblings = Files.list(dir)) {
            siblings.filter(p -> p.getFileName().toString().startsWith(label + "-")
                    && !p.equals(target)).forEach(p -> p.toFile().delete());
        } catch (Throwable ignored) {
            // stale-copy cleanup is best-effort
        }
        return target;
    }

    private static IModFile findLoadedHost(Iterable<IModFile> loadedMods) {
        for (IModFile mf : loadedMods) {
            try {
                Path p = mf.findResource(HOST_MARKER_RESOURCE);
                if (p != null && Files.exists(p)) {
                    return mf;
                }
            } catch (Throwable ignored) {
                // Some mod files may reject odd resource lookups; just skip them.
            }
        }
        return null;
    }

    /**
     * The on-disk path of the shell jar this class was loaded from. Primary: the class's code
     * source ({@code file:} or modlauncher's {@code union:/...jar%23n!/} form). Fallback: scan
     * the mods folder for a jar carrying this locator's service registration + core stash.
     */
    private static Path locateShellJar() {
        try {
            var source = SuperChunkBootstrapLocator.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path p = pathFromLocation(source.getLocation().toURI());
                if (p != null && Files.isRegularFile(p)) {
                    return p;
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("code-source self-location failed ({}), falling back to mods-folder scan",
                    String.valueOf(t));
        }
        try (Stream<Path> files = Files.list(FMLPaths.MODSDIR.get())) {
            for (Path p : files.filter(f -> f.toString().endsWith(".jar")).toList()) {
                try (JarFile jf = new JarFile(p.toFile())) {
                    var svc = jf.getEntry(SERVICE_RESOURCE);
                    if (svc == null || jf.getEntry(CORE_MOD_RESOURCE) == null) {
                        continue;
                    }
                    try (InputStream in = jf.getInputStream(svc)) {
                        if (new String(in.readAllBytes(), StandardCharsets.UTF_8)
                                .contains(SuperChunkBootstrapLocator.class.getName())) {
                            return p;
                        }
                    }
                } catch (Throwable ignored) {
                    // unreadable jar — skip
                }
            }
        } catch (Throwable t) {
            LOGGER.error("mods-folder scan for the SuperChunk shell jar failed.", t);
        }
        return null;
    }

    /** {@code file:} URIs directly; {@code union:}/{@code jar:} forms by peeling the wrapper syntax. */
    private static Path pathFromLocation(java.net.URI uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                return Path.of(uri);
            }
            String ssp = uri.getRawSchemeSpecificPart();
            // union:/abs/path/to.jar%23<n>!/  |  jar:file:/abs/path/to.jar!/
            int bang = ssp.indexOf("!/");
            if (bang >= 0) {
                ssp = ssp.substring(0, bang);
            }
            if (ssp.startsWith("file:")) {
                ssp = ssp.substring("file:".length());
            }
            int fragment = ssp.indexOf("%23");
            if (fragment >= 0) {
                ssp = ssp.substring(0, fragment);
            }
            return Path.of(java.net.URLDecoder.decode(ssp, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    private static EarlyLoadingException earlyError(String message, Throwable cause) {
        return new EarlyLoadingException(message, cause,
                List.of(new EarlyLoadingException.ExceptionData(message)));
    }

    @Override
    public String toString() {
        return "superchunk-bootstrap";
    }
}
