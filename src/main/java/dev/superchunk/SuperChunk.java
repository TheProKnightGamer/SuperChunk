package dev.superchunk;

import com.mojang.logging.LogUtils;
import dev.superchunk.ca.spottedleaf.starlight.common.ScalableLuxEntrypoint;
import dev.superchunk.gpu.CLProgramCache;
import dev.superchunk.gpu.GpuCompileBench;
import dev.superchunk.gpu.GpuSelfTest;
import dev.superchunk.gpu.OpenCLBackend;
import dev.superchunk.gpu.aquifer.AquiferGpuVerify;
import dev.superchunk.gpu.aquifer.RngSelfTest;
import dev.superchunk.gpu.dfc.GpuDfcParityTest;
import dev.superchunk.gpu.dfc.GpuFillProfiler;
import dev.superchunk.gpu.dfc.GpuFillStats;
import dev.superchunk.gpu.dfc.GpuVanillaParityTest;
import dev.superchunk.gpu.noise.GpuNoiseParityTest;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * SuperChunk — Forge 1.20.1 port. Entry point.
 *
 * <p><b>Stage 2 (this build):</b> stage 1's OpenCL GPU backend PLUS the full worldgen GPU
 * offload — the C2ME density-function compiler (opts/dfc), the gpu/dfc batching/fusion/climate
 * runtime, the aquifer/census verify gates and the wiring mixins — running on the VANILLA
 * 1.20.1 chunk system ({@code MixinNoiseChunkGeneratorGpuSeam} replaces the C2ME
 * chunk-system delegate seams of the neo build when the C2ME chunk system is disabled; the
 * chunk-system delegate owns the seams when it is enabled). As of 2026-07-18 the FULL module
 * set is ported: C2ME (chunk system, schedulers, serializer, notickvd), ScalableLux, VMP,
 * predictive gen — see PORT-NOTES.md. The NeoForge 1.21.1 original lives in {@code ../neo-1.21.1}.
 *
 * <p>Backend init is wired to {@link ServerAboutToStartEvent} (re-acquires the GPU per world;
 * {@code init()} is idempotent) and torn down on {@link ServerStoppingEvent}. Every GPU path is a
 * graceful CPU fallback — the backend never breaks startup.
 */
