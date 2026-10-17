package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.SubCompiledDensityFunction;
import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.CLProgramCache;
import dev.superchunk.gpu.OpenCLBackend;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point that C2ME's {@code BytecodeGen.compile} calls after it builds a
 * {@link SubCompiledDensityFunction}: when the GPU backend is enabled + available,
 * try to compile the same AST to OpenCL and attach it as a GPU delegate. On any
 * failure (unsupported node, build error) the DF stays CPU-only — a clean
 * per-density-function fallback.
 *
 * <p>The whole thing is a no-op when {@code gpu enabled=false}: with the GPU off,
 * {@code maybeAttach} returns immediately and behaviour is identical to before.
 *
 * <p><b>Kernel merge (mergeKernels, default off — measured a cold-boot regression since
 * clBuildProgram is super-linear in program size).</b> Each DF kernel re-includes the
 * ~460-line noise.cl + dfc_support.cl headers, and the NVIDIA driver compiles them
 * from scratch every time AND serializes builds internally — so a cold boot JITs the
 * shared headers ~N times. When {@link #beginBatch()} / {@link #endBatch()} wrap a
 * router build ({@code MixinNoiseConfig}), the DFs are EMITTED (cheap) at the call
 * site with a unique per-DF symbol tag and accumulated; at the barrier they are
 * concatenated into ONE program (headers ONCE) and built a single time, then each
 * deferred {@link GpuDfcDelegate} is resolved to its tagged kernels. Bit-identical
 * (same generated math, just namespaced + shared headers). Any failure falls back to
 * the per-DF build, so the worst case is exactly today's behaviour.
 */
public final class GpuDfcHook {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final AtomicInteger gpuCount = new AtomicInteger();
    private static final AtomicInteger cpuCount = new AtomicInteger();

    /** Master switch resolved once; the GPU path is fully dormant when false. */
    private static volatile Boolean enabled = null;
    /** Kernel-merge flag resolved once. */
    private static volatile Boolean mergeKernels = null;
    /** One-shot: warned that the run-constant latch is OFF while the backend is now up. */
    private static volatile boolean latchedOffWarned = false;

    /** A DF emitted during a batch, awaiting the merged build at {@link #endBatch()}. */
    private record Pending(AstNode ast, NoiseRegistry registry, String tag, String generated,
                           boolean fp32, GpuDfcDelegate delegate) {
    }

    /**
     * The in-progress batch for THIS thread (router build is single-threaded per RandomState;
     * a thread-local keeps concurrent dimension builds independent). {@code null} = not batching
     * -> DFs build per-DF immediately, exactly as before.
     */
    private static final ThreadLocal<List<Pending>> BATCH = new ThreadLocal<>();

    private GpuDfcHook() {
    }

    private static boolean isEnabled() {
        Boolean e = enabled;
        if (e == null) {
            synchronized (GpuDfcHook.class) {
                if (enabled == null) {
                    enabled = OpenCLBackend.config() != null
                            && OpenCLBackend.config().isEnabled()
                            && OpenCLBackend.isAvailable();
                    if (enabled) {
                        LOGGER.info("[SuperChunk-GPU] DFC GPU routing ENABLED — density functions will be "
                                + "compiled to OpenCL where supported (per-DF CPU fallback otherwise).");
                    }
                }
                e = enabled;
            }
        }
        // SILENT-GAP GUARD: the latch is deliberately run-constant (generated-class field
        // layout — see CacheLikeNode / BytecodeGen.compilationCache), so a session whose
        // FIRST world resolved it FALSE (gpu.enabled flipped on without a restart, or a
        // transient CL init failure) stays 100% CPU for the rest of the session even
        // though every later world's backend boots green. That must not be invisible:
        // warn once when a router compiles with the backend UP but routing latched OFF.
        if (!e && !latchedOffWarned
                && OpenCLBackend.config() != null
                && OpenCLBackend.config().isEnabled()
                && OpenCLBackend.isAvailable()) {
            latchedOffWarned = true;
            LOGGER.warn("[SuperChunk-GPU] GPU backend is UP but DFC routing was latched OFF earlier this session "
                    + "(disabled config or failed init at first router compile). Density functions will run 100% CPU "
                    + "for the REST OF THIS SESSION despite green GPU boot logs — restart the game to engage the GPU.");
        }
        return e;
    }

    private static boolean mergeEnabled() {
        Boolean m = mergeKernels;
        if (m == null) {
            m = OpenCLBackend.config() != null && OpenCLBackend.config().mergeKernels();
            mergeKernels = m;
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Batch session (wrapped around a router build by MixinNoiseConfig).
    // ------------------------------------------------------------------

    /** Opens a kernel-merge batch on this thread (no-op when GPU off or mergeKernels off). */
    public static void beginBatch() {
        if (isEnabled() && mergeEnabled() && BATCH.get() == null) {
            BATCH.set(new ArrayList<>());
        }
    }

    /** Closes the batch: builds the ONE merged program and resolves every deferred delegate. */
    public static void endBatch() {
        List<Pending> batch = BATCH.get();
        if (batch == null) {
            return;
        }
        BATCH.remove();
        if (batch.isEmpty()) {
            return;
        }
        try {
            buildMergedAndResolve(batch);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] merged kernel build threw — per-DF fallback.", t);
            resolvePerDf(batch);
        }
    }

    /** Emits {@code ast} with a unique tag and records it; returns a deferred delegate (or null = CPU). */
    private static GpuDfcDelegate enqueue(List<Pending> batch, AstNode ast) {
        try {
            boolean fp32 = OpenCLBackend.isFp32();
            NoiseRegistry registry = new NoiseRegistry();
            String tag = "d" + batch.size() + "_";
            // throws UnsupportedDfNodeException if this DF can't be GPU-compiled -> CPU.
            String generated = OpenCLAstEmitter.emit(ast, registry, tag).source();
            GpuDfcDelegate delegate = new GpuDfcDelegate(null);   // resolved at endBatch
            batch.add(new Pending(ast, registry, tag, generated, fp32, delegate));
            return delegate;
        } catch (UnsupportedDfNodeException e) {
            cpuCount.incrementAndGet();
            // Hygiene: this was previously a SILENT null (the DF just vanished onto the
            // CPU path). Router DFs that fail to GPU-compile are exactly what the boot
            // log needs to surface — id + reason at INFO.
            LOGGER.info("[SuperChunk-GPU] router DF {} (batch #{}) did not GPU-compile — unsupported node: {}. CPU-only for this DF.",
                    describeDf(ast), batch.size(), e.getMessage());
            return null;
        } catch (Throwable t) {
            cpuCount.incrementAndGet();
            LOGGER.info("[SuperChunk-GPU] router DF {} (batch #{}) emit threw — CPU-only for this DF: {}",
                    describeDf(ast), batch.size(), String.valueOf(t));
            return null;
        }
    }

    /** Best-available DF identity for failure logs: the AST root's shape (DFs carry no registry name here). */
    private static String describeDf(AstNode ast) {
        return ast == null ? "<null-ast>" : ast.getClass().getSimpleName();
    }

    /**
     * Only DFs whose generated body is at most this many chars are MERGED. Above it, a DF builds
     * as its own standalone program. Rationale (measured): {@code clBuildProgram} is super-linear
     * in program size, so a large-bodied DF (the spline-heavy composites — finalDensity,
     * overworld_composite, depth, ...) already dominates its own compile and sharing the ~460-line
     * headers saves nothing; folding it into a merged program just makes that program huge and the
     * compile blows up (merging ALL DFs did not finish in 8 min). The WIN is real only for the
     * MANY small/medium DFs, which are header-dominated — merging those amortizes the header JIT.
     */
    private static final int MERGE_BODY_MAX = 4_000;
    /** Total merged-body chars per group (small bodies only -> stays in the near-linear regime). */
    private static final int MERGE_GROUP_CHARS = 16_000;
    /** Hard cap on DFs per group. */
    private static final int MERGE_GROUP_MAX = 24;

    private static void buildMergedAndResolve(List<Pending> batch) {
        String headers = GpuDensityFunction.kernelHeaders();
        if (headers == null) {
            resolvePerDf(batch);
            return;
        }
        boolean fp32 = batch.get(0).fp32();
        String opts = fp32 ? "-DUSE_FP32" : "";
        // Split: small-bodied DFs MERGE (header-sharing win, near-linear); large-bodied DFs stay
        // per-DF (their compile is body-bound and super-linear — merging them regresses).
        List<Pending> small = new ArrayList<>();
        List<Pending> large = new ArrayList<>();
        for (Pending p : batch) {
            (p.generated().length() <= MERGE_BODY_MAX ? small : large).add(p);
        }
        long t0 = System.nanoTime();
        int mergedDFs = 0, groups = 0, i = 0;
        while (i < small.size()) {
            int j = i, sz = 0;
            while (j < small.size() && (j == i
                    || (sz + small.get(j).generated().length() <= MERGE_GROUP_CHARS && (j - i) < MERGE_GROUP_MAX))) {
                sz += small.get(j).generated().length();
                j++;
            }
            mergedDFs += buildGroup(headers, opts, small.subList(i, j));
            groups++;
            i = j;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        LOGGER.info("[SuperChunk-GPU] kernel merge: {} small DFs merged into {} group(s) "
                        + "(shared headers compiled {}x not {}x) in {} ms; {} large DFs build per-DF (body-bound).",
                mergedDFs, groups, groups, small.size(), ms, large.size());
        // The large-bodied DFs build standalone (unchanged per-DF path).
        resolvePerDf(large);
    }

    /** Builds ONE merged program for {@code group} (headers + each DF's tagged body); returns DFs served. */
    private static int buildGroup(String headers, String opts, List<Pending> group) {
        StringBuilder sb = new StringBuilder(headers.length() + group.size() * 2048).append(headers).append('\n');
        for (Pending p : group) {
            sb.append(p.generated()).append('\n');
        }
        String src = sb.toString();
        CLProgram probe = CLProgramCache.getOrBuild(src, opts);   // build the group ONCE
        if (probe == null) {
            LOGGER.warn("[SuperChunk-GPU] merged group build failed ({} DFs) — per-DF fallback for it.", group.size());
            resolvePerDf(group);
            return 0;
        }
        probe.close();   // release probe ref; compileShared re-getOrBuild dedup-hits the group program
        int merged = 0;
        for (Pending p : group) {
            GpuDensityFunction gpu = GpuDensityFunction.compileShared(src, p.tag(), p.registry(), p.fp32(), p.ast());
            if (gpu != null) {
                p.delegate().resolve(gpu);
                gpuCount.incrementAndGet();
                merged++;
            } else {
                cpuCount.incrementAndGet();
                String reason = GpuDensityFunction.takeLastCompileFailure();
                LOGGER.info("[SuperChunk-GPU] router DF {} ({}) did not resolve from the merged program — {}. CPU-only for this DF.",
                        p.ast() == null ? "<null-ast>" : p.ast().getClass().getSimpleName(), p.tag(),
                        reason != null ? reason : "reason unavailable");
            }
        }
        return merged;
    }

    /** Fallback: build each batched DF as its own standalone program (today's behaviour). */
    private static void resolvePerDf(List<Pending> batch) {
        for (Pending p : batch) {
            if (p.delegate().gpu() != null) continue;   // already resolved (endBatch catch re-entry): don't recompile/leak its CL resources
            try {
                GpuDensityFunction gpu = GpuDensityFunction.compile(p.ast());
                if (gpu != null) {
                    p.delegate().resolve(gpu);
                    gpuCount.incrementAndGet();
                } else {
                    cpuCount.incrementAndGet();
                    String reason = GpuDensityFunction.takeLastCompileFailure();
                    LOGGER.info("[SuperChunk-GPU] router DF {} ({}) did not GPU-compile — {}. CPU-only for this DF.",
                            describeDf(p.ast()), p.tag(), reason != null ? reason : "reason unavailable");
                }
            } catch (Throwable t) {
                cpuCount.incrementAndGet();
                LOGGER.info("[SuperChunk-GPU] router DF {} ({}) GPU build threw — CPU-only for this DF: {}",
                        describeDf(p.ast()), p.tag(), String.valueOf(t));
            }
        }
    }

    /**
     * Builds a {@link GpuDfcDelegate} from {@code ast} when the GPU backend is
     * enabled + available, else returns {@code null}. During a batch the build is
     * DEFERRED (the delegate resolves at {@link #endBatch()}); otherwise it builds
     * the DF's own program immediately. Used by the cache-postprocessing codegen
     * ({@code CacheLikeNode}) to attach a GPU delegate to the generated wrapper
     * {@link SubCompiledDensityFunction}. Never throws.
     */
    public static GpuDfcDelegate tryBuildDelegate(AstNode ast) {
        if (!isEnabled() || ast == null) {
            return null;
        }
        List<Pending> batch = BATCH.get();
        if (batch != null) {
            return enqueue(batch, ast);   // deferred; null = unsupported -> CPU
        }
        try {
            GpuDensityFunction gpu = GpuDensityFunction.compile(ast);
            if (gpu == null) {
                cpuCount.incrementAndGet();
                String reason = GpuDensityFunction.takeLastCompileFailure();
                LOGGER.info("[SuperChunk-GPU] cache-wrapper DF {} did not GPU-compile — {}. CPU-only for this DF.",
                        describeDf(ast), reason != null ? reason : "reason unavailable");
                return null;
            }
            int g = gpuCount.incrementAndGet();
            LOGGER.info("[SuperChunk-GPU] cache-wrapper DF compiled to GPU (#{}, fp32={}). Total GPU/CPU = {}/{}",
                    g, gpu.isFp32(), g, cpuCount.get());
            return new GpuDfcDelegate(gpu);
        } catch (Throwable t) {
            cpuCount.incrementAndGet();
            LOGGER.info("[SuperChunk-GPU] cache-wrapper DF {} GPU build threw — CPU-only for this DF: {}",
                    describeDf(ast), String.valueOf(t));
            return null;
        }
    }

    /**
     * Deterministic field-slot variant of {@link #tryBuildDelegate} for the
     * cache-postprocessing codegen ({@code CacheLikeNode}). Whenever GPU routing is
     * ENABLED for this run it NEVER returns null: a successful build yields the real
     * delegate; an unsupported or failed build (including a transient {@code
     * clBuildProgram} failure, or a batch member that doesn't resolve) yields a fresh
     * EMPTY delegate — inner gpu {@code null}, so {@link GpuDfcDelegate#fill} returns
     * false and the generated wrapper takes the CPU bytecode path, leaving density
     * values bit-identical.
     *
     * <p>This lets {@code CacheLikeNode} emit the GPU-delegate field as a deterministic
     * function of the AST SHAPE instead of the live build result, so the generated
     * class's field layout — and therefore its {@code <init>} constructor arg ordinals —
     * stays stable across a relaxed-AST compilation-cache HIT even when a build
     * succeeds during the cache-populating compile but fails on the later hit (or vice
     * versa). Each call returns a DISTINCT object so the per-DF {@code newField} slot is
     * never deduplicated away (a literal null would collapse every empty slot into one
     * shared field, changing the arg count and reintroducing the ordinal divergence).
     *
     * <p>Returns {@code null} ONLY when GPU routing is disabled for the whole run
     * (run-constant): the field is then omitted entirely and the generated class is the
     * unchanged pure-CPU form. Never throws.
     */
    public static GpuDfcDelegate tryBuildDelegateOrEmpty(AstNode ast) {
        if (!isEnabled()) {
            return null;   // GPU off this run -> no field slot (unchanged CPU layout)
        }
        GpuDfcDelegate delegate = tryBuildDelegate(ast);
        // Enabled but unsupported/failed: still emit the slot with a fresh empty
        // (CPU-fallback) delegate so field presence is a function of shape, not of the
        // live build result. A distinct instance keeps newField from deduping the slot.
        return delegate != null ? delegate : new GpuDfcDelegate(null);
    }

    /**
     * If the GPU backend is enabled + available, try to GPU-compile {@code ast}
     * and attach it to {@code compiled}. During a batch the build is deferred
     * (attached delegate resolves at {@link #endBatch()}). Never throws.
     */
    public static void maybeAttach(AstNode ast, SubCompiledDensityFunction compiled) {
        if (!isEnabled()) {
            return;
        }
        List<Pending> batch = BATCH.get();
        if (batch != null) {
            GpuDfcDelegate del = enqueue(batch, ast);
            if (del != null) {
                compiled.c2me$setGpuDelegate(del);
            }
            return;
        }
        try {
            GpuDensityFunction gpu = GpuDensityFunction.compile(ast);
            if (gpu != null) {
                compiled.c2me$setGpuDelegate(new GpuDfcDelegate(gpu));
                int g = gpuCount.incrementAndGet();
                LOGGER.info("[SuperChunk-GPU] DF compiled to GPU (#{}, fp32={}). Total GPU/CPU = {}/{}",
                        g, gpu.isFp32(), g, cpuCount.get());
            } else {
                int c = cpuCount.incrementAndGet();
                String reason = GpuDensityFunction.takeLastCompileFailure();
                LOGGER.info("[SuperChunk-GPU] router DF {} did not GPU-compile (#{}) — {}. Total GPU/CPU = {}/{}",
                        describeDf(ast), c, reason != null ? reason : "reason unavailable", gpuCount.get(), c);
            }
        } catch (Throwable t) {
            cpuCount.incrementAndGet();
            LOGGER.info("[SuperChunk-GPU] router DF {} GPU-attach threw — CPU-only for this DF: {}",
                    describeDf(ast), String.valueOf(t));
        }
    }

    /**
     * Cheap, allocation-free check used by the density-fill hot path to decide
     * whether to bother recording CPU-only (no-delegate) batches in
     * {@link GpuFillStats}. Returns the cached enabled flag; on the pure-CPU
     * production default this is a single volatile read so there is no measurable
     * overhead when the GPU is off.
     */
    public static boolean isRoutingEnabled() {
        Boolean e = enabled;
        return e != null && e;
    }
}
