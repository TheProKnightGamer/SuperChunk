package dev.superchunk.com.ishland.c2me.opts.dfc.common.gen;

import com.google.common.base.Suppliers;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IArrayCacheCapable;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IBlendingAwareVisitor;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.ICoordinatesFilling;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.vif.EachApplierVanillaInterface;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

public class SubCompiledDensityFunction implements DensityFunction {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubCompiledDensityFunction.class);

    private final ISingleMethod singleMethod;
    private final IMultiMethod multiMethod;
    protected final Supplier<DensityFunction> blendingFallback;

    /**
     * SuperChunk GPU backend (Stage 2): an optional GPU-compiled form of this
     * density function. When non-null (and gpu enabled + available), the batch
     * {@link #fillArray} path routes to it, falling back to the CPU bytecode
     * {@code multiMethod} if the GPU eval fails. Set additively after construction
     * via {@link #c2me$setGpuDelegate}; left null for the unchanged CPU path.
     */
    private volatile dev.superchunk.gpu.dfc.GpuDfcDelegate gpuDelegate;

    /**
     * SuperChunk COMPACT-IDS (Stage 5): the C2ME AST this DF was compiled from,
     * stashed additively by {@code BytecodeGen.compile} on the router-level compile
     * result. {@code CompactIds.captureRouter} reads it to capture a dimension's
     * route WITHOUT requiring the whole DF to also GPU-compile (the full
     * finalDensity never does — its tree contains McToAst-opaque nodes like vanilla
     * {@code BlendedNoise}; only its interpolated subtrees get GPU forms). Purely a
     * stored reference: never read outside the compact-ids probe, no behavior change.
     */
    private volatile dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode sourceAst;

    /** SuperChunk: stash the compile-source AST (see {@link #sourceAst}). */
    public void c2me$setSourceAst(dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode ast) {
        this.sourceAst = ast;
    }

    /** SuperChunk: the compile-source AST, or {@code null} (mapAll copies do not carry it). */
    public dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode c2me$getSourceAst() {
        return this.sourceAst;
    }

    /** SuperChunk: attach a GPU-compiled form (see {@link #gpuDelegate}). */
    public void c2me$setGpuDelegate(dev.superchunk.gpu.dfc.GpuDfcDelegate delegate) {
        this.gpuDelegate = delegate;
    }

    /**
     * SuperChunk: the attached GPU-compiled form, or {@code null} if this DF stayed
     * CPU-only. Read by the biome-climate offload to recover the uncached climate
     * DFs' compiled GPU forms (and their ASTs) at the router build site / chunk seam.
     */
    public dev.superchunk.gpu.dfc.GpuDfcDelegate c2me$getGpuDelegate() {
        return this.gpuDelegate;
    }

    /**
     * SuperChunk: carry the GPU delegate from {@code other} onto this instance.
     * Used when {@code mapAll} produces a reshaped copy (per-chunk cache rebinding):
     * the GPU kernel inlines the pass-through cache markers and so is unaffected by
     * the rebinding, but the new instance would otherwise lose GPU routing.
     */
    public void c2me$copyGpuDelegateFrom(SubCompiledDensityFunction other) {
        if (other != null) {
            this.gpuDelegate = other.gpuDelegate;
        }
    }

    // also called from generated code
    public SubCompiledDensityFunction(ISingleMethod singleMethod, IMultiMethod multiMethod, DensityFunction blendingFallback) {
        this(singleMethod, multiMethod, unwrap(blendingFallback));
    }

    protected SubCompiledDensityFunction(ISingleMethod singleMethod, IMultiMethod multiMethod, Supplier<DensityFunction> blendingFallback) {
        this.singleMethod = Objects.requireNonNull(singleMethod);
        this.multiMethod = Objects.requireNonNull(multiMethod);
        this.blendingFallback = blendingFallback;
    }

    private static Supplier<DensityFunction> unwrap(DensityFunction densityFunction) {
        if (densityFunction instanceof SubCompiledDensityFunction scdf) {
            return scdf.blendingFallback;
        } else {
            return densityFunction != null ? Suppliers.ofInstance(densityFunction) : null;
        }
    }

    @Override
    public double compute(FunctionContext pos) {
        if (pos.getBlender() != Blender.empty()) {
            DensityFunction fallback = this.getFallback();
            if (fallback == null) {
                throw new IllegalStateException("blendingFallback is no more");
            }
            return fallback.compute(pos);
        } else {
            return this.singleMethod.evalSingle(pos.blockX(), pos.blockY(), pos.blockZ(), EvalType.from(pos));
        }
    }

    @Override
    public void fillArray(double[] densities, ContextProvider applier) {
        if (applier instanceof NoiseChunk sampler) {
            if (sampler.getBlender() != Blender.empty()) {
                DensityFunction fallback = this.getFallback();
                if (fallback == null) {
                    throw new IllegalStateException("blendingFallback is no more");
                }
                fallback.fillArray(densities, applier);
                return;
            }
        }
        if (applier instanceof EachApplierVanillaInterface vanillaInterface) {
            int[] vx = vanillaInterface.getX();
            int[] vy = vanillaInterface.getY();
            int[] vz = vanillaInterface.getZ();
            // SuperChunk GPU (Stage 2): try the GPU-compiled form first; on any
            // failure (or when no GPU delegate) use the CPU bytecode path.
            dev.superchunk.gpu.dfc.GpuDfcDelegate gpu = this.gpuDelegate;
            boolean gpuDone = false;
            if (gpu != null) {
                long t0 = System.nanoTime();
                gpuDone = gpu.fill(densities, vx, vy, vz);
                long attemptNanos = System.nanoTime() - t0;
                // Record the GPU attempt wall-time regardless of outcome so
                // gpuFillNanos+cpuFillNanos stays a true 100% of fill time. On a fall-back
                // (gpuDone==false) the time already burned in the failed attempt is wasted
                // overhead on the CPU path that must now redo the work via evalMulti below,
                // so charge it to CPU. (gpu!=null implies routing, so this matches the
                // recordCpuTime accounting in the !gpuDone block and adds no overhead on
                // the pure-CPU gpu==null default.)
                if (gpuDone) {
                    dev.superchunk.gpu.dfc.GpuFillStats.recordGpuTime(attemptNanos);
                } else {
                    dev.superchunk.gpu.dfc.GpuFillStats.recordCpuTime(attemptNanos);
                }
            }
            if (!gpuDone) {
                // gpu==null => this DF never GPU-compiled (e.g. CacheLikeNode); count it
                // as a CPU batch + time it, but ONLY when GPU routing is on (no overhead
                // on the pure-CPU production default).
                boolean routing = gpu != null || dev.superchunk.gpu.dfc.GpuDfcHook.isRoutingEnabled();
                if (gpu == null && routing) {
                    dev.superchunk.gpu.dfc.GpuFillStats.recordCpu(densities.length);
                }
                long t0 = routing ? System.nanoTime() : 0L;
                this.multiMethod.evalMulti(densities, vx, vy, vz, EvalType.from(applier), vanillaInterface.c2me$getArrayCache());
                if (routing) dev.superchunk.gpu.dfc.GpuFillStats.recordCpuTime(System.nanoTime() - t0);
            }
            return;
        }

        boolean scPooled = applier instanceof IArrayCacheCapable;
        ArrayCache cache = scPooled ? ((IArrayCacheCapable) applier).c2me$getArrayCache() : new ArrayCache();
        int[] x = cache.getIntArray(densities.length, false);
        int[] y = cache.getIntArray(densities.length, false);
        int[] z = cache.getIntArray(densities.length, false);
        if (applier instanceof ICoordinatesFilling coordinatesFilling) {
            coordinatesFilling.c2me$fillCoordinates(x, y, z);
        } else {
            for (int i = 0; i < densities.length; i ++) {
                FunctionContext pos = applier.forIndex(i);
                x[i] = pos.blockX();
                y[i] = pos.blockY();
                z[i] = pos.blockZ();
            }
        }
        // SuperChunk GPU (Stage 2): try GPU first, else CPU bytecode.
        dev.superchunk.gpu.dfc.GpuDfcDelegate gpu = this.gpuDelegate;
        boolean gpuDone = false;
        if (gpu != null) {
            long t0 = System.nanoTime();
            gpuDone = gpu.fill(densities, x, y, z);
            long attemptNanos = System.nanoTime() - t0;
            // Record the GPU attempt wall-time regardless of outcome so
            // gpuFillNanos+cpuFillNanos stays a true 100% of fill time. On a fall-back
            // (gpuDone==false) the time already burned in the failed attempt is wasted
            // overhead on the CPU path that must now redo the work via evalMulti below,
            // so charge it to CPU. (gpu!=null implies routing, so this matches the
            // recordCpuTime accounting in the !gpuDone block and adds no overhead on
            // the pure-CPU gpu==null default.)
            if (gpuDone) {
                dev.superchunk.gpu.dfc.GpuFillStats.recordGpuTime(attemptNanos);
            } else {
                dev.superchunk.gpu.dfc.GpuFillStats.recordCpuTime(attemptNanos);
            }
        }
        if (!gpuDone) {
            boolean routing = gpu != null || dev.superchunk.gpu.dfc.GpuDfcHook.isRoutingEnabled();
            if (gpu == null && routing) {
                dev.superchunk.gpu.dfc.GpuFillStats.recordCpu(densities.length);
            }
            long t0 = routing ? System.nanoTime() : 0L;
            this.multiMethod.evalMulti(densities, x, y, z, EvalType.from(applier), cache);
            if (routing) dev.superchunk.gpu.dfc.GpuFillStats.recordCpuTime(System.nanoTime() - t0);
        }
        // Recycle the coordinate arrays back to the per-applier pool (they were never recycled
        // before -> getIntArray was the #1 allocation site, ~78 GB/120s). Safe: both gpu.fill
        // (copies oversized inputs / consumes synchronously, exactly as branch 1 reuses vx/vy/vz)
        // and evalMulti have returned, so x/y/z are no longer referenced. Only when pooled
        // (per-applier cache); the throwaway `new ArrayCache()` is GC'd anyway.
        if (scPooled) {
            cache.recycle(x);
            cache.recycle(y);
            cache.recycle(z);
        }
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        if (this.getClass() != SubCompiledDensityFunction.class) {
            throw new AbstractMethodError();
        }
        if (visitor instanceof IBlendingAwareVisitor blendingAwareVisitor && blendingAwareVisitor.c2me$isBlendingEnabled()) {
            DensityFunction fallback1 = this.getFallback();
            if (fallback1 == null) {
                throw new IllegalStateException("blendingFallback is no more");
            }
            return fallback1.mapAll(visitor);
        }
        boolean modified = false;
        Supplier<DensityFunction> fallback = this.blendingFallback != null ? Suppliers.memoize(() -> {
            DensityFunction densityFunction = this.blendingFallback.get();
            return densityFunction != null ? densityFunction.mapAll(visitor) : null;
        }) : null;
        if (fallback != this.blendingFallback) {
            modified = true;
        }
        if (modified) {
            return new SubCompiledDensityFunction(this.singleMethod, this.multiMethod, fallback);
        } else {
            return this;
        }
    }

    @Override
    public double minValue() {
//        DensityFunction fallback = this.getFallback();
//        return fallback != null ? fallback.minValue() : Double.MIN_VALUE;
        return Double.MIN_VALUE;
    }

    @Override
    public double maxValue() {
//        DensityFunction fallback = this.getFallback();
//        return fallback != null ? fallback.maxValue() : Double.MAX_VALUE;
        return Double.MAX_VALUE;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        throw new UnsupportedOperationException();
    }

    protected DensityFunction getFallback() {
        return this.blendingFallback != null ? this.blendingFallback.get() : null;
    }
}