@Mod(SuperChunk.MOD_ID)
public final class SuperChunk {
    public static final String MOD_ID = "superchunk";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SuperChunk() {
        // Again here: the loader may replace the early logging configuration before mods construct.
        dev.superchunk.diag.LogQuieter.install();
        LOGGER.info("[SuperChunk] Forge 1.20.1 port initializing — GPU worldgen offload (stage 2).");
        // Diagnostic: periodic chunk census (leak-vs-plateau); daemon self-idles when no server runs.
        // Off by default — enable with -Dsuperchunk.diag.chunkCensus=true.
        dev.superchunk.diag.ChunkCensus.start();
        // Chunk-SEND funnel counters (-Dsuperchunk.diag.chunkSend=true); off by default.
        dev.superchunk.diag.ChunkSendDiag.start();
        // One-time GC advisory: high render distances stutter under stop-the-world GC (G1/Aikar) —
        // recommend Generational ZGC. Advice only; a mod cannot change the running collector.
        dev.superchunk.diag.GcAdvisory.maybeAdvise();
        // --- ScalableLux (lighting): forces the static config load (mirrors neo SuperChunk). ---
        ScalableLuxEntrypoint.init();
        // --- C2ME gc-free chunk serializer registration (neo SuperChunk parity, plan §1.5/§3 gate 3). ---
        dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.ModuleEntryPoint.init();
        // --- Merged Lithium (portplans/lithium.md wave L1): entrypoint-parity init + the version
        // feed that replaced neo's pruned startup.MinecraftMixin (mid-ctor @Inject, rejected by
        // runtime mixin 0.8.5 — plan §2C). Called on BOTH dists (neo only set it on client;
        // LithiumMod.getVersion() has no critical callers, so this is strictly more robust). ---
        try {
            dev.superchunk.net.caffeinemc.mods.lithium.forge.LithiumForgeMod.init();
            String version;
            try {
                version = net.minecraftforge.fml.ModLoadingContext.get().getActiveContainer()
                        .getModInfo().getVersion().toString();
            } catch (Throwable t) {
                version = "unknown";
            }
            dev.superchunk.net.caffeinemc.mods.lithium.common.LithiumMod.onInitialization(version);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk] failed to initialize the merged Lithium module.", t);
        }
        // --- C2ME no-tick view distance + extended render distance protocol (vmp-notickvd-player.md §5.4). ---
        try {
            // SimpleChannel registration must run during mod construction, BOTH dists (the
            // channel identity is part of the handshake either way).
            dev.superchunk.com.ishland.c2me.base.common.network.ExtRenderDistance.register();
            dev.superchunk.com.ishland.c2me.notickvd.common.NoTickVDInitializer.init();
            if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT) {
                dev.superchunk.client.ExtendedRenderDistance.init(); // client-only class, loaded inside the guard
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk] failed to initialize notickvd/ext-render-distance wiring.", t);
        }
        // C2ME base config: flush c2me.toml at common setup (upstream parity — by then every
        // ported module's Config statics have registered their keys). Inlined C2MEBaseMod.onSetup().
        try {
            FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLCommonSetupEvent e) ->
                    dev.superchunk.com.ishland.c2me.base.common.config.ConfigSystem.flushConfig());
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk] failed to register c2me config flush hook.", t);
        }
        try {
            MinecraftForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> initGpuBackend());
            // c2me server-utils /c2me debug commands. Registered via the Forge event instead of
            // upstream's Commands-<init> mixin: runtime mixin 0.8.5 rejects mid-constructor
            // @Inject points ("Found @Inject targetting a constructor"), and the event is the
            // idiomatic Forge seam anyway.
            MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.RegisterCommandsEvent e) ->
                    dev.superchunk.com.ishland.c2me.server.utils.common.C2MECommands.register(e.getDispatcher()));
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk] failed to register GPU init hook.", t);
        }

        // --- Distant Horizons interop ---
        // One boot line describing what engaged, and the C2ME-accessor registration that stops DH
        // reporting "C2ME missing, slow world gen expected" for chunk requests SuperChunk's own
        // C2ME chunk system is serving. Registered per world start (DH tears its levels down on
        // exit-to-title just like the GPU backend) and one-shot internally. No-op without DH.
        dev.superchunk.compat.DistantHorizonsCompat.logBootLine();
        try {
            MinecraftForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) ->
                    dev.superchunk.compat.DhC2meAccessor.bindIfPresent());
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk] failed to register the Distant Horizons compat hook.", t);
        }
        // Release all OpenCL resources (per-thread queues/kernels/buffers + context)
        // and log the final live-integration density-fill summaries on server stop.
        // Registered unconditionally — the handler is a cheap no-op when GPU is off.
        try {
            MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
                if (Boolean.getBoolean("superchunk.worldgen.biomeQuartCache.verify")) {
                    dev.superchunk.worldgen.BiomeQuartCache.reportVerify();
                }
                dev.superchunk.worldgen.FirstPositionalFloat.reportVerify();
                dev.superchunk.worldgen.ReusableOreRandom.reportVerify();
                dev.superchunk.worldgen.LerpTables.reportVerify();
                dev.superchunk.worldgen.AirCells.reportVerify();
                dev.superchunk.worldgen.CellSignSkip.report();
                dev.superchunk.worldgen.CornerColumns.report();
                dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.PreliminarySurfaceLevels.report();
                dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer.ScAquiferCellCache.report();
                dev.superchunk.worldgen.LightTiming.report();
                dev.superchunk.ca.spottedleaf.starlight.common.light.LightVerify.report();
                dev.superchunk.worldgen.OreTiming.report();
                dev.superchunk.worldgen.OreRuns.report();
                if (Boolean.getBoolean("superchunk.worldgen.biomeFiddleCache.verify")) {
                    dev.superchunk.worldgen.BiomeFiddleCache.reportVerify();
                }
                if (Boolean.getBoolean("superchunk.worldgen.flatClimateSearch.verify")) {
                    dev.superchunk.worldgen.FlatClimateIndex.reportVerify();
                }
                if (dev.superchunk.worldgen.ClimateColumns.VERIFY) {
                    dev.superchunk.worldgen.ClimateColumns.reportVerify();
                }
                try {
                    GpuFillStats.logSummary("server stopping");
                } catch (Throwable ignored) {
                }
                try {
                    dev.superchunk.gpu.dfc.FullFieldBench.logSummary("server stopping");
                } catch (Throwable ignored) {
                }
                try {
                    dev.superchunk.gpu.dfc.OnDeviceInterp.logSummary("server stopping");
                } catch (Throwable ignored) {
                }
                try {
                    // Distant Horizons batch-generation prefetch tally (no-op unless DH's own
                    // generator actually ran groups through the seam this session).
                    dev.superchunk.compat.DhWorldGenBridge.logSummary("server stopping");
                } catch (Throwable ignored) {
                }
                try {
                    if (GpuFillProfiler.ENABLED) {
                        GpuFillProfiler.logBreakdown("server stopping");
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (AquiferGpuVerify.ENABLED) {
                        AquiferGpuVerify.flushAll();
                        AquiferGpuVerify.report("server stopping");
                        AquiferGpuVerify.close();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (dev.superchunk.gpu.aquifer.BlockIdCensus.ENABLED) {
                        dev.superchunk.gpu.aquifer.BlockIdCensus.flushAll();
                        dev.superchunk.gpu.aquifer.BlockIdCensus.report("server stopping");
                        dev.superchunk.gpu.aquifer.BlockIdCensus.close();
                    }
                } catch (Throwable ignored) {
                }
                try {
                    dev.superchunk.gpu.dfc.CompactIds.report("server stopping");
                    dev.superchunk.gpu.dfc.CompactConsume.report("server stopping");
                    dev.superchunk.gpu.dfc.CompactIds.reset();
                } catch (Throwable ignored) {
                }
                try {
                    CLProgramCache.logStats("server stopping");
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
     * GPU/OpenCL backend init + cache-flag wiring + boot self-tests; re-run per world via the
     * {@link ServerAboutToStartEvent} listener. Never throws; a no-op when disabled or no GPU
     * is present (graceful CPU fallback). Ported 1:1 from the neo-1.21.1 entry point.
     */
    private static void initGpuBackend() {
        try {
            OpenCLBackend.init();
            if (OpenCLBackend.config().isEnabled()) {
                GpuFillProfiler.configure(OpenCLBackend.isAvailable() && OpenCLBackend.config().profile());
                if (OpenCLBackend.isAvailable()) {
                    // Stage 5: dispatch coalescing (whole-chunk density grid per DF in one
                    // dispatch, columns served from a per-thread cache).
                    dev.superchunk.gpu.dfc.ChunkGridCache.setEnabled(
                            OpenCLBackend.config().coalesce() && OpenCLBackend.config().latticeCoords());
                    // LIVE DF fusion (finalDensity + initialDensityWithoutJaggedness in one
                    // multi-output dispatch). Requires coalescing (and lattice).
                    dev.superchunk.gpu.dfc.ChunkGridCache.setFusionEnabled(
                            OpenCLBackend.config().fuseInterpolated()
                                    && OpenCLBackend.config().coalesce()
                                    && OpenCLBackend.config().latticeCoords());
                    // ASYNC (non-blocking) GPU readback. Requires fusion.
                    dev.superchunk.gpu.dfc.ChunkGridCache.setAsyncEnabled(
                            OpenCLBackend.config().asyncReadback()
                                    && OpenCLBackend.config().fuseInterpolated()
                                    && OpenCLBackend.config().coalesce()
                                    && OpenCLBackend.config().latticeCoords());
                    // Biome-climate offload (quart-lattice climate DFs in one per-chunk dispatch).
                    dev.superchunk.gpu.dfc.BiomeClimateCache.setEnabled(
                            OpenCLBackend.config().offloadBiome()
                                    && OpenCLBackend.config().fuseInterpolated()
                                    && OpenCLBackend.config().coalesce()
                                    && OpenCLBackend.config().latticeCoords());
                    // LIVE cross-chunk batching at the noise seam (worker freed during the wait;
                    // coexists with on-device interp via the pooled full-field prefetch).
                    dev.superchunk.gpu.dfc.ChunkGridCache.setBatchEnabled(
                            OpenCLBackend.config().batchChunks()
                                    && OpenCLBackend.config().fuseInterpolated()
                                    && OpenCLBackend.config().coalesce()
                                    && OpenCLBackend.config().latticeCoords());
                    // Sub-full-chunk lattice routing (structure-probe column samplers).
                    dev.superchunk.gpu.dfc.ChunkGridCache.setSubLatticeGpu(
                            OpenCLBackend.config().subLatticeGpu());
                    if (dev.superchunk.gpu.dfc.OnDeviceInterp.ENABLED && OpenCLBackend.config().batchChunks()) {
                        LOGGER.info("[SuperChunk-GPU] [on-device-interp] cross-chunk batching ENABLED alongside on-device "
                                + "interpolation (full field PREFETCHED into a pooled buffer at batch deposit; served ready, "
                                + "no worker wait; prefetchDepth={}).", dev.superchunk.gpu.dfc.OnDeviceFieldPool.CAPACITY);
                    }
                    GpuSelfTest.run();
                    // Aquifer block-decision VERIFY gate (-Dsuperchunk.gpu.aquiferVerify=true; off default).
                    if (AquiferGpuVerify.ENABLED) {
                        RngSelfTest.run();
                        dev.superchunk.gpu.aquifer.AquiferKernelSelfTest.run();
                        AquiferGpuVerify.maybeInit();
                    }
                    // STAGE-3 self-consistent block-flip census (-Dsuperchunk.gpu.blockIdVerify=true).
                    if (dev.superchunk.gpu.aquifer.BlockIdCensus.ENABLED) {
                        dev.superchunk.gpu.aquifer.BlockIdCensus.maybeInit();
                    }
                    // Diagnostics (all no-ops unless their -D flags are set).
                    GpuCompileBench.run();
                    dev.superchunk.gpu.dfc.DecideBench.run();
                    dev.superchunk.gpu.dfc.CompactIds.logBoot();
                    // Stage 1: CPU-vs-GPU worldgen-noise parity test (guarded; off by default).
                    if (OpenCLBackend.config().selftestNoise()) {
                        GpuNoiseParityTest.run();
                    }
                    // Stage 2: CPU-vs-GPU density-function-compiler parity test (guarded; off by default).
                    if (OpenCLBackend.config().selftestDfc()) {
                        GpuDfcParityTest.run();
                    }
                    // Stage 3b: GPU-compiled-DF vs VANILLA density functions (bit-exact at fp64).
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
