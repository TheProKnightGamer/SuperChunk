package dev.superchunk.gpu;

import dev.superchunk.config.SuperChunkConfig;
import org.slf4j.Logger;

import java.util.Properties;

/**
 * GPU/OpenCL configuration for SuperChunk — the {@code gpu.*} section of the unified
 * {@code config/superchunk.properties}, surfaced through {@link SuperChunkConfig#gpuProperties()}
 * with the {@code gpu.} prefix stripped. The backend runs very early so this stays a tiny
 * {@link Properties} reader rather than NeoForge's {@code ModConfigSpec} config-screen machinery.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code enabled} (default {@code false}) — master switch. When false the
 *       backend logs one line and does nothing.</li>
 *   <li>{@code platformIndex} (default {@code -1} = auto) — pin a specific
 *       OpenCL platform by enumeration index.</li>
 *   <li>{@code deviceIndex} (default {@code -1} = auto) — pin a specific device
 *       within the chosen platform by enumeration index.</li>
 *   <li>{@code desiredFp} (default {@code fp64}) — three-way precision mode
 *       ({@link FpMode}): {@code auto} (double when the device supports it, else
 *       fp32), {@code fp64} (require double; refuse non-fp64 devices — the
 *       vanilla-accurate default), or {@code fp32} (force the float path even on an
 *       fp64 device, for perf/integration testing). The old boolean {@code requireFp64}
 *       key is translated into {@code desiredFp} at config migration
 *       ({@code SuperChunkConfig.migrateLegacy}: {@code true}->{@code fp64},
 *       {@code false}->{@code auto}), so old config files keep working.</li>
 * </ul>
 */
public final class GpuConfig {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final String K_ENABLED = "enabled";
    private static final String K_PLATFORM = "platformIndex";
    private static final String K_DEVICE = "deviceIndex";
    private static final String K_DESIRED_FP = "desiredFp";
    private static final String K_SELFTEST_NOISE = "selftest.noise";
    private static final String K_SELFTEST_DFC = "selftest.dfc";
    private static final String K_SELFTEST_GPU_PARITY = "selftest.gpu_parity";
    private static final String K_PROFILE = "profile";
    private static final String K_LATTICE = "latticeCoords";
    private static final String K_MAPPED = "mappedBuffers";
    private static final String K_COALESCE = "coalesce";
    private static final String K_FUSE_INTERPOLATED = "fuseInterpolated";
    private static final String K_ASYNC_READBACK = "asyncReadback";
    private static final String K_OFFLOAD_BIOME = "offloadBiome";
    private static final String K_BATCH_CHUNKS = "batchChunks";
    private static final String K_BATCH_LIMIT = "batchLimit";
    private static final String K_BATCH_WINDOW_MICROS = "batchWindowMicros";
    private static final String K_BATCH_PIPELINE_DEPTH = "batchPipelineDepth";
    private static final String K_MERGE_KERNELS = "mergeKernels";
    private static final String K_SUB_LATTICE_GPU = "subLatticeGpu";
    private static final String K_DECIDE_FP32 = "decideFp32";
    private static final String K_DECIDE_FMA_CONTRACT = "decideFmaContract";
    private static final String K_CLIMATE_FP32 = "climateFp32";

