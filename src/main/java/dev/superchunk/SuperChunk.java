package dev.superchunk;

import dev.superchunk.ca.spottedleaf.starlight.common.ScalableLuxEntrypoint;
import dev.superchunk.com.ishland.c2me.base.common.network.ExtRenderDistance;
import dev.superchunk.com.ishland.c2me.notickvd.common.NoTickVDInitializer;
import dev.superchunk.gpu.GpuSelfTest;
import dev.superchunk.gpu.OpenCLBackend;
import dev.superchunk.gpu.aquifer.RngSelfTest;
import dev.superchunk.gpu.aquifer.AquiferGpuVerify;
import dev.superchunk.gpu.noise.GpuNoiseParityTest;
import dev.superchunk.gpu.dfc.GpuDfcParityTest;
import dev.superchunk.gpu.dfc.GpuFillStats;
import dev.superchunk.gpu.dfc.GpuFillProfiler;
import dev.superchunk.gpu.dfc.GpuVanillaParityTest;
import dev.superchunk.net.caffeinemc.mods.lithium.neoforge.LithiumNeoForgeMod;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * SuperChunk — unified worldgen + chunk-performance super-mod.
 *
 * <p>This is a from-source merge of four upstream mods (C2ME, ScalableLux,
 * NoisiumForked, Lithium). Integration is staged: each domain is brought in
 * under its own mixin config and reconciled against the others. This class is
 * just the entry point; the heavy lifting lives in the mixin packages and the
 * ported engine code.
 */
