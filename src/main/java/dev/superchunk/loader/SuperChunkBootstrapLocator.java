package dev.superchunk.loader;

import cpw.mods.jarhandling.JarContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IOrderedProvider;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * SuperChunk's SINGLE-JAR bootstrap: ONE distributed jar that works as the mod on both
 * dists AND provides LWJGL core exactly when the running instance lacks it (dedicated
 * servers). Replaces the old two-jar scheme (mod jar + a separate
 * {@code -lwjgl-server-locator} companion).
 *
 * <p><b>Shell architecture.</b> The distributed {@code superchunk-<ver>.jar} is a thin
 * SHELL: it contains only this locator (package {@code dev.superchunk.loader}), its
 * top-level {@code META-INF/services} registration, and two side-stashed jars under
 * {@code META-INF/jarjar/} that are deliberately absent from any jarJar metadata:
 * <ul>
 *   <li>{@value #CORE_MOD_RESOURCE} — the REAL SuperChunk mod jar (unchanged content:
 *       mods.toml, mixins, nested jarJar libraries, LWJGL natives).</li>
 *   <li>an {@code lwjgl-<ver>.jar} — LWJGL core, module {@code org.lwjgl}.</li>
 * </ul>
 *
 * <p>Because the shell declares a loader-SPI service at its top level, FML's
 * {@code ModDirTransformerDiscoverer} hoists it into the modlauncher SERVICE layer and
 * excludes it from mods-folder discovery — which is the hook this class rides. During
 * dependency discovery it:
 * <ol>
 *   <li>contributes the nested core jar as the regular SuperChunk MOD file (both dists).
 *       {@link #getPriority()} is above {@code JarInJarDependencyLocator}'s default (0),
 *       and FML hands each dependency locator a fresh snapshot at its own turn, so the
 *       normal global jar-in-jar pass then loads the core's nested libraries (rxjava,
 *       lwjgl-opencl, ...) exactly like any other mod's.</li>
 *   <li>on a DEDICATED SERVER only, contributes the stashed LWJGL core. A client already
 *       ships module {@code org.lwjgl} (the game renders with LWJGL) and a second copy
 *       makes the layer read two modules of that name ("reads more than one module named
 *       org.lwjgl" boot crash); a headless server ships none, and without it the core
 *       mod's always-bundled {@code org.lwjgl.opencl} ({@code requires transitive
 *       org.lwjgl}) fails resolution.</li>
 * </ol>
 *
 * <p><b>Why a shell and not just hoisting the whole mod jar:</b> a hoisted jar becomes an
 * AUTOMATIC module in the SERVICE layer, exporting every package it contains. If that jar
 * also carried the mod's classes, the re-contributed GAME-layer mod module would export
 * the same packages, and any automatic module in the game layer (e.g. jarJar'd
 * mixinextras) reads both layers — {@code ResolutionException: Modules X and Y export
 * package ...} (observed). The shell holds ONLY {@code dev.superchunk.loader}, which the
 * core jar deliberately excludes, so the layers never overlap. The shell's module name is
 * additionally pinned via {@code Automatic-Module-Name} so it cannot collide with the
 * mod module (named after the modid by FML's {@code ModJarMetadata}).
 *
 * <p>Nested jars are opened via FML's own jar-in-jar ({@code jij:}) file system; if that
 * refuses the zipfs-backed shell paths, the jar is EXTRACTED to
 * {@code <gamedir>/.superchunk-bootstrap/} (content-hashed name, so updates never reuse a
 * stale copy) and contributed from disk — functionally identical, just less elegant.
 *
 * <p>A core-mod contribution failure is fatal ({@link ModLoadingException}) — silently
 * booting a world WITHOUT its worldgen mod would be far worse than a clear load error.
 * The LWJGL step degrades gracefully instead (GPU backend unavailable, all else runs).
 */
public final class SuperChunkBootstrapLocator implements IDependencyLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Bootstrap");

    /** The real SuperChunk mod jar, stashed in the shell (stable, version-free name). */
    private static final String CORE_MOD_RESOURCE = "META-INF/jarjar/superchunk-core.jar";

    /** Directory inside the shell scanned for the LWJGL core jar ({@code lwjgl-<ver>.jar}, no natives). */
    private static final String STASH_DIR = "META-INF/jarjar";

    /** A class that exists (only) in the core mod jar — identifies an already-loaded host (dev flows). */
    private static final String HOST_MARKER_RESOURCE = "dev/superchunk/SuperChunk.class";

    /** The service file whose presence at a jar's top level marks it as this bootstrap shell. */
    private static final String SERVICE_RESOURCE = "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator";

    /** Run BEFORE JarInJarDependencyLocator (priority 0) so the global jarJar pass sees the contributed mod. */
    @Override
    public int getPriority() {
        return IOrderedProvider.DEFAULT_PRIORITY + 100;
    }

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        // If the SuperChunk mod is already among the loaded mods (dev/userdev flows load the
        // classes directly and never see this shell), only the LWJGL duty remains.
        IModFile host = findLoadedHost(loadedMods);
        FileSystem shellFs = null;
        if (host == null) {
            Path shell = locateShellJar();
            if (shell == null) {
                // Hoisted service jars are excluded from mod discovery, so failing here would mean
                // the mod silently does not load — that must be a HARD error, not a warning.
                throw new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.technical_error",
                        "SuperChunk bootstrap could not locate its own jar file; the SuperChunk mod cannot be loaded."));
            }
            try {
                shellFs = FileSystems.newFileSystem(shell);
                Path coreJar = shellFs.getPath(CORE_MOD_RESOURCE);
                if (!Files.isRegularFile(coreJar)) {
                    throw new IllegalStateException("shell jar " + shell.getFileName() + " carries no " + CORE_MOD_RESOURCE);
                }
                host = contributeNested(pipeline, coreJar, ModFileDiscoveryAttributes.DEFAULT, "superchunk-core");
                LOGGER.info("Single-jar bootstrap: contributed the SuperChunk mod from {}!{}.",
                        shell.getFileName(), CORE_MOD_RESOURCE);
            } catch (ModLoadingException e) {
                throw e;
            } catch (Throwable t) {
                LOGGER.error("SuperChunk bootstrap failed to contribute the core mod from {}.", shell, t);
                throw new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.technical_error",
                        "SuperChunk bootstrap failed to load the mod from " + shell.getFileName() + ": " + t));
            }
        } else {
            LOGGER.debug("SuperChunk mod already present ({}); bootstrap only handles LWJGL.", host.getFileName());
        }

        // LWJGL core: only when the instance does not already have it (= dedicated server).
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            contributeLwjglCore(pipeline, shellFs, host);
        }
    }

    /**
     * Contributes the stashed LWJGL core (module {@code org.lwjgl}) as a LIBRARY with the
     * SuperChunk mod as parent, landing on the same module layer as the jarJar'd
     * {@code org.lwjgl.opencl} so its {@code requires transitive org.lwjgl} resolves. The
     * jar is looked up in the shell when available, else inside the host mod file (covers
     * an already-loaded host that still stashes one). Never throws: on failure the
     * GPU/OpenCL backend is simply unavailable.
     */
    private static void contributeLwjglCore(IDiscoveryPipeline pipeline, FileSystem shellFs, IModFile host) {
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
                if (inHost != null && Files.isRegularFile(inHost)) {
                    coreJar = inHost;
                }
            }
            if (coreJar == null) {
                LOGGER.warn("Dedicated server: no stashed lwjgl core jar found; module org.lwjgl will NOT be "
                        + "contributed and the GPU/OpenCL backend may be unavailable.");
                return;
            }
            ModFileDiscoveryAttributes attributes = host != null
                    ? ModFileDiscoveryAttributes.DEFAULT.withParent(host)
                    : ModFileDiscoveryAttributes.DEFAULT;
            contributeNested(pipeline, coreJar, attributes, "lwjgl-core");
            LOGGER.info("Dedicated server: contributed bundled lwjgl-core (module org.lwjgl) to satisfy org.lwjgl.opencl.");
        } catch (Throwable t) {
            // Never throw: org.lwjgl.opencl's own requirement produces the canonical resolution
            // error if core is truly needed and missing.
            LOGGER.error("Dedicated server: failed to contribute bundled lwjgl-core.", t);
        }
    }

    /**
     * Reads + adds one nested jar into the discovery pipeline. Primary route: FML's
     * jar-in-jar ({@code jij:}) file system over the nested path — the exact mechanism
     * {@code JarInJarDependencyLocator.loadModFileFrom} uses. Fallback: extract to
     * {@code <gamedir>/.superchunk-bootstrap/<name>-<contenthash>.jar} and contribute the
     * plain file (bulletproof against FS-provider nesting quirks).
     */
    private static IModFile contributeNested(IDiscoveryPipeline pipeline, Path nestedJar,
                                             ModFileDiscoveryAttributes attributes, String label) throws Exception {
        JarContents contents;
        try {
            URI uri = new URI("jij:" + nestedJar.toAbsolutePath().toUri().getRawSchemeSpecificPart()).normalize();
            FileSystem jijFs = FileSystems.newFileSystem(uri, Map.of("packagePath", nestedJar));
            contents = JarContents.of(jijFs.getPath("/"));
        } catch (Throwable jij) {
            Path extracted = extractToCache(nestedJar, label);
            LOGGER.info("jij filesystem unavailable for {} ({}); using extracted copy {}.",
                    label, String.valueOf(jij), extracted.getFileName());
            contents = JarContents.of(extracted);
        }
        IModFile file = pipeline.readModFile(contents, attributes);
        if (file == null || !pipeline.addModFile(file)) {
            throw new IllegalStateException("discovery pipeline did not accept nested jar '" + label + "'");
        }
        return file;
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

    private static IModFile findLoadedHost(List<IModFile> loadedMods) {
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
     * The on-disk path of the shell jar this class was loaded from. Primary: the class's
     * code source, handling the plain {@code file:} form and modlauncher's
     * {@code union:/...jar%23n!/} form. Fallback: scan the mods folder for a jar carrying
     * this locator's service registration.
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
            LOGGER.debug("code-source self-location failed ({}), falling back to mods-folder scan", String.valueOf(t));
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
    private static Path pathFromLocation(URI uri) {
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
            return Path.of(URLDecoder.decode(ssp, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "superchunk-bootstrap";
    }
}