    // Defaults
    private boolean enabled = false;
    private int platformIndex = -1;
    private int deviceIndex = -1;
    private FpMode fpMode = FpMode.FP64;
    private boolean selftestNoise = false;
    private boolean selftestDfc = false;
    private boolean selftestGpuParity = false;
    private boolean profile = false;
    private boolean latticeCoords = true;
    private boolean mappedBuffers = true;
    private boolean coalesce = true;
    private boolean fuseInterpolated = true;
    private boolean asyncReadback = true;
    // DEFAULT FALSE since round 8: with the compact-ids decide chain default-on the GPU is
    // the scarce resource at high worker counts, and the climate chain's extra NDRange +
    // readback per batch costs throughput outright (round-8 A/B at 24w: biome-on 805.5 vs
    // biome-off 1032 cps = -22%). Biomes stay bit-identical either way (the CPU path IS
    // vanilla; the chain was proven bit-identical too — 7.0M live samples, 0 mismatches);
    // true remains available for thermal/live-play experiments on GPU-idle setups.
    private boolean offloadBiome = false;
    // LIVE cross-chunk batched dispatch. DEFAULT TRUE since round 8: the batcher pipeline
    // (per-slot queues, pinned staging, chained decide kernel) is the validated fast path —
    // smoke/parity/census-gated across rounds 6-8 — and the compact-ids chain requires it.
    // false = byte-for-byte the per-chunk path. Wired via SuperChunk.initGpuBackend ->
    // ChunkGridCache.setBatchEnabled. See batchChunks().
    private boolean batchChunks = true;
    // Aggregator knobs (GpuChunkBatcher): max chunks coalesced into one dispatch, and
    // the max time a request waits for its group to fill before being flushed.
    private int batchLimit = 32;
    private long batchWindowMicros = 500L;
    // Pipeline depth (GpuBatchDispatcher slot ring): how many batched dispatches may be
    // in flight at once. DEFAULT 2 (2026-07-07 evening, measured at 12 AND 21 workers on
    // the 14700K+3070): depth 2 is the sweet spot — it restores the overlap the CLIMATE
    // CHAIN needs (chained combined batches at depth 1 serialized and cost ~8%; at depth 2
    // they equal biome-off = the offload is FREE) while costing biome-off users nothing
    // (21w biome-off: d2 831.1 vs d1 830.9 mean — identical). Depth 4 remains WORSE here
    // (batch shrinkage doubles driver-call volume: ~5% loss in every 12w/21w leg) — on a
    // CPU-bound box total driver interactions are the cost, not GPU latency. Weak-CPU/
    // strong-GPU builds may still benefit from 3-4.
    private int batchPipelineDepth = 2;
    // Kernel merge: emit a router's DFs into ONE program (headers compiled once) instead of one
    // program per DF. Bit-identical (validated: DFC parity 60000/60000 exact), with automatic
    // per-DF fallback. DEFAULT OFF — measured a REGRESSION on real worldgen: clBuildProgram is
    // super-linear in program size, so merging the (large-bodied) router DFs makes the cold JIT
    // dramatically SLOWER (a chunked group build did not finish in 8 min; the whole per-DF cold
    // boot is ~470s). The shared-header saving (~a few s) is dwarfed by that. Only conceivably
    // helpful for an all-tiny-DF dimension; left here, off, for that opt-in case.
    private boolean mergeKernels = false;
    // Whether SUB-full-chunk NoiseChunk lattices (structure-probe column samplers from
    // getBaseHeight/iterateNoiseColumn: ~2 x (cellCountY+1) x 2 corners, ~49-196 points)
    // may arm the coalesced GPU path. DEFAULT FALSE (2026-07-07, 21-worker A/B): those
    // probes were ~11.4k worker-side eager dispatches per r2048 pregen with ~79%
    // had-to-wait parks — routing them to the CPU bytecode path was worth +18% cps at
    // 21 workers (the single biggest slice of the GPU-on tax). Parity-free either way
    // since the crDivide fix made the GPU path FP64 bit-exact (probe outputs identical).
    private boolean subLatticeGpu = false;
    // Precision of the compact-ids DECIDE kernel's decision math (interpolation deltas +
    // emitted tail/barrier/vein noise ASTs). The corner buffer it reads and aq_decide's
    // comparison algebra stay fp64 regardless. DEFAULT TRUE: the decide chain is the one
    // ALU-bound GPU stage (GA104 fp64 = 1/64 rate) and every OpenCL device supports fp32;
    // if the fp32 build fails the dispatcher falls back to the fp64 decide program
    // automatically. Parity envelope measured by the compact-consume-verify flip census
    // (only relevant when compactIds is enabled — itself a relaxed-parity opt-in).
    private boolean decideFp32 = true;
    // FP contraction (FMA) for the fp32 DECIDE program only. FP_CONTRACT stays OFF in
    // every fp64 program (bit-exactness depends on it) and in the fp64 decide fallback.
    // Census-proven zero-flip (459.7M blocks, 2026-07-16) but measured THROUGHPUT-NEUTRAL
    // in an interleaved A/B on the RTX 3070 (the decide kernel is bound by the integer
    // aquifer candidate loop + memory, not fp32 ALU) — so it ships DEFAULT OFF: no gain,
    // no reason to carry the extra deviation surface. Kept as an experiment flag for
    // GPUs with a different fp32/int balance.
    private boolean decideFmaContract = false;
    // fp32 compute for the climate dispatcher's temperature/vegetation kernel (the
    // biome offload's GPU cost is fp64 noise at GA104's 1/64 rate — the reason
    // offloadBiome is a net loss at the GPU-bound operating point). The kernel writes
    // DOUBLE outputs so every host path is unchanged; biome safety = the boot-time
    // quantized acceptance gate (batchedQuantIdentical) + the serve-side quantization
    // guard. MEASURED-REFUTED on vanilla 1.21.1 noise (2026-07-16): temperature/
    // vegetation are DOMAIN-WARPED (shift coords are themselves noise evals), so fp32
    // drift amplifies to ~0.47 quantized units on the overworld — far beyond any
    // usable guard band — and the gate REFUSES the fp32 dispatcher (auto-fallback to
    // fp64, zero behavior change). Kept as a self-protecting experiment flag for
    // datapacks/dimensions with un-warped climate noise.
    private boolean climateFp32 = false;