@Mod(SuperChunk.MOD_ID)
public final class SuperChunk {
    public static final String MOD_ID = "superchunk";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SuperChunk(IEventBus modEventBus) {
        LOGGER.info("[SuperChunk] initializing — unified worldgen/chunk super-mod.");
        // Diagnostic: periodic chunk census (leak-vs-plateau); daemon self-idles when no server runs.
        // Off by default — enable with -Dsuperchunk.diag.chunkCensus=true.
        dev.superchunk.diag.ChunkCensus.start();
        // One-time GC advisory: high render distances stutter under stop-the-world GC (G1/Aikar) —
        // recommend Generational ZGC. Advice only; a mod cannot change the running collector.
        dev.superchunk.diag.GcAdvisory.maybeAdvise();
        // --- ScalableLux (lighting) ---
        ScalableLuxEntrypoint.init();
        // --- Lithium ---
        LithiumNeoForgeMod.init();
        // --- C2ME no-tick view distance: extended render distance protocol ---
        modEventBus.addListener(ExtRenderDistance::register);
        NoTickVDInitializer.init();
        // Client half (slider cap widening is the client mixin; this sends the
        // ext_render_distance request on login when the slider is above 32). The class
        // touches client-only types, so it is only loaded inside the dist check.
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            dev.superchunk.client.ExtendedRenderDistance.init();
        }
        // --- C2ME gc-free chunk serializer: register serializer when enabled ---
        dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.ModuleEntryPoint.init();
        // --- C2ME base config: flush c2me.toml at common setup (upstream C2ME-NeoForge
        // parity — its @Mod("c2me_base") does exactly this). By common setup every
        // module's Config statics have registered their keys, so the flush persists
        // defaults + comments on fresh installs and version migrations on upgrades.
        // Without this the whole c2me.toml lifecycle is dead (never written).
        modEventBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) ->
                dev.superchunk.com.ishland.c2me.base.C2MEBaseMod.onSetup());

        // --- GPU/OpenCL backend (Stage 0): foundation for later worldgen offload. ---
        // Initialized per-world via ServerAboutToStartEvent (NOT once at mod
        // construction): this is a client mod on the integrated server, so the backend
        // is torn down on every ServerStopping (exit-to-title). Re-running init() before
        // each world lets a second/third single-player world in the same session
        // re-acquire the GPU instead of silently falling back to CPU for the rest of the
        // session. init() is synchronized + idempotent (no-op when already available),
        // so re-entry is safe.
        try {
            NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> initGpuBackend());
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk] failed to register GPU init hook.", t);
        }

        // Release all OpenCL resources (per-thread queues/kernels/buffers + context)
        // and log the final live-integration density-fill summary on server stop.
        // Registered unconditionally — the handler is a cheap no-op when GPU is off.
        try {
            NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
                try {
                    GpuFillStats.logSummary("server stopping");
                } catch (Throwable ignored) {
                }
                try {
                    // STAGE-1 full-field bench summary (no-op unless -Dsuperchunk.gpu.fullFieldBench).
                    dev.superchunk.gpu.dfc.FullFieldBench.logSummary("server stopping");
                } catch (Throwable ignored) {
                }
                try {
                    // STAGE-2 on-device interpolation summary / verify gate (no-op unless
                    // -Dsuperchunk.gpu.onDeviceInterp[.verify]). In verify mode this is the
                    // BUGS=0 parity report.
                    dev.superchunk.gpu.dfc.OnDeviceInterp.logSummary("server stopping");
                } catch (Throwable ignored) {
                }
                try {
                    if (GpuFillProfiler.ENABLED) {
                        GpuFillProfiler.logBreakdown("server stopping");
                    }
                } catch (Throwable ignored) {
                }
                try {
                    // Aquifer GPU decision verify gate (no-op unless -Dsuperchunk.gpu.aquiferVerify).
                    // Flush any residual per-thread batches, then the final BUGS / spline-wall report.
                    if (AquiferGpuVerify.ENABLED) {
                        AquiferGpuVerify.flushAll();
                        AquiferGpuVerify.report("server stopping");
                        AquiferGpuVerify.close();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    // STAGE-3 self-consistent block-flip census (no-op unless
                    // -Dsuperchunk.gpu.blockIdVerify). Flush residual per-thread batches,
                    // then the final [blockid-census] transition-matrix / HARD-ZERO report.
                    if (dev.superchunk.gpu.aquifer.BlockIdCensus.ENABLED) {
                        dev.superchunk.gpu.aquifer.BlockIdCensus.flushAll();
                        dev.superchunk.gpu.aquifer.BlockIdCensus.report("server stopping");
                        dev.superchunk.gpu.aquifer.BlockIdCensus.close();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    // STAGE-5/6 compact-ids (no-op unless -Dsuperchunk.gpu.compactIds=probe|verify|on):
                    // final [compact-ids] chunks-with-ids / decide-duty / aux-failure report, the
                    // Stage-6 [compact-consume]/[compact-consume-verify] consume/equivalence report,
                    // then drop the per-world routes (they reference this world's GPU forms).
                    dev.superchunk.gpu.dfc.CompactIds.report("server stopping");
                    dev.superchunk.gpu.dfc.CompactConsume.report("server stopping");
                    dev.superchunk.gpu.dfc.CompactIds.reset();
                } catch (Throwable ignored) {
                }
                try {
                    dev.superchunk.gpu.CLProgramCache.logStats("server stopping");
                } catch (Throwable ignored) {
                }
                // Reset the cache enable flags BEFORE releasing the context. These
                // paths are gated only on `enabled` (not OpenCLBackend.isAvailable()),
                // so a stale-true flag would otherwise reference torn-down CL handles
                // after shutdown instead of cleanly falling back to CPU. They are
                // re-wired on the next ServerAboutToStart.
                try {
                    dev.superchunk.gpu.dfc.ChunkGridCache.setEnabled(false);
                    dev.superchunk.gpu.dfc.ChunkGridCache.setFusionEnabled(false);
                    dev.superchunk.gpu.dfc.ChunkGridCache.setAsyncEnabled(false);
                    dev.superchunk.gpu.dfc.ChunkGridCache.setBatchEnabled(false);
                    dev.superchunk.gpu.dfc.BiomeClimateCache.setEnabled(false);
                } catch (Throwable ignored) {
                }
                OpenCLBackend.shutdown();
            });
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk] failed to register GPU shutdown hook.", t);
        }
    }

    /**
     * GPU/OpenCL backend init + cache-flag wiring; re-run per world via the
     * ServerAboutToStartEvent listener registered in the constructor. init() never
     * throws and is a no-op when disabled or no GPU is present (graceful CPU
     * fallback). When enabled + available, run the FP32 self-test.
     */
    private static void initGpuBackend() {
        try {
            OpenCLBackend.init();
            if (OpenCLBackend.config().isEnabled()) {
                // Phase-A: resolve the profiling flag once (zero hot-path cost when off).
                GpuFillProfiler.configure(OpenCLBackend.isAvailable() && OpenCLBackend.config().profile());
                if (OpenCLBackend.isAvailable()) {
                    // Stage 5: dispatch coalescing — compute a whole chunk's density
                    // grid per DF in ONE dispatch + serve columns from a per-thread
                    // cache. Requires the in-kernel lattice path; bit-identical to the
                    // per-column path. On only when both config flags allow it.
                    dev.superchunk.gpu.dfc.ChunkGridCache.setEnabled(
                            OpenCLBackend.config().coalesce() && OpenCLBackend.config().latticeCoords());
                    // LIVE DF fusion (additive to coalescing): compute the chunk's
                    // interpolated grids (finalDensity + initialDensityWithoutJaggedness)
                    // in ONE multi-output dispatch. Requires coalescing (and lattice).
                    dev.superchunk.gpu.dfc.ChunkGridCache.setFusionEnabled(
                            OpenCLBackend.config().fuseInterpolated()
                                    && OpenCLBackend.config().coalesce()
                                    && OpenCLBackend.config().latticeCoords());
                    // ASYNC (non-blocking) GPU readback (additive to fusion): eagerly
                    // dispatch the fused per-chunk kernel + a non-blocking readback at
                    // beginChunk and wait on the CL event only at first density use, so
                    // the c2me-worker doesn't fully park on the GPU. Requires fusion.
                    dev.superchunk.gpu.dfc.ChunkGridCache.setAsyncEnabled(
                            OpenCLBackend.config().asyncReadback()
                                    && OpenCLBackend.config().fuseInterpolated()
                                    && OpenCLBackend.config().coalesce()
                                    && OpenCLBackend.config().latticeCoords());
                    // Biome-climate offload (separate from terrain fusion): fuse the chunk's
                    // uncached temperature/vegetation/depth climate DFs into ONE per-chunk
                    // dispatch over the quart lattice + serve Climate.Sampler.sample from the
                    // cache. Reuses the fused-interpolator + async-readback infra, so it
                    // requires the same lattice path those depend on.
                    dev.superchunk.gpu.dfc.BiomeClimateCache.setEnabled(
                            OpenCLBackend.config().offloadBiome()
                                    && OpenCLBackend.config().fuseInterpolated()
                                    && OpenCLBackend.config().coalesce()
                                    && OpenCLBackend.config().latticeCoords());
                    // LIVE cross-chunk batching (batchChunks): wire the GpuChunkBatcher into
                    // the noise status boundary so concurrent chunks' fused grids are computed
                    // in BATCHED dispatches with the worker freed during the wait. Reuses the
                    // fused-interpolator + lattice infra, so it requires the same flags. Default
                    // OFF -> byte-for-byte today's behavior (nothing submits, nothing stores).
                    // STAGE 2 on-device interpolation now COEXISTS with cross-chunk batching, and
                    // the full field is PREFETCHED: when the batcher deposits a chunk's batched
                    // corners it ALSO produces the two full fields right then (on the batcher
                    // thread, off the worker's fill critical path) into a REUSED buffer from a
                    // bounded pool (OnDeviceFieldPool, -Dsuperchunk.gpu.onDeviceInterp.prefetchDepth,
                    // default 8), so the fill serves them READY with NO GPU round-trip and ZERO
                    // per-chunk field allocation. When the pool is exhausted the chunk falls back
                    // to per-chunk field production (ChunkGridCache.consumeBatchedGrid ->
                    // provideFullFieldFromHostCorners). Batch-miss chunks take the per-chunk eager
                    // async path which also enqueues the field. So batching is NO LONGER
                    // force-disabled when on-device interp is on.
                    dev.superchunk.gpu.dfc.ChunkGridCache.setBatchEnabled(
                            OpenCLBackend.config().batchChunks()
                                    && OpenCLBackend.config().fuseInterpolated()
                                    && OpenCLBackend.config().coalesce()
                                    && OpenCLBackend.config().latticeCoords());
                    // Sub-full-chunk lattice routing (structure-probe column samplers):
                    // gpu.subLatticeGpu=true (default) keeps them on the coalesced GPU path;
                    // false routes them to the CPU bytecode gate (no per-probe worker park).
                    dev.superchunk.gpu.dfc.ChunkGridCache.setSubLatticeGpu(
                            OpenCLBackend.config().subLatticeGpu());
                    if (dev.superchunk.gpu.dfc.OnDeviceInterp.ENABLED && OpenCLBackend.config().batchChunks()) {
                        LOGGER.info("[SuperChunk-GPU] [on-device-interp] cross-chunk batching ENABLED alongside on-device "
                                + "interpolation (full field PREFETCHED into a pooled buffer at batch deposit; served ready, "
                                + "no worker wait; prefetchDepth={}).", dev.superchunk.gpu.dfc.OnDeviceFieldPool.CAPACITY);
                    }
                    GpuSelfTest.run();
                    // Aquifer block-decision VERIFY gate (default OFF;
                    // -Dsuperchunk.gpu.aquiferVerify=true => zero behavior change when off).
                    if (AquiferGpuVerify.ENABLED) {
                        // Stage 1 foundation: prove the OpenCL Xoroshiro128++ positional RNG +
                        // nextInt() is bit-exact vs Java (the riskiest piece). Logs [aquifer-rng].
                        RngSelfTest.run();
                        // Isolated kernel self-test: aquifer_decide vs a Java reference on synthetic data.
                        dev.superchunk.gpu.aquifer.AquiferKernelSelfTest.run();
                        // Build the GPU decision kernel so the live MixinAquiferSamplerImpl hook can
                        // compare the GPU decision vs the CPU's actual result per block.
                        AquiferGpuVerify.maybeInit();
                    }
                    // STAGE-3 self-consistent block-flip census (default OFF;
                    // -Dsuperchunk.gpu.blockIdVerify=true => verify-only, vanilla output ships
                    // unchanged). Builds the aquifer_census kernel; the live seams
                    // (MaterialRuleList.calculate arm/finalize + computeSubstance record) then
                    // compare the GPU per-block decision + fluid-tick bit against the shipped
                    // result. The flag also force-enables OnDeviceInterp VERIFY (the mode-C
                    // density-equality proof leg — see BlockIdCensus javadoc).
                    if (dev.superchunk.gpu.aquifer.BlockIdCensus.ENABLED) {
                        dev.superchunk.gpu.aquifer.BlockIdCensus.maybeInit();
                    }
                    // Diagnostic (guarded by -Dsuperchunk.gpu.compileBench=N): measure whether the
                    // driver compiles kernels faster in parallel than sequentially. No-op when unset.
                    dev.superchunk.gpu.GpuCompileBench.run();
                    // STAGE-2 diagnostic (guarded by -Dsuperchunk.gpu.decideBench=N, default OFF):
                    // batched decide-kernel duty bench — synthetic-input decide kernel over the
                    // K*M*1225 multichunk corner layout (in-register lerp3 + finalDensity tail +
                    // REAL aq_decide + vein Xoroshiro draws -> 1 byte/block), timed with the
                    // pinned-map id readback on ONE in-order queue. Measures ms/chunk against the
                    // fp64 <= 1.0 ms/chunk GO gate at K=32. Purely additive: no production kernel
                    // or dispatch path is touched, terrain unchanged. No-op when unset.
                    dev.superchunk.gpu.dfc.DecideBench.run();
                    // STAGE-5 compact-ids probe (guarded by -Dsuperchunk.gpu.compactIds=probe,
                    // default off): boot status line only — the decide chain itself builds
                    // lazily when the live batcher's dispatcher compiles (ChunkGridCache.
                    // ensureBatcher -> GpuBatchDispatcher.compile(roots, fused)).
                    dev.superchunk.gpu.dfc.CompactIds.logBoot();
                    // Stage 1: CPU-vs-GPU worldgen-noise parity test (guarded; off by default).
                    if (OpenCLBackend.config().selftestNoise()) {
                        GpuNoiseParityTest.run();
                    }
                    // Stage 2: CPU-vs-GPU density-function-compiler parity test (guarded; off by default).
                    if (OpenCLBackend.config().selftestDfc()) {
                        GpuDfcParityTest.run();
                    }
                    // Stage 3b: GPU-compiled-DF vs VANILLA density functions (bit-exact at
                    // fp64). The 3070 acceptance gate (guarded; off by default).
                    if (OpenCLBackend.config().selftestGpuParity()) {
                        GpuVanillaParityTest.run();
                    }
                } else {
                    LOGGER.info("[SuperChunk] GPU enabled but unavailable — continuing on CPU.");
                }
            } else {
                LOGGER.info("[SuperChunk] GPU disabled — continuing on CPU.");
            }
        } catch (Throwable t) {
            // Belt-and-suspenders: the GPU path must never break worldgen startup.
            LOGGER.warn("[SuperChunk] GPU backend init threw — continuing on CPU.", t);
        }
    }
}
