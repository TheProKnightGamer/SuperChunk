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
     * The five Lithium rule keys (the part AFTER the {@code lithium.} prefix) that overlap
     * C2ME's chunk-system / serializer rewrites and are documented to crash/corrupt on
     * double-application. They are HARD-PINNED to false everywhere they flow (legacy migration
     * + write-through to {@code lithium.properties}), overriding any user/migrated {@code true}
     * by design — a safety pin, not a preference. See {@link #pinLithium}.
     */
    private static final java.util.Set<String> LITHIUM_FORCE_DISABLED = java.util.Set.of(
            "gen.cached_generator_settings",
            "chunk.serialization",
            "chunk.no_locking",
            "world.tick_scheduler",
            "world.chunk_access");

    /** Ordered key -> default value. Order drives the written file layout (see {@link #SECTIONS}). */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    /** key -> section header emitted just before it when writing the default file. */
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();

    static {
        section("gpu.enabled", "GPU / OpenCL worldgen offload (master switch + precision + features)");
        def("gpu.enabled", "false");
        def("gpu.platformIndex", "-1");
        def("gpu.deviceIndex", "-1");
        def("gpu.desiredFp", "fp64");            // auto | fp64 | fp32
        def("gpu.coalesce", "true");
        def("gpu.fuseInterpolated", "true");
        def("gpu.asyncReadback", "true");
        // false (2026-07-16, round 8): with the compact-ids decide chain default-on the GPU
        // is the scarce resource — biome-on measured -22% at 24 workers (805.5 vs 1032 cps).
        // Biomes are bit-identical either way (the CPU path IS vanilla).
        def("gpu.offloadBiome", "false");
        // true (2026-07-16, round 8): the batcher pipeline is the validated fast path
        // (rounds 6-8 smoke/parity/census-gated) and the compact-ids chain requires it.
        def("gpu.batchChunks", "true");
        def("gpu.batchLimit", "32");
        def("gpu.batchWindowMicros", "500");
        // In-flight batched dispatches (slot ring in GpuBatchDispatcher). 2 = the measured
        // sweet spot: restores the overlap the chained climate+density batches need (free
        // climate offload) at zero cost to biome-off; 1 serializes the chain (~8% loss
        // when biome is on); 4 shrinks batches (~5% loss everywhere on a strong CPU).
        def("gpu.batchPipelineDepth", "2");
        def("gpu.mergeKernels", "false");
        // false (2026-07-07): structure-probe column samplers take the CPU bytecode path —
        // the GPU probe path was ~11.4k worker-side parked dispatches per r2048 pregen;
        // CPU routing measured +18% cps at 21 workers. Parity-free (GPU is FP64 bit-exact).
        def("gpu.subLatticeGpu", "false");
        // fp32 decision math for the compact-ids DECIDE kernel (corner buffer + aq_decide
        // comparisons stay fp64). Auto-falls-back to the fp64 program if the fp32 build
        // fails. The compact-ids chain itself is DEFAULT ON since round 8
        // (-Dsuperchunk.gpu.compactIds=off restores strict mode); its output envelope is
        // census-measured — zero deviations across every census run (455.9M + 4x460M blocks).
        def("gpu.decideFp32", "true");
        // FMA contraction for the fp32 decide program. Census-zero but measured
        // throughput-NEUTRAL on GA104 (the kernel is latency-bound) — experiment flag.
        def("gpu.decideFmaContract", "false");
        // fp32 climate kernel experiment. MEASURED-REFUTED on vanilla noise (domain-warp
        // amplifies fp32 drift to ~0.47 quantized units; the boot gate auto-refuses and
        // falls back to fp64 — zero behavior change). Kept for un-warped datapack noise.
        def("gpu.climateFp32", "false");
        def("gpu.latticeCoords", "true");
        def("gpu.mappedBuffers", "true");
        def("gpu.profile", "false");
        def("gpu.selftest.noise", "false");
        def("gpu.selftest.dfc", "false");
        def("gpu.selftest.gpu_parity", "false");

        section("c2me.globalExecutorParallelism",
                "C2ME multithreaded chunk gen. Applied as -Dc2me.base.config.override.<key>.\n"
                        + "# 'default' = C2ME auto-sizes min(cpu/1.3, heap-based); set e.g. 21 to use more cores\n"
                        + "# (watch heap/GC). Any c2me.<key> here maps to that C2ME config key.\n"
                        + "# The keys below are the useful subset seeded for discoverability ('default' = C2ME's\n"
                        + "# own default governs):\n"
                        + "#   noTickViewDistance.enabled (true)         - no-tick chunk ring beyond simulation distance\n"
                        + "#   noTickViewDistance.maxConcurrentChunkLoads (workers+1) - no-tick ring load rate\n"
                        + "#   noTickViewDistance.enableExtRenderDistanceProtocol (true) - >127-chunk client requests\n"
                        + "#   generalOptimizations.autoSave.mode (ENHANCED) - ENHANCED|PERIODIC|VANILLA autosave\n"
                        + "#   generalOptimizations.midTickChunkTasksInterval (100) - us between mid-tick chunk tasks\n"
                        + "#   ioSystem.gcFreeChunkSerializer (false)    - EXPERIMENTAL; corruption-risk, needs a\n"
                        + "#                                               round-trip parity check before enabling\n"
                        + "#   chunkSystem.recoverFromErrors (false)     - true = salvage worldgen exceptions instead of\n"
                        + "#                                               crashing (good for play; keep false for parity R&D)\n"
                        + "#   chunkSystem.lowMemoryMode (false)         - trade throughput for heap headroom\n"
                        + "#   chunkSystem.suppressGhostMushrooms        - SuperChunk defaults this TRUE: with\n"
                        + "#                                               player.sendAtChunkSending on, the outer no-tick\n"
                        + "#                                               ring reaches the client before post-processing,\n"
                        + "#                                               which would briefly show MC-276863 ghost\n"
                        + "#                                               mushrooms; this suppresses them (no other\n"
                        + "#                                               worldgen effect). Set false to restore.");
        def("c2me.globalExecutorParallelism", DEFAULT_PLACEHOLDER);
        def("c2me.noTickViewDistance.enabled", DEFAULT_PLACEHOLDER);
        def("c2me.noTickViewDistance.maxConcurrentChunkLoads", DEFAULT_PLACEHOLDER);
        def("c2me.noTickViewDistance.enableExtRenderDistanceProtocol", DEFAULT_PLACEHOLDER);
        def("c2me.generalOptimizations.autoSave.mode", DEFAULT_PLACEHOLDER);
        def("c2me.generalOptimizations.midTickChunkTasksInterval", DEFAULT_PLACEHOLDER);
        def("c2me.ioSystem.gcFreeChunkSerializer", DEFAULT_PLACEHOLDER);
        def("c2me.chunkSystem.recoverFromErrors", DEFAULT_PLACEHOLDER);
        def("c2me.chunkSystem.lowMemoryMode", DEFAULT_PLACEHOLDER);
        // TRUE by default (companion to player.sendAtChunkSending, below): sending the no-tick ring
        // at CHUNK_SENDING exposes non-post-processed chunks, so mushrooms could briefly appear
        // (MC-276863). This C2ME workaround suppresses them with no other worldgen effect.
        def("c2me.chunkSystem.suppressGhostMushrooms", "true");

        section("client.maxRenderDistance",
                "Extended render distance (client). Raises the vanilla render-distance slider cap (32)\n"
                        + "# up to this value; terrain past simulation distance is served by C2ME's no-tick view\n"
                        + "# distance (works on the integrated server too). >127 additionally needs the\n"
                        + "# c2me ext_render_distance channel (vanilla's own packet caps at 127) - sent\n"
                        + "# automatically on login. This is the slider CAP, not a forced value: raising it to\n"
                        + "# 64 lets the client select up to 64 (the server still clamps to what its own no-tick\n"
                        + "# view distance allows), and a linear 2..64 slider puts the vanilla-32 point at the\n"
                        + "# midpoint (the bar 'reads' half-scale). 32 = vanilla, exact no-op. Memory note: client\n"
                        + "# chunk storage grows O(r^2) - a client that actually picks 64 uses ~4x the 32 footprint.");
        def("client.maxRenderDistance", "64");

        section("lithium.gen.cached_generator_settings",
                "Lithium game-logic opts. These five overlap C2ME's chunk-system/serializer rewrites\n"
                        + "# and MUST stay disabled (double-application corrupts/crashes). Written to\n"
                        + "# config/lithium.properties as mixin.<key>. Other lithium.<key>=true/false also pass through.");
        def("lithium.gen.cached_generator_settings", "false");
        def("lithium.chunk.serialization", "false");
        // TRUE since round 9 (2026-07-16): removes vanilla's ThreadingDetector
        // ReentrantLock on EVERY PalettedContainer get/set — measured 4.8% of worker
        // CPU at 24 workers (JFR), +2.3% cps interleaved A/B. Detection-only removal
        // (Lithium-standard, shipped to production for years); no mixin conflict with
        // the C2ME chunk system (verified: nothing else touches acquire/release), and
        // zero ThreadingDetector trips ever observed across all pregen legs.
        def("lithium.chunk.no_locking", "true");
        def("lithium.world.tick_scheduler", "false");
        def("lithium.world.chunk_access", "false");

        section("lighting.parallelism",
                "ScalableLux lighting. Note: lighting is externally managed by C2ME, so this is\n"
                        + "# effectively inert today (-1 = auto). Kept for completeness.");
        def("lighting.parallelism", "-1");

        section("player.fullSpeedLoading",
                "Full-throttle chunk loading (\"no radius shrink\"). false = vanilla behavior.\n"
                        + "# When true, SuperChunk lifts the SERVER-side self-throttles that make the effective\n"
                        + "# loaded ring shrink around a fast-moving player:\n"
                        + "#   - vanilla PlayerChunkSender: the per-tick chunk-send budget is pinned to\n"
                        + "#     chunksPerTick (vanilla max 64) instead of shrinking to the client-reported\n"
                        + "#     desired batch size on slow acks; the post-drain quota slow-start is neutralized;\n"
                        + "#     maxUnackedBatches widens the in-flight batch window (vanilla 10; raise-only).\n"
                        + "#   - C2ME notickvd: the concurrent chunk-load cap is raised to at least\n"
                        + "#     noTickVdMaxConcurrentLoads (C2ME default: executor parallelism + 1), so the\n"
                        + "#     no-tick ring refills at generation speed instead of a handful at a time.\n"
                        + "# HONESTY NOTE: these are server-side lifts only. A slow client still decodes,\n"
                        + "# renders and acks chunk batches at its own pace; the server merely stops\n"
                        + "# throttling itself below what the pipeline can produce.");
        def("player.fullSpeedLoading", "false");
        def("player.fullSpeedLoading.chunksPerTick", "64");
        def("player.fullSpeedLoading.maxUnackedBatches", "10");
        def("player.fullSpeedLoading.noTickVdMaxConcurrentLoads", "128");

        section("player.sendAtChunkSending",
                "Player chunk-arrival fixes + proof metrics. sendAtChunkSending defaults TRUE (the port-defect\n"
                        + "# fix below); priorityBias/latencyMetrics default false.\n"
                        + "# sendAtChunkSending: 1.21.1 port-defect fix — the C2ME chunk holder never overrode vanilla\n"
                        + "#   ChunkHolder.getChunkToSend(), so chunks only became client-sendable at BLOCK_TICKING\n"
                        + "#   (and with view distance > simulation distance the outer no-tick rings NEVER sent).\n"
                        + "#   When true, chunks are exposed to PlayerChunkSender at the earlier\n"
                        + "#   SERVER_ACCESSIBLE_CHUNK_SENDING status; vanilla sendSync (light-send deps) still gates.\n"
                        + "# priorityBias: chunk-system tasks within (view distance + 8) chebyshev of a player run in\n"
                        + "#   priority band 18..32 (control-plane 15 / saves 16 / light 17 stay ahead), live-updated on\n"
                        + "#   player movement. Pure reordering of the existing work queue — no budget, no new work.\n"
                        + "# latencyMetrics: 1s [player-latency] log lines — per-player collect-miss counter, work-queue\n"
                        + "#   depth buckets {<=17, 18-32, 33, 34+}, send-latency p50/p99 (now - CHUNK_SENDING completion).");
        def("player.sendAtChunkSending", "true");
        def("player.priorityBias", "false");
        def("player.latencyMetrics", "false");

        section("player.predictiveGen",
                "Predictive chunk generation along a fast player's projected path (default true; set false to disable).\n"
                        + "# When true, a per-player velocity tracker (EMA of the per-tick horizontal position\n"
                        + "# delta, measured server-side) activates at >= minSpeedBps blocks/second and projects\n"
                        + "# the position lookaheadSeconds ahead; the chunk corridor from here to there — width\n"
                        + "# min(view distance, corridorWidthChunks), <= maxPredictedChunks per player,\n"
                        + "# nearest-first — is pre-generated/loaded through the EXISTING C2ME notickvd pipeline\n"
                        + "# (same SERVER_ACCESSIBLE_CHUNK_SENDING target + vanilla level-33 ticket as the no-tick\n"
                        + "# loader, own ticket type) instead of raising the whole view radius. Predicted loads run\n"
                        + "# under their own small maxConcurrentPredictedLoads budget so the no-tick loader's\n"
                        + "# maxConcurrentChunkLoads accounting is untouched; with player.priorityBias=true the\n"
                        + "# predicted positions also join the P2 priority band. Corridors recompute every\n"
                        + "# recomputeIntervalTicks; tickets retract when the player turns, slows below the\n"
                        + "# threshold, disconnects or changes dimension. logMetrics=true emits a 1s\n"
                        + "# [predictive-gen] line with ticket counters and the hit rate (predicted chunk entered\n"
                        + "# the player's real view within hitWindowSeconds = HIT; misses = wasted generation).\n"
                        + "# On by default; set false for zero effect. Zero effect on pregen either way (real players only).");
        def("player.predictiveGen", "true");
        def("player.predictiveGen.lookaheadSeconds", "4");
        def("player.predictiveGen.maxPredictedChunks", "64");
        def("player.predictiveGen.minSpeedBps", "8");
        def("player.predictiveGen.maxConcurrentPredictedLoads", "8");
        def("player.predictiveGen.corridorWidthChunks", "5");
        def("player.predictiveGen.recomputeIntervalTicks", "10");
        def("player.predictiveGen.hitWindowSeconds", "10");
        def("player.predictiveGen.logMetrics", "true");
    }

    private static void def(String k, String v) {
        DEFAULTS.put(k, v);
    }

    private static void section(String firstKey, String header) {
        SECTIONS.put(firstKey, header);
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
                LOGGER.info("[SuperChunk-Config] Loaded unified config {}", file);
            } else {
                migrateLegacy(dir, props);
                writeDefaults(file, props);
                LOGGER.info("[SuperChunk-Config] Created unified config {} (one file for GPU + C2ME + Lithium + lighting).", file);
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

    private static void writeDefaults(Path file, Properties values) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# SuperChunk — unified configuration (single source of truth).\n");
            sb.append("# SuperChunk applies these to the underlying engines at load:\n");
            sb.append("#   gpu.*      -> the OpenCL worldgen offload (GpuConfig)\n");
            sb.append("#   c2me.*     -> -Dc2me.base.config.override.<key> (overrides c2me.toml)\n");
            sb.append("#   lithium.*  -> config/lithium.properties as mixin.<key>\n");
            sb.append("#   lighting.* -> scalablelux.properties\n");
            sb.append("#   player.*   -> read directly by SuperChunk at runtime (no write-through)\n");
            sb.append("#   client.*   -> read directly by SuperChunk on the client (no write-through)\n");
            sb.append("# Edit THIS file; the per-engine files are written from it and may be overwritten.\n");
            for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {
                String header = SECTIONS.get(e.getKey());
                if (header != null) {
                    sb.append("\n# ===== ").append(header).append(" =====\n");
                }
                sb.append(e.getKey()).append('=').append(values.getProperty(e.getKey(), e.getValue())).append('\n');
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
                sb.append("\n# ===== migrated / custom =====\n");
                for (String name : extras) {
                    sb.append(name).append('=').append(values.getProperty(name)).append('\n');
                }
            }
            // Atomic write: superchunk.properties is the source of truth and is written
            // ONLY on first run (when absent). A crash mid-write would otherwise leave a
            // partial file that loadOrCreate() silently overlays on defaults and never
            // repairs (it now "exists"). Write to a sibling temp file then atomically move
            // it into place so the destination is either the old (absent) or the complete
            // file — never a truncated one.
            byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicFailed) {
                // Some filesystems don't support ATOMIC_MOVE — fall back to a plain replace.
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Config] Could not write default unified config.", t);
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
        try {
            applyC2me(p);
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
     * {@code lithium.} prefix, HARD-PINNING the five C2ME-overlap rules
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
            // Make the C2ME-overlap safety pins auditable in the boot log. These five are ALWAYS
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