    private GpuConfig() {
    }

    /**
     * Loads the GPU config from the {@code gpu.*} section of the unified
     * {@code config/superchunk.properties} (written/created by {@link SuperChunkConfig}).
     * Never throws: on any error it logs and returns the defaults.
     */
    public static GpuConfig load() {
        GpuConfig cfg = new GpuConfig();
        try {
            Properties props = SuperChunkConfig.gpuProperties();
            cfg.enabled = parseBool(props.getProperty(K_ENABLED), cfg.enabled);
            cfg.platformIndex = parseInt(props.getProperty(K_PLATFORM), cfg.platformIndex);
            cfg.deviceIndex = parseInt(props.getProperty(K_DEVICE), cfg.deviceIndex);
            // Precision: the three-way desiredFp. The legacy boolean requireFp64 is TRANSLATED
            // into desiredFp at config migration (SuperChunkConfig.migrateLegacy), and desiredFp
            // is always seeded from the unified-config defaults, so there is no live requireFp64
            // fallback to read here.
            cfg.fpMode = FpMode.parse(props.getProperty(K_DESIRED_FP), cfg.fpMode);
            cfg.selftestNoise = parseBool(props.getProperty(K_SELFTEST_NOISE), cfg.selftestNoise);
            cfg.selftestDfc = parseBool(props.getProperty(K_SELFTEST_DFC), cfg.selftestDfc);
            cfg.selftestGpuParity = parseBool(props.getProperty(K_SELFTEST_GPU_PARITY), cfg.selftestGpuParity);
            cfg.profile = parseBool(props.getProperty(K_PROFILE), cfg.profile);
            cfg.latticeCoords = parseBool(props.getProperty(K_LATTICE), cfg.latticeCoords);
            cfg.mappedBuffers = parseBool(props.getProperty(K_MAPPED), cfg.mappedBuffers);
            cfg.coalesce = parseBool(props.getProperty(K_COALESCE), cfg.coalesce);
            cfg.fuseInterpolated = parseBool(props.getProperty(K_FUSE_INTERPOLATED), cfg.fuseInterpolated);
            cfg.asyncReadback = parseBool(props.getProperty(K_ASYNC_READBACK), cfg.asyncReadback);
            cfg.offloadBiome = parseBool(props.getProperty(K_OFFLOAD_BIOME), cfg.offloadBiome);
            cfg.batchChunks = parseBool(props.getProperty(K_BATCH_CHUNKS), cfg.batchChunks);
            cfg.batchLimit = Math.max(1, parseInt(props.getProperty(K_BATCH_LIMIT), cfg.batchLimit));
            cfg.batchWindowMicros = Math.max(0L, parseLong(props.getProperty(K_BATCH_WINDOW_MICROS), cfg.batchWindowMicros));
            cfg.batchPipelineDepth = Math.min(16, Math.max(1, parseInt(props.getProperty(K_BATCH_PIPELINE_DEPTH), cfg.batchPipelineDepth)));
            cfg.mergeKernels = parseBool(props.getProperty(K_MERGE_KERNELS), cfg.mergeKernels);
            cfg.subLatticeGpu = parseBool(props.getProperty(K_SUB_LATTICE_GPU), cfg.subLatticeGpu);
            cfg.decideFp32 = parseBool(props.getProperty(K_DECIDE_FP32), cfg.decideFp32);
            cfg.decideFmaContract = parseBool(props.getProperty(K_DECIDE_FMA_CONTRACT), cfg.decideFmaContract);
            cfg.climateFp32 = parseBool(props.getProperty(K_CLIMATE_FP32), cfg.climateFp32);
            LOGGER.info("[SuperChunk-GPU] Loaded config: enabled={}, platformIndex={}, deviceIndex={}, desiredFp={}, selftest.noise={}, selftest.dfc={}, selftest.gpu_parity={}, profile={}, latticeCoords={}, mappedBuffers={}, coalesce={}, fuseInterpolated={}, asyncReadback={}, offloadBiome={}, batchChunks={}, batchLimit={}, batchWindowMicros={}, batchPipelineDepth={}, mergeKernels={}, subLatticeGpu={}, decideFp32={}, decideFmaContract={}, climateFp32={}",
                    cfg.enabled, cfg.platformIndex, cfg.deviceIndex, cfg.fpMode.token(), cfg.selftestNoise, cfg.selftestDfc, cfg.selftestGpuParity, cfg.profile, cfg.latticeCoords, cfg.mappedBuffers, cfg.coalesce, cfg.fuseInterpolated, cfg.asyncReadback, cfg.offloadBiome, cfg.batchChunks, cfg.batchLimit, cfg.batchWindowMicros, cfg.batchPipelineDepth, cfg.mergeKernels, cfg.subLatticeGpu, cfg.decideFp32, cfg.decideFmaContract, cfg.climateFp32);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] Failed to load gpu.* config — using defaults (enabled=false)", t);
        }
        return cfg;
    }

    private static boolean parseBool(String s, boolean def) {
        if (s == null) return def;
        s = s.trim();
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        return def;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int platformIndex() {
        return platformIndex;
    }

    public int deviceIndex() {
        return deviceIndex;
    }

    /** The desired floating-point precision mode (auto / fp64 / fp32). Never null. */
    public FpMode fpMode() {
        return fpMode;
    }

    /** When true (and the backend is available) run the Stage-1 noise parity test at boot. */
    public boolean selftestNoise() {
        return selftestNoise;
    }

    /** When true (and the backend is available) run the Stage-2 DFC parity test at boot. */
    public boolean selftestDfc() {
        return selftestDfc;
    }

    /**
     * When true (and the backend is available) run the Stage-3 GPU-vs-VANILLA
     * density-function parity test at boot. Bit-exact expected on fp64 hardware;
     * this is the RTX 3070 acceptance gate.
     */
    public boolean selftestGpuParity() {
        return selftestGpuParity;
    }

    /**
     * When true, the GPU density-fill path records OpenCL event profiling
     * (queued/submit/start/end) and host-side timers (upload/readback/sync), and
     * logs a breakdown at server stop. Off by default — zero hot-path overhead.
     */
    public boolean profile() {
        return profile;
    }

    /**
     * (Phase B) When true, regular-lattice fills generate coordinates in-kernel
     * from origin+stride+dims instead of uploading x/y/z arrays. Default true.
     */
    public boolean latticeCoords() {
        return latticeCoords;
    }

    /**
     * (Phase B) When true, use pinned/mapped host buffers (CL_MEM_ALLOC_HOST_PTR)
     * for output readback and any array coord uploads. Zero-copy on the iGPU
     * (unified memory), pinned DMA on a discrete GPU. Default true.
     */
    public boolean mappedBuffers() {
        return mappedBuffers;
    }

    /**
     * (Stage 5) When true, the GPU density-fill coalesces a whole chunk's
     * cell-corner grid for each density function into ONE dispatch and serves every
     * per-column fill from that cache (no per-column dispatch). Bit-identical to the
     * per-column path. Default true. Requires {@link #latticeCoords()}.
     */
    public boolean coalesce() {
        return coalesce;
    }

    /**
     * (LIVE DF fusion) When true, the chunk's interpolated density grids
     * ({@code finalDensity} + {@code initialDensityWithoutJaggedness}) are computed
     * in ONE multi-output GPU dispatch per chunk rather than one dispatch per DF.
     * Bit-identical to the per-DF whole-grid coalesced path (same kernel math), with
     * automatic fallback to that path on any failure. Default true. Requires
     * {@link #coalesce()} (and hence {@link #latticeCoords()}).
     */
    public boolean fuseInterpolated() {
        return fuseInterpolated;
    }

    /**
     * (Async GPU readback) When true, the fused per-chunk multi-output dispatch is
     * issued EAGERLY at chunk-interpolation start ({@code beginChunk}) with a
     * NON-blocking readback, and the worker thread only waits on the CL event at the
     * first density use — overlapping any intervening CPU work with the GPU
     * dispatch/readback instead of fully parking on it. Bit-identical to the blocking
     * fused path (same kernel, same bytes), with automatic fallback to blocking on any
     * failure. Default true. Requires {@link #fuseInterpolated()} (and hence
     * {@link #coalesce()} + {@link #latticeCoords()}).
     */
    public boolean asyncReadback() {
        return asyncReadback;
    }

    /**
     * (Biome climate offload) When true, the chunk's three UNCACHED biome-climate
     * density functions (temperature, vegetation, depth) are computed for the whole
     * per-chunk quart lattice in ONE fused multi-output GPU dispatch during biome
     * population, and each {@code Climate.Sampler.sample()} is served from that cache
     * instead of recomputing the multi-octave noise on the CPU. The remaining three
     * climate DFs (continents/erosion/ridges) and the final biome selection are
     * untouched. Bit-identical to vanilla {@code compute()}; automatic CPU fallback on
     * any failure. DEFAULT FALSE — measured ~11% cps cost at 21 workers (2026-07-07
     * decomposition A/B); enable for live-play/thermal experiments only. Requires
     * {@link #fuseInterpolated()} (and hence {@link #coalesce()} + {@link #latticeCoords()}).
     */
    public boolean offloadBiome() {
        return offloadBiome;
    }

    /**
     * (LIVE cross-chunk batched dispatch) When true, concurrently-generating chunks'
     * fused-group density grids are computed in BATCHED {@code df_batch_lattice_multichunk}
     * dispatches: the noise status boundary submits each chunk to a
     * {@link dev.superchunk.gpu.dfc.GpuChunkBatcher} and FREES the c2me-worker (async
     * status step) until the batch completes, then the fill serves the precomputed grids
     * via {@link dev.superchunk.gpu.dfc.GpuBatchStore}. Bit-identical to the per-chunk
     * fused path; automatic fallback to it on any failure/timeout. Requires
     * {@link #fuseInterpolated()} (and hence {@link #coalesce()} + {@link #latticeCoords()}).
     * Default false (OFF = byte-for-byte the per-chunk path).
     */
    public boolean batchChunks() {
        return batchChunks;
    }

    /**
     * (Cross-chunk aggregator) Max chunks {@link dev.superchunk.gpu.dfc.GpuChunkBatcher}
     * coalesces into ONE batched GPU dispatch. THE LIMIT to raise for more coalescing.
     * Default 32; clamped to &ge; 1.
     */
    public int batchLimit() {
        return batchLimit;
    }

    /**
     * (Cross-chunk aggregator) Max microseconds a pooled request waits for its geometry
     * group to reach {@link #batchLimit()} before being flushed anyway. Default 500;
     * clamped to &ge; 0.
     */
    public long batchWindowMicros() {
        return batchWindowMicros;
    }

    /**
     * (Pipelined dispatch) Number of independent in-flight batch slots per
     * {@link dev.superchunk.gpu.dfc.GpuBatchDispatcher} (own queue + kernel clone +
     * buffers each). Higher depths overlap GPU compute/DMA with host-side
     * collection/slicing so the GPU pipeline's absorption rate scales instead of
     * capping at one round-trip at a time. Default 1 (measured fastest on a strong
     * CPU — see the field comment); raise to 2-4 on weak-CPU/strong-GPU builds.
     * Clamped to [1, 16].
     */
    public int batchPipelineDepth() {
        return batchPipelineDepth;
    }

    /**
     * (Kernel merge) When true, a router's density-function kernels are emitted into ONE OpenCL
     * program (the shared noise.cl + dfc_support.cl headers compiled once instead of per-DF) and
     * built a single time, instead of one program per DF. Bit-identical (same generated math, just
     * per-DF symbol namespacing); any failure falls back to the per-DF build. Default FALSE: this
     * was measured a cold-boot REGRESSION on real worldgen, because clBuildProgram is super-linear
     * in program size, so merging the large-bodied router DFs makes the cold JIT dramatically
     * slower and dwarfs the shared-header saving (SuperChunkConfig also sets gpu.mergeKernels=false).
     */
    public boolean mergeKernels() {
        return mergeKernels;
    }

    /**
     * Whether SUB-full-chunk NoiseChunk lattices (the 1-cell column samplers vanilla builds
     * for every {@code getBaseHeight}/{@code getBaseColumn} structure probe via
     * {@code iterateNoiseColumn}) may arm the coalesced whole-grid GPU path. DEFAULT FALSE:
     * routing those ~49-196-point lattices to the CPU bytecode path (no worker-side
     * dispatch/park per probe) measured +18% cps at 21 workers. Parity-free either way
     * post-crDivide (GPU FP64 bit-exact). See the field comment for the measurements.
     */
    public boolean subLatticeGpu() {
        return subLatticeGpu;
    }

    /**
     * Whether the compact-ids DECIDE kernel builds its decision math (interpolation
     * deltas + emitted tail/barrier/vein noise ASTs) as fp32 ({@code -DUSE_FP32}).
     * The device-resident corner buffer it reads and {@code aq_decide}'s comparison
     * algebra stay fp64 in both builds. DEFAULT TRUE — the decide chain is the one
     * ALU-bound GPU stage and every OpenCL device supports fp32; on an fp32 build
     * failure the dispatcher automatically retries the fp64 decide program. Only
     * meaningful when the (relaxed-parity, default-off) compactIds chain is enabled;
     * flip envelope measured by the compact-consume-verify census.
     */
    public boolean decideFp32() {
        return decideFp32;
    }

    /**
     * FP contraction (FMA) for the fp32 decide program only ({@code gpu.decideFmaContract},
     * default true). No effect on any fp64 program (those keep {@code FP_CONTRACT OFF} for
     * bit-exactness) or on the fp64 decide fallback; output envelope gated by the
     * compact-consume-verify flip census, like {@link #decideFp32()} itself.
     */
    public boolean decideFmaContract() {
        return decideFmaContract;
    }

    /**
     * fp32 compute for the climate chain's temperature/vegetation kernel
     * ({@code gpu.climateFp32}); double outputs + the serve-side quantization guard
     * keep biomes bit-identical. See the field comment.
     */
    public boolean climateFp32() {
        return climateFp32;
    }
}
