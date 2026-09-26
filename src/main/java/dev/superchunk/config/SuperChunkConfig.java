package dev.superchunk.config;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * SuperChunk's <b>single, unified configuration</b> ({@code config/superchunk.properties}).
 *
 * <p>SuperChunk merges five engines (C2ME, ScalableLux, Lithium, Noisium, VMP) plus a
 * from-scratch GPU/OpenCL worldgen offload — each of which historically read its OWN config
 * file through its OWN loader at its OWN time. This class makes ONE file the source of truth
 * and <i>applies it through to each engine</i> ("write-through") at the right moment:
 *
 * <ul>
 *   <li><b>GPU</b> ({@code gpu.*}) — consumed directly by {@code GpuConfig} at backend init.</li>
 *   <li><b>C2ME</b> ({@code c2me.*}) — pushed as {@code -Dc2me.base.config.override.<key>} system
 *       properties, which C2ME's {@code ConfigSystem} reads with precedence over {@code c2me.toml}
 *       (and which survive its config-version reset).</li>
 *   <li><b>Lithium</b> ({@code lithium.*}) — written to {@code config/lithium.properties} (as
 *       Lithium's {@code mixin.*} keys) BEFORE {@code LithiumMixinPlugin.onLoad()} reads it.</li>
 *   <li><b>Lighting</b> ({@code lighting.parallelism}) — written to {@code scalablelux.properties}
 *       (note: ScalableLux is externally managed by C2ME, so this is effectively inert today).</li>
 *   <li><b>Player loading</b> ({@code player.*}) — read directly by SuperChunk at runtime (no
 *       write-through); see {@link FullSpeedLoading} for the full-throttle chunk-loading toggle.</li>
 * </ul>
 *
 * <p>{@link #applyEarly()} runs from {@code SuperChunkMixinPlugin.onLoad()} — the earliest mod
 * hook, and {@code superchunk.mixins.json} is declared first in {@code neoforge.mods.toml}, so the
 * write-through happens before the Lithium/C2ME loaders fire. It is idempotent.
 *
 * <p><b>Migration.</b> On first run, if the unified file is absent but the legacy per-engine files
 * exist ({@code superchunk-gpu.properties}, {@code lithium.properties}), their values are folded in
 * so existing setups keep working; the unified file is then written with documented defaults.
 *
 * <p>Never throws: any I/O error logs and falls back to built-in defaults.
 */
public final class SuperChunkConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Config");

    private static final String FILE_NAME = "superchunk.properties";
    private static final String GPU_PREFIX = "gpu.";
    private static final String LITHIUM_PREFIX = "lithium.";
    private static final String C2ME_PREFIX = "c2me.";
    private static final String C2ME_OVERRIDE_PROP = "c2me.base.config.override.";
    private static final String DEFAULT_PLACEHOLDER = "default";

    /**
     * The Lithium rule keys (the part AFTER the {@code lithium.} prefix) that overlap
     * C2ME's chunk-system / serializer rewrites and are documented to crash/corrupt on
     * double-application. They are HARD-PINNED to false everywhere they flow (legacy migration
     * + write-through to {@code lithium.properties}), overriding any user/migrated {@code true}
     * by design — a safety pin, not a preference. See {@link #pinLithium}.
     *
     * <p><b>{@code chunk.no_locking} was removed from this set (2026-08-05).</b> Round 9
     * (2026-07-16) flipped its default to true for a measured win, but leaving the key here meant
     * {@link #pinLithium} silently rewrote it back to false on the way into
     * {@code lithium.properties} — so the shipped build still paid vanilla's
     * {@code ThreadingDetector} ReentrantLock+Semaphore on every {@code PalettedContainer}
     * get/set. A fresh CPU-only r2048 JFR profile put that at ~3% of all worldgen samples
     * (82.6% of the time inside {@code PalettedContainer.getAndSet}). It does NOT overlap C2ME:
     * nothing outside {@code lithium.mixin.chunk.no_locking} touches {@code acquire()},
     * {@code release()} or {@code threadingDetector}, and the other {@code PalettedContainer}
     * mixins in the tree target only {@code read}/{@code count}/{@code getAll}/serialization.
     */
    private static final java.util.Set<String> LITHIUM_FORCE_DISABLED = java.util.Set.of(
            "gen.cached_generator_settings",
            "chunk.serialization",
            "world.tick_scheduler",
            "world.chunk_access");

    /** Mutable caches that assume a single ticking thread in each level. */
    private static final java.util.Set<String> SKEIN_LITHIUM_DISABLED = java.util.Set.of(
            "alloc.chunk_random", "util.world_border_listener",
            "world.block_entity_ticking.world_border", "world.chunk_access", "block.hopper",
            "chunk.entity_class_groups", "entity.inactive_navigations",
            "entity.collisions.unpushable_cramming", "util.block_entity_retrieval");

    private static boolean skeinInstalled() {
        var mods = net.neoforged.fml.loading.LoadingModList.get();
        return mods != null && mods.getMods().stream().anyMatch(mod -> "skein".equals(mod.getModId()));
    }

    /** Ordered key -> default value. Order drives the written file layout (see {@link #SECTIONS}). */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    /** key -> section title emitted just before it when writing the file. */
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();
    /** key -> optional section introduction printed under the title. */
    private static final Map<String, String> SECTION_INTROS = new LinkedHashMap<>();
    /** key -> plain-language description printed above the option in the file. */
    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    static {
        section("gpu.enabled", "GPU worldgen offload (OpenCL)",
                "Moves terrain density math to the graphics card. Off by default. It needs an OpenCL GPU "
                        + "with double-precision (fp64) support; without one, SuperChunk uses the CPU.");
        def("gpu.enabled", "false",
                "Master switch for GPU worldgen. true = compute terrain noise on the GPU; false = CPU only. "
                        + "If no usable device is found, the server logs why and uses the CPU.");
        def("gpu.platformIndex", "-1",
                "OpenCL platform (driver) to use, by its number in the startup log's 'Platform[n]' lines. "
                        + "-1 = choose automatically.");
        def("gpu.deviceIndex", "-1",
                "GPU within that platform, by its number in the startup log. -1 = choose automatically.");
        def("gpu.desiredFp", "fp64",
                "Precision of the GPU terrain math. fp64 = double precision, terrain identical to vanilla; "
                        + "devices without fp64 are not used. auto = fp64 if the device supports it, otherwise "
                        + "fp32 (terrain may differ slightly from vanilla). fp32 = always single precision "
                        + "(testing only).");
        def("gpu.coalesce", "true",
                "Compute each density function for a whole chunk in one GPU call instead of one call per "
                        + "column. Same results, far fewer GPU calls. Needed by fuseInterpolated, "
                        + "asyncReadback and batchChunks.");
        def("gpu.fuseInterpolated", "true",
                "Compute all of a chunk's interpolated density grids in a single GPU call. Same results. "
                        + "Needs coalesce.");
        def("gpu.asyncReadback", "true",
                "Start a chunk's GPU work early and collect the results later, so the worker thread keeps "
                        + "doing CPU work meanwhile. Same results. Needs fuseInterpolated.");
        // false (2026-07-16, round 8): with the compact-ids decide chain default-on the GPU
        // is the scarce resource — biome-on measured -22% at 24 workers (805.5 vs 1032 cps).
        // Biomes are bit-identical either way (the CPU path IS vanilla).
        def("gpu.offloadBiome", "false",
                "Also compute biome climate noise on the GPU. Biomes are identical either way. Off because "
                        + "the GPU is usually the busier side: this measured 11-22% slower overall. Try it "
                        + "only if your GPU is mostly idle.");
        // true (2026-07-16, round 8): the batcher pipeline is the validated fast path
        // (rounds 6-8 smoke/parity/census-gated) and the compact-ids chain requires it.
        def("gpu.batchChunks", "true",
                "Combine several chunks into one GPU call and free the worker thread while it runs. This is "
                        + "the fast path and is required for GPU block decisions (see decideFp32). "
                        + "false = one GPU call per chunk.");
        def("gpu.batchLimit", "32",
                "Maximum number of chunks combined into one GPU call (1 or more).");
        def("gpu.batchWindowMicros", "500",
                "How long, in microseconds, a chunk waits for others to join its batch before the batch is "
                        + "sent anyway (0 or more). Longer = bigger batches but more waiting.");
        // In-flight batched dispatches (slot ring in GpuBatchDispatcher). 2 = the measured
        // sweet spot: restores the overlap the chained climate+density batches need (free
        // climate offload) at zero cost to biome-off; 1 serializes the chain (~8% loss
        // when biome is on); 4 shrinks batches (~5% loss everywhere on a strong CPU).
        def("gpu.batchPipelineDepth", "2",
                "How many batches may be on the GPU at the same time (1-16). 2 measured best on an "
                        + "i7-14700K with an RTX 3070; a weak CPU with a strong GPU may gain from 3 or 4.");
        def("gpu.mergeKernels", "false",
                "Compile all density functions into a single OpenCL program. Same results, but the "
                        + "first-time compile becomes far slower for normal worlds. Leave false.");
        // false (2026-07-07): structure-probe column samplers take the CPU bytecode path —
        // the GPU probe path was ~11.4k worker-side parked dispatches per r2048 pregen;
        // CPU routing measured +18% cps at 21 workers. Parity-free (GPU is FP64 bit-exact).
        def("gpu.subLatticeGpu", "false",
                "Also send the small terrain-height probes used for structure placement to the GPU. "
                        + "Measured 18% slower, because each probe waits for its own GPU round trip. "
                        + "Leave false.");
        // fp32 decision math for the compact-ids DECIDE kernel (corner buffer + aq_decide
        // comparisons stay fp64). Auto-falls-back to the fp64 program if the fp32 build
        // fails. The compact-ids chain itself is DEFAULT ON since round 8
        // (-Dsuperchunk.gpu.compactIds=off restores strict mode); its output envelope is
        // census-measured — zero deviations across every census run (455.9M + 4x460M blocks).
        def("gpu.decideFp32", "true",
                "Precision of the GPU's per-block decisions (stone, air, water, lava, ore veins); the "
                        + "density grids stay fp64. true = single precision, faster; very rarely a block can "
                        + "differ from vanilla (one stone/air difference in 143 million blocks on one tested "
                        + "seed). false = double precision, no known differences. Falls back to fp64 "
                        + "automatically if the fp32 program fails to build. To keep block decisions on the "
                        + "CPU entirely, start the server with -Dsuperchunk.gpu.compactIds=off.");
        // FMA contraction for the fp32 decide program. Census-zero but measured
        // throughput-NEUTRAL on GA104 (the kernel is latency-bound) — experiment flag.
        def("gpu.decideFmaContract", "false",
                "Allow fused multiply-add in the fp32 decision program. Experimental: no speed gain was "
                        + "measured on an RTX 3070.");
        // fp32 climate kernel experiment. MEASURED-REFUTED on vanilla noise (domain-warp
        // amplifies fp32 drift to ~0.47 quantized units; the boot gate auto-refuses and
        // falls back to fp64 — zero behavior change). Kept for un-warped datapack noise.
        def("gpu.climateFp32", "false",
                "Single precision for the GPU climate program (only used with offloadBiome=true). "
                        + "Experimental: on vanilla terrain a startup check rejects it and keeps fp64. Only "
                        + "useful for datapacks whose climate noise is not domain-warped.");
        def("gpu.latticeCoords", "true",
                "Generate grid coordinates on the GPU instead of uploading them. Same results. Needed by "
                        + "coalesce; leave true.");
        def("gpu.mappedBuffers", "true",
                "Use pinned host memory for GPU transfers (zero-copy on integrated GPUs, faster copies on "
                        + "discrete ones). Leave true unless a driver has problems with it.");
        def("gpu.profile", "false",
                "Record GPU timings (upload, kernel, readback) and log a breakdown when the server stops. "
                        + "Diagnostics; small overhead.");
        def("gpu.selftest.noise", "false",
                "Diagnostics: at startup, compare GPU noise with the CPU implementation and log the result.");
        def("gpu.selftest.dfc", "false",
                "Diagnostics: at startup, compare the GPU density-function compiler's output with the CPU "
                        + "and log the result.");
        def("gpu.selftest.gpu_parity", "false",
                "Diagnostics: at startup, compare GPU density values with vanilla's. On fp64 devices they "
                        + "are expected to match exactly.");

        section("c2me.globalExecutorParallelism", "C2ME multithreaded chunk system",
                "Each c2me.<key> is passed to C2ME as -Dc2me.base.config.override.<key> and takes precedence "
                        + "over config/c2me.toml. 'default' = C2ME's own default applies. Any other C2ME "
                        + "option can be added here as c2me.<key>=<value>. A -D option given on the command "
                        + "line always wins.");
        def("c2me.globalExecutorParallelism", DEFAULT_PLACEHOLDER,
                "Number of worldgen worker threads. default = chosen by C2ME from CPU cores and heap size "
                        + "(the startup log says when the heap is the limit). A number sets it directly; each "
                        + "extra thread needs roughly 0.6 GB more heap.");
        def("c2me.noTickViewDistance.enabled", DEFAULT_PLACEHOLDER,
                "Load and send chunks beyond the simulation distance without ticking them, so the view "
                        + "distance can be larger than the simulation distance cheaply. C2ME default: true.");
        def("c2me.noTickViewDistance.maxConcurrentChunkLoads", DEFAULT_PLACEHOLDER,
                "How many of those no-tick chunk loads run at once. Lower = lower latency, higher = faster "
                        + "loading. C2ME default: worker threads + 1 (player.fullSpeedLoading can raise it).");
        def("c2me.noTickViewDistance.enableExtRenderDistanceProtocol", DEFAULT_PLACEHOLDER,
                "Let clients request render distances above 127 chunks through C2ME's extended protocol, "
                        + "which SuperChunk clients use automatically. C2ME default: true.");
        def("c2me.generalOptimizations.autoSave.mode", DEFAULT_PLACEHOLDER,
                "How autosave runs. ENHANCED = when the server has spare time after a tick; VANILLA = every "
                        + "tick during ticking; PERIODIC = every 6000 ticks (the pre-1.18 behavior). "
                        + "C2ME default: ENHANCED.");
        def("c2me.generalOptimizations.midTickChunkTasksInterval", DEFAULT_PLACEHOLDER,
                "Interval, in nanoseconds, for running chunk tasks in the middle of a server tick. This "
                        + "speeds up chunk loading while the server is busy, but can raise tick time while "
                        + "chunks load. -1 disables it. C2ME default: 100000 (0.1 ms). Leave it unless you "
                        + "know you need it.");
        def("c2me.ioSystem.gcFreeChunkSerializer", DEFAULT_PLACEHOLDER,
                "Experimental C2ME chunk saver with fewer memory allocations. Always off in SuperChunk: it "
                        + "does not write NeoForge's per-chunk mod data (data attachments, ChunkDataEvent.Save), "
                        + "so other mods would lose data on every save. Setting it to true only logs a warning.");
        def("c2me.chunkSystem.recoverFromErrors", DEFAULT_PLACEHOLDER,
                "If a chunk fails to load, regenerate it from scratch instead of failing. Whatever was built "
                        + "in that chunk is lost. C2ME default: false.");
        def("c2me.chunkSystem.lowMemoryMode", DEFAULT_PLACEHOLDER,
                "Unload unused chunks aggressively to save memory. Only has an effect together with "
                        + "c2me.chunkSystem.useLegacyScheduling=false (C2ME's default is true). "
                        + "C2ME default: false.");
        // TRUE by default (companion to player.sendAtChunkSending, below): sending the no-tick ring
        // at CHUNK_SENDING exposes non-post-processed chunks, so mushrooms could briefly appear
        // (MC-276863). This C2ME workaround suppresses them with no other worldgen effect.
        def("c2me.chunkSystem.suppressGhostMushrooms", "true",
                "Work around MC-276863, where mushrooms can briefly appear in chunks that are not "
                        + "post-processed yet. On because player.sendAtChunkSending shows such chunks to "
                        + "players earlier. No other effect on worldgen; false = vanilla behavior.");

        section("client.maxRenderDistance", "Client render distance", null);
        def("client.maxRenderDistance", "64",
                "Highest render distance the client's video-settings slider allows (32-512; vanilla is 32). "
                        + "This only sets the slider limit: the server still decides how far it sends. On "
                        + "joining a server, a distance above 32 is requested through C2ME's extended protocol "
                        + "(the only way above 127), so a slider change takes effect the next time you join. "
                        + "Client memory for chunks grows with the square of the distance (64 uses about 4x as "
                        + "much as 32). Read by clients only.");

        section("lithium.gen.cached_generator_settings", "Lithium",
                "Written to config/lithium.properties as mixin.<rule>. The four rules set to false below "
                        + "overlap C2ME's chunk system and are always forced off, whatever this file says. "
                        + "Other Lithium rules can be added here as lithium.<rule>=true|false. When Skein is "
                        + "installed, SuperChunk also turns off Lithium caches that assume one ticking thread "
                        + "per world.");
        def("lithium.gen.cached_generator_settings", "false",
                "Overlaps C2ME's world generation; always forced to false.");
        def("lithium.chunk.serialization", "false",
                "Overlaps C2ME's chunk saving; always forced to false.");
        // TRUE since round 9 (2026-07-16): removes vanilla's ThreadingDetector
        // ReentrantLock on EVERY PalettedContainer get/set — measured 4.8% of worker
        // CPU at 24 workers (JFR), +2.3% cps interleaved A/B. Detection-only removal
        // (Lithium-standard, shipped to production for years); no mixin conflict with
        // the C2ME chunk system (verified: nothing else touches acquire/release), and
        // zero ThreadingDetector trips ever observed across all pregen legs.
        // 2026-08-05: this default was INERT until now — the key was still listed in
        // LITHIUM_FORCE_DISABLED, so pinLithium rewrote it to false on write-through and
        // config/lithium.properties shipped mixin.chunk.no_locking=false. See that field.
        // Re-measured after removing the pin (28-core box, 20 workers, ZGC, CPU-only r2048):
        // ThreadingDetector 3.04% of worldgen JFR samples -> 0%, PalettedContainer.getAndSet
        // 3.68% -> 1.45%. Wall clock was NEUTRAL there (4 interleaved A/B rounds, 97.25 s mean
        // both legs) — that box is not bound by the freed CPU, so treat the round-9 "+2.3% cps"
        // as hardware-specific. 5 clean r2048 pregens, zero ThreadingDetector trips.
        def("lithium.chunk.no_locking", "true",
                "Remove vanilla's concurrent-access check, a lock taken on every block write in a chunk "
                        + "section (about 3% of worldgen CPU). Safe alongside C2ME.");
        def("lithium.world.tick_scheduler", "false",
                "Overlaps C2ME's chunk system; always forced to false.");
        def("lithium.world.chunk_access", "false",
                "Overlaps C2ME's chunk system; always forced to false.");

        section("lighting.parallelism", "Lighting (ScalableLux)", null);
        def("lighting.parallelism", "-1",
                "Threads for ScalableLux lighting (-1 = automatic). Currently has no effect, because C2ME "
                        + "runs the lighting itself. Written to scalablelux.properties for completeness.");

        section("pregen.chunkyWorkingCount", "Chunky pre-generation", null);
        def("pregen.chunkyWorkingCount", "auto",
                "How many chunks Chunky keeps generating at once. Chunky's own default of 50 holds this "
                        + "mod's pipeline back badly. auto = 192 per GB of maximum heap, between 256 and 3072. "
                        + "default = leave Chunky's setting alone. A number is used as given. "
                        + "-Dchunky.maxWorkingCount on the command line always wins. Measured with a 16 GB "
                        + "heap: 3072 was fastest, 768 about 6% slower, 6144 slower again.");

        section("player.fullSpeedLoading", "Fast chunk loading for moving players",
                "Server-side only: a slow client still receives, builds and acknowledges chunks at its own "
                        + "pace.");
        def("player.fullSpeedLoading", "false",
                "true = stop the server from throttling chunk sending to fast-moving players (see the three "
                        + "options below). false = vanilla behavior.");
        def("player.fullSpeedLoading.chunksPerTick", "64",
                "With fullSpeedLoading on: chunks sent to each player per tick, instead of the smaller rate "
                        + "a busy client asks for (1-512; vanilla never sends more than 64).");
        def("player.fullSpeedLoading.maxUnackedBatches", "10",
                "With fullSpeedLoading on: chunk batches that may be waiting for a client's acknowledgement "
                        + "(up to 1000). Vanilla's 10 is the minimum; lower values are ignored.");
        def("player.fullSpeedLoading.noTickVdMaxConcurrentLoads", "128",
                "With fullSpeedLoading on: minimum number of no-tick chunk loads that may run at once "
                        + "(1-65536). It raises c2me.noTickViewDistance.maxConcurrentChunkLoads and never "
                        + "lowers it.");

        section("player.sendAtChunkSending", "Chunk delivery to players", null);
        def("player.sendAtChunkSending", "true",
                "Send chunks to players as soon as they are ready to be sent, instead of waiting until they "
                        + "tick. Without it, when the view distance is larger than the simulation distance, the "
                        + "outer chunks never arrive. Recommended: true.");
        def("player.priorityBias", "false",
                "Do chunk work near players (within view distance + 8 chunks) before other chunk work. It "
                        + "only reorders existing work; nothing extra is generated.");
        def("player.latencyMetrics", "false",
                "Log a [player-latency] line every second with per-player chunk-delivery statistics. "
                        + "Diagnostics; shown only when logging.verbose=true.");

        section("player.predictiveGen", "Predictive generation ahead of fast-moving players",
                "Generates the chunks along the path a fast player is heading, instead of enlarging the "
                        + "whole loaded area. Affects real players only; no effect on pre-generation.");
        def("player.predictiveGen", "true",
                "Master switch. false = no effect at all.");
        def("player.predictiveGen.lookaheadSeconds", "4",
                "How far ahead to predict, in seconds of travel (0.5-30).");
        def("player.predictiveGen.maxPredictedChunks", "64",
                "Most chunks predicted per player at a time, nearest first (1-1024).");
        def("player.predictiveGen.minSpeedBps", "8",
                "Horizontal speed, in blocks per second, at which prediction starts (0.5-500).");
        def("player.predictiveGen.maxConcurrentPredictedLoads", "8",
                "Most predicted chunk loads in progress at once (1-256). Separate from the no-tick "
                        + "loader's own limit.");
        def("player.predictiveGen.corridorWidthChunks", "5",
                "Width of the predicted path, in chunks (1-15, and never wider than the view distance).");
        def("player.predictiveGen.recomputeIntervalTicks", "10",
                "How often the predicted path is recalculated, in ticks (1-100).");
        def("player.predictiveGen.hitWindowSeconds", "10",
                "For the metrics: a predicted chunk that enters the player's view within this many seconds "
                        + "counts as a hit (1-120).");
        def("player.predictiveGen.logMetrics", "false",
                "Log a [predictive-gen] line every second with prediction counts and the hit rate. "
                        + "Diagnostics; shown only when logging.verbose=true.");

        section("logging.verbose", "Logging", null);
        def("logging.verbose", "false",
                "false = SuperChunk writes only warnings and errors to the log. true = also its "
                        + "informational lines: startup summaries, feature status, and the metrics and "
                        + "verify diagnostics you enable. Turn on when reporting a problem.");
    }

    static {
        // A key that is BOTH force-disabled and defaulted true is a silent contradiction: the
        // default advertises a behaviour pinLithium then rewrites away on the path to
        // lithium.properties. That is exactly how chunk.no_locking's round-9 win stayed inert
        // behind a warning nobody read. Make it unrepresentable instead of merely warned about.
        for (String suffix : LITHIUM_FORCE_DISABLED) {
            String v = DEFAULTS.get(LITHIUM_PREFIX + suffix);
            if (v != null && !"false".equalsIgnoreCase(v)) {
                throw new IllegalStateException("SuperChunkConfig: lithium." + suffix
                        + " is in LITHIUM_FORCE_DISABLED but defaults to '" + v
                        + "' — the pin would silently override the default. Fix one of the two.");
            }
        }
    }

    private static void def(String k, String v, String description) {
        DEFAULTS.put(k, v);
        DESCRIPTIONS.put(k, description);
    }

    /** {@code intro} (optional) is printed under the section title, before its first option. */
    private static void section(String firstKey, String title, String intro) {
        SECTIONS.put(firstKey, title);
        if (intro != null) {
            SECTION_INTROS.put(firstKey, intro);
        }
    }

    private static volatile Properties unified;
    private static volatile boolean appliedEarly = false;

    private SuperChunkConfig() {
    }

    /** Loads (once) the unified config, creating + migrating it on first run. Never throws. */
    public static synchronized Properties get() {
        if (unified == null) {
            unified = loadOrCreate();
        }
        return unified;
    }

    private static Properties loadOrCreate() {
        Properties props = new Properties();
        // start from built-in defaults so a partial file still resolves every key
        DEFAULTS.forEach(props::setProperty);
        try {
            Path dir = FMLPaths.CONFIGDIR.get();
            Path file = dir.resolve(FILE_NAME);
            if (Files.exists(file)) {
                Properties onDisk = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    onDisk.load(in);
                }
                onDisk.forEach((k, v) -> props.setProperty(String.valueOf(k), String.valueOf(v)));
                LOGGER.debug("[SuperChunk-Config] Loaded unified config {}", file);
                upgradeDocumentation(file, props);
            } else {
                migrateLegacy(dir, props);
                if (writeDefaults(file, props)) {
                    LOGGER.info("[SuperChunk-Config] Created unified config {} with a description of every option.", file);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Config] Failed to load {} — using built-in defaults.", FILE_NAME, t);
        }
        return props;
    }

    /**
     * Folds legacy per-engine config files into {@code props} on first run so existing setups are
     * preserved: {@code superchunk-gpu.properties} -> {@code gpu.*}, {@code lithium.properties}
     * ({@code mixin.*}) -> {@code lithium.*}. C2ME/lighting keep their defaults (rarely tuned; the
     * user can set them in the unified file).
     */
    private static void migrateLegacy(Path dir, Properties props) {
        Properties gpu = readProps(dir.resolve("superchunk-gpu.properties"));
        if (gpu != null) {
            // The legacy boolean requireFp64 must be TRANSLATED into the three-way gpu.desiredFp
            // rather than copied to a dead gpu.requireFp64 key: desiredFp is always seeded from
            // DEFAULTS, so GpuConfig never falls back to requireFp64 — a copied requireFp64=false
            // would be silently ignored and the user upgraded to fp64. Translate true -> "fp64",
            // false -> "auto" (accept fp32 fallback). Skip the translation when desiredFp is ALSO
            // set in the legacy file (an explicit desiredFp wins; never override it).
            boolean legacyHasDesiredFp = gpu.getProperty("desiredFp") != null;
            int migrated = 0;
            for (Map.Entry<Object, Object> e : gpu.entrySet()) {
                String key = String.valueOf(e.getKey());
                String val = String.valueOf(e.getValue());
                if (key.equals("requireFp64")) {
                    if (legacyHasDesiredFp) {
                        LOGGER.info("[SuperChunk-Config] Legacy requireFp64 present but desiredFp is also set — "
                                + "keeping desiredFp and dropping the dead requireFp64 key.");
                    } else {
                        boolean require = Boolean.parseBoolean(val.trim());
                        String desired = require ? "fp64" : "auto";
                        props.setProperty(GPU_PREFIX + "desiredFp", desired);
                        LOGGER.info("[SuperChunk-Config] Translated legacy requireFp64={} -> gpu.desiredFp={}.",
                                val.trim(), desired);
                    }
                    continue;   // never write the dead gpu.requireFp64 key
                }
                props.setProperty(GPU_PREFIX + key, val);
                migrated++;
            }
            LOGGER.info("[SuperChunk-Config] Migrated {} legacy GPU keys from superchunk-gpu.properties.", migrated);
        }
        Properties lith = readProps(dir.resolve("lithium.properties"));
        if (lith != null) {
            int n = 0;
            for (Map.Entry<Object, Object> e : lith.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.startsWith("mixin.")) {
                    String suffix = key.substring("mixin.".length());
                    props.setProperty(LITHIUM_PREFIX + suffix,
                            pinLithium(suffix, String.valueOf(e.getValue()), "legacy migration"));
                    n++;
                }
            }
            if (n > 0) {
                LOGGER.info("[SuperChunk-Config] Migrated {} legacy Lithium keys from lithium.properties.", n);
            }
        }
    }

    private static Properties readProps(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Marks a file written in the current documented layout; files without it are regenerated once.
     * Bump it when options are added, so existing files show them. 2: per-option descriptions.
     * 3: {@code logging.verbose}.
     */
    private static final String FORMAT_MARKER = "# superchunk-config-format: 3";
    private static final int WRAP = 96;

    /**
     * A file written in an older layout (before options carried descriptions, or before the newest
     * options existed) is regenerated once in the documented layout. Every value already present is
     * kept (including keys SuperChunk does not know), so behavior is unchanged; the previous file is
     * saved as {@code superchunk.properties.bak}.
     */
    private static void upgradeDocumentation(Path file, Properties values) {
        try {
            if (Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1).contains(FORMAT_MARKER)) {
                return;
            }
            // A symlinked or read-only file is managed deliberately: leave it exactly as it is.
            if (Files.isSymbolicLink(file) || !Files.isWritable(file)) {
                return;
            }
            Path backup = file.resolveSibling(FILE_NAME + ".bak");
            Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (writeDefaults(file, values)) {
                LOGGER.info("[SuperChunk-Config] Updated {} to the current layout and option descriptions (values unchanged; previous file saved as {}).",
                        file, backup.getFileName());
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Config] Could not add option descriptions to {} — leaving it as is.", file, t);
        }
    }

    /** The documented file for {@code values}: every known option with its description, then any others. */
    static String render(Properties values) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SuperChunk configuration (single source of truth).\n");
        sb.append("#\n");
        sb.append("# SuperChunk passes these settings on to the engines it contains:\n");
        sb.append("#   gpu.*      -> the OpenCL GPU worldgen offload\n");
        sb.append("#   c2me.*     -> C2ME, as -Dc2me.base.config.override.<key> (wins over c2me.toml)\n");
        sb.append("#   lithium.*  -> config/lithium.properties, as mixin.<key>\n");
        sb.append("#   lighting.* -> config/scalablelux.properties\n");
        sb.append("#   pregen.*   -> Chunky, as the -Dchunky.maxWorkingCount system property\n");
        sb.append("#   player.*   -> read by SuperChunk on the server\n");
        sb.append("#   client.*   -> read by SuperChunk on the client\n");
        sb.append("#   logging.*  -> SuperChunk's own log output\n");
        sb.append("# Edit this file, not the per-engine files: those are rewritten from it at startup.\n");
        sb.append("# Changes take effect after a restart. Use the values each description lists.\n");
        sb.append(FORMAT_MARKER).append('\n');
        for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {
            String key = e.getKey();
            String title = SECTIONS.get(key);
            if (title != null) {
                sb.append("\n# ===== ").append(title).append(" =====\n");
                String intro = SECTION_INTROS.get(key);
                if (intro != null) {
                    appendComment(sb, intro);
                }
            }
            sb.append('\n');
            String description = DESCRIPTIONS.get(key);
            if (description != null) {
                appendComment(sb, description);
            }
            sb.append("# Default: ").append(e.getValue())
                    .append(DEFAULT_PLACEHOLDER.equals(e.getValue()) ? " (C2ME's own default applies)" : "").append('\n');
            sb.append(escape(key, true)).append('=').append(escape(values.getProperty(key, e.getValue()), false)).append('\n');
        }
        // Persist any keys folded in by migrateLegacy (or otherwise present) that are NOT part of
        // DEFAULTS — e.g. migrated lithium.*/gpu.* customizations — so they survive to the next run.
        // Sorted for deterministic output.
        java.util.List<String> extras = new java.util.ArrayList<>();
        for (String name : values.stringPropertyNames()) {
            if (!DEFAULTS.containsKey(name)) {
                extras.add(name);
            }
        }
        if (!extras.isEmpty()) {
            java.util.Collections.sort(extras);
            sb.append("\n# ===== Other settings =====\n");
            appendComment(sb, "Options SuperChunk has no built-in description for, such as extra c2me.* or "
                    + "lithium.* rules and migrated settings. They are passed on unchanged.");
            for (String name : extras) {
                sb.append(escape(name, true)).append('=').append(escape(values.getProperty(name), false)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Writes the documented file atomically; false (after logging) if it could not be written. */
    private static boolean writeDefaults(Path file, Properties values) {
        try {
            Files.createDirectories(file.getParent());
            String text = render(values);
            // Atomic write: superchunk.properties is the source of truth and is written only on
            // first run and once to add descriptions (upgradeDocumentation). A crash mid-write would leave a
            // partial file that loadOrCreate() silently overlays on defaults and never
            // repairs (it now "exists"). Write to a sibling temp file then atomically move
            // it into place so the destination is either the old (absent) or the complete
            // file — never a truncated one.
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicFailed) {
                // Some filesystems don't support ATOMIC_MOVE — fall back to a plain replace.
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Config] Could not write {}.", file, t);
            try {
                Files.deleteIfExists(file.resolveSibling(FILE_NAME + ".tmp"));
            } catch (Throwable ignored) {
                // best effort: a stale temp file is harmless
            }
            return false;
        }
    }

    /**
     * Pushes the C2ME + Lithium + lighting settings to where each engine's loader will read them,
     * BEFORE those loaders run. Idempotent; called from {@code SuperChunkMixinPlugin.onLoad()}.
     * Never throws.
     */
    public static synchronized void applyEarly() {
        if (appliedEarly) {
            return;
        }
        appliedEarly = true;
        Properties p = get();
        dev.superchunk.diag.LogQuieter.install(); // first, so the lines below obey logging.verbose
        try {
            applyC2me(p);
            applyChunky(p);
            writeLithium(p);
            writeLighting(p);
            // Mod-compat C2ME overrides layered AFTER applyC2me so an explicit user value wins.
            // Currently: disable the relocated fluid post-processing filter when Bye?Pregen! is present.
            dev.superchunk.compat.ByePregenCompat.applyEarlyOverrides();
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Config] applyEarly write-through failed (engines fall back to their own defaults).", t);
        }
    }

    /**
     * c2me.<key>=<val> -> System property c2me.base.config.override.<key>.
     *
     * <p>'default' is pushed through as C2ME's own placeholder (ConfigSystem treats an
     * override string "default" as isDefaultValue=true), NOT skipped: skipping it let a
     * stale value in a pre-existing c2me.toml silently win while the unified file — the
     * documented source of truth — showed 'default'. Keys absent from the unified file
     * remain toml-governed as before.
     */
    /**
     * {@code pregen.chunkyWorkingCount} &rarr; the {@code chunky.maxWorkingCount} system property.
     *
     * <p>Chunky's {@code GenerationTask} reads that property in a static initialiser and uses it as
     * the permit count of the semaphore its generation loop acquires per chunk — so it is a hard
     * cap on in-flight chunks, and therefore on throughput (in-flight = throughput &times;
     * per-chunk latency). Its default is <b>50</b>, which is sized for vanilla's synchronous chunk
     * pipeline, not for the much deeper async one this mod runs; every benchmark in the README was
     * taken with the property raised by hand.
     *
     * <p>Three rules make this safe to set on a user's behalf: an explicit {@code -D} always wins
     * (same contract as {@link #applyC2me}), {@code default} leaves Chunky entirely alone, and the
     * {@code auto} default scales with the heap rather than hard-coding the value that happened to
     * win on a 16 GB benchmark box — every in-flight chunk is retained, so a number sized for 16 GB
     * is a way to run a 4 GB server out of memory. The class is only touched if Chunky is installed
     * at all; writing a system property no one reads is inert.
     */
    private static void applyChunky(Properties p) {
        String val = p.getProperty("pregen.chunkyWorkingCount");
        if (val == null || val.isBlank() || val.equalsIgnoreCase(DEFAULT_PLACEHOLDER)) {
            return;
        }
        final String prop = "chunky.maxWorkingCount";
        if (System.getProperty(prop) != null) {
            LOGGER.info("[SuperChunk-Config] {} already set on the command line — leaving it alone.", prop);
            return;
        }
        int n;
        String how;
        if ("auto".equalsIgnoreCase(val.trim())) {
            double heapGb = Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0 * 1024.0);
            n = (int) Math.max(256, Math.min(3072, heapGb * 192.0));
            how = String.format("auto from a %.1f GB max heap", heapGb);
        } else {
            try {
                n = Integer.parseInt(val.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("[SuperChunk-Config] pregen.chunkyWorkingCount='{}' is not a number, "
                        + "'auto' or 'default' — ignored.", val);
                return;
            }
            if (n <= 0) {
                return;
            }
            how = "configured";
        }
        System.setProperty(prop, Integer.toString(n));
        LOGGER.info("[SuperChunk-Config] Chunky in-flight chunk limit -> {} ({}, -D{}). Chunky's own "
                + "default is 50, which caps pregen throughput far below this pipeline's capability.",
                n, how, prop);
    }

    private static void applyC2me(Properties p) {
        int n = 0;
        for (String name : p.stringPropertyNames()) {
            if (!name.startsWith(C2ME_PREFIX)) {
                continue;
            }
            String val = p.getProperty(name);
            if (val == null || val.isBlank()) {
                continue;
            }
            // Properties keeps trailing spaces, and C2ME parses the value strictly: "true " or "8 "
            // would be rejected with a WARN and silently replaced by C2ME's own default.
            val = val.trim();
            if (val.equalsIgnoreCase(DEFAULT_PLACEHOLDER)) {
                val = DEFAULT_PLACEHOLDER; // normalize: ConfigSystem matches "default" exactly
            }
            String key = name.substring(C2ME_PREFIX.length());
            String prop = C2ME_OVERRIDE_PROP + key;
            // do not clobber an explicit -D the user passed on the command line
            if (System.getProperty(prop) == null) {
                System.setProperty(prop, val);
                n++;
            }
        }
        if (n > 0) {
            LOGGER.info("[SuperChunk-Config] Applied {} C2ME override(s) via system properties.", n);
        }
    }

    /**
     * Returns the value to use for a Lithium rule whose {@code suffix} is its key without the
     * {@code lithium.} prefix, HARD-PINNING the C2ME-overlap rules
     * ({@link #LITHIUM_FORCE_DISABLED}) to false and logging a warning when an incoming
     * {@code true} is overridden. Other rules pass through unchanged. {@code origin} names the
     * flow for the log (e.g. "write-through", "legacy migration").
     */
    private static String pinLithium(String suffix, String val, String origin) {
        if (!LITHIUM_FORCE_DISABLED.contains(suffix)) {
            return val;
        }
        if (val != null && val.trim().equalsIgnoreCase("true")) {
            LOGGER.warn("[SuperChunk-Config] Lithium rule '{}{}'=true OVERLAPS C2ME (double-application "
                    + "corrupts/crashes) — hard-pinning it to false ({}).", LITHIUM_PREFIX, suffix, origin);
        }
        return "false";
    }

    /** lithium.<key>=<val> -> config/lithium.properties as mixin.<key>. Owns the file. */
    private static void writeLithium(Properties p) {
        Properties out = new Properties();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith(LITHIUM_PREFIX)) {
                String suffix = name.substring(LITHIUM_PREFIX.length());
                out.setProperty("mixin." + suffix, pinLithium(suffix, p.getProperty(name), "write-through"));
            }
        }
        if (skeinInstalled()) {
            // Applied before either bundled or standalone Lithium selects its mixins.
            // Changing Skein's phase settings later must not reactivate these caches.
            for (String suffix : SKEIN_LITHIUM_DISABLED) out.setProperty("mixin." + suffix, "false");
            LOGGER.info("[SuperChunk-Config] Skein detected: disabled shared Lithium tick caches: {}",
                    String.join(", ", SKEIN_LITHIUM_DISABLED));
        }
        if (out.isEmpty()) {
            return;
        }
        Path file = FMLPaths.CONFIGDIR.get().resolve("lithium.properties");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream o = Files.newOutputStream(file)) {
                out.store(o, "Generated by SuperChunk from config/superchunk.properties (lithium.*). Edit superchunk.properties instead.");
            }
            LOGGER.info("[SuperChunk-Config] Wrote {} Lithium rule(s) to {} from the unified config.", out.size(), file);
            // Make the C2ME-overlap safety pins auditable in the boot log. These are ALWAYS
            // forced false (see pinLithium) and apply to whichever Lithium reads this file — the
            // bundled copy OR a standalone Lithium — which is the whole point of writing it early.
            LOGGER.info("[SuperChunk-Config] C2ME-overlap safety pins forced false in {}: mixin.{}",
                    file.getFileName(), String.join(", mixin.", LITHIUM_FORCE_DISABLED));
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Config] Could not write lithium.properties.", t);
        }
    }

    /** lighting.parallelism -> scalablelux.properties (inert today; written for completeness). */
    private static void writeLighting(Properties p) {
        String par = p.getProperty("lighting.parallelism");
        if (par == null || par.isBlank()) {
            return;
        }
        Path file = FMLPaths.CONFIGDIR.get().resolve("scalablelux.properties");
        try {
            Properties out = new Properties();
            out.setProperty("parallelism", par.trim());
            Files.createDirectories(file.getParent());
            try (OutputStream o = Files.newOutputStream(file)) {
                out.store(o, "Generated by SuperChunk from config/superchunk.properties (lighting.*).");
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Config] Could not write scalablelux.properties.", t);
        }
    }

    /** Appends {@code text} as "# " comment lines wrapped at {@link #WRAP} columns. */
    private static void appendComment(StringBuilder sb, String text) {
        StringBuilder line = new StringBuilder("#");
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() > 1 && line.length() + 1 + word.length() > WRAP) {
                sb.append(line).append('\n');
                line.setLength(0);
                line.append('#');
            }
            line.append(' ').append(word);
        }
        if (line.length() > 1) {
            sb.append(line).append('\n');
        }
    }

    /**
     * Escapes a key or value for {@link Properties#load(InputStream)}, which reads ISO-8859-1:
     * backslashes, line breaks, leading spaces, key separators and non-Latin-1 characters.
     */
    static String escape(String text, boolean key) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                case ' ' -> out.append(key || i == 0 ? "\\ " : " ");
                case '=', ':', '#', '!' -> out.append(key || i == 0 ? "\\" + c : String.valueOf(c));
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * The GPU section as a {@link Properties} with the {@code gpu.} prefix stripped, so
     * {@code GpuConfig} can read its existing un-prefixed keys ({@code enabled}, {@code desiredFp},
     * {@code selftest.gpu_parity}, ...) straight from the unified file.
     */
    public static Properties gpuProperties() {
        Properties src = get();
        Properties out = new Properties();
        for (String name : src.stringPropertyNames()) {
            if (name.startsWith(GPU_PREFIX)) {
                out.setProperty(name.substring(GPU_PREFIX.length()), src.getProperty(name));
            }
        }
        return out;
    }
}
