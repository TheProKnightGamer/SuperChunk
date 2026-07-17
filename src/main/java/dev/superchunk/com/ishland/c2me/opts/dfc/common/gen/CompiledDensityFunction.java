package dev.superchunk.com.ishland.c2me.opts.dfc.common.gen;

import com.google.common.base.Suppliers;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IArrayCacheCapable;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IBlendingAwareVisitor;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.vif.EachApplierVanillaInterface;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.function.Supplier;

public class CompiledDensityFunction extends SubCompiledDensityFunction {

    private final CompiledEntry compiledEntry;

    public CompiledDensityFunction(CompiledEntry compiledEntry, DensityFunction blendingFallback) {
        super(compiledEntry, compiledEntry, blendingFallback);
        this.compiledEntry = Objects.requireNonNull(compiledEntry);
    }

    private CompiledDensityFunction(CompiledEntry compiledEntry, Supplier<DensityFunction> blendingFallback) {
        super(compiledEntry, compiledEntry, blendingFallback);
        this.compiledEntry = Objects.requireNonNull(compiledEntry);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        if (visitor instanceof IBlendingAwareVisitor blendingAwareVisitor && blendingAwareVisitor.c2me$isBlendingEnabled()) {
            DensityFunction fallback1 = this.getFallback();
            if (fallback1 == null) {
                throw new IllegalStateException("blendingFallback is no more");
            }
            return fallback1.mapAll(visitor);
        }
        boolean modified = false;
        List<Object> args = this.compiledEntry.getArgs();
        for (ListIterator<Object> iterator = args.listIterator(); iterator.hasNext(); ) {
            Object next = iterator.next();
            if (next instanceof DensityFunction df) {
                if (!(df instanceof IFastCacheLike)) {
                    DensityFunction applied = df.mapAll(visitor);
                    if (df != applied) {
                        iterator.set(applied);
                        modified = true;
                    }
                }
            }
            if (next instanceof DensityFunction.NoiseHolder noise) {
                DensityFunction.NoiseHolder applied = visitor.visitNoise(noise);
                if (noise != applied) {
                    iterator.set(applied);
                    modified = true;
                }
            }
        }

        for (ListIterator<Object> iterator = args.listIterator(); iterator.hasNext(); ) {
            Object next = iterator.next();
            if (next instanceof IFastCacheLike cacheLike) {
                DensityFunction applied = visitor.apply(cacheLike);
                if (applied == cacheLike.c2me$getDelegate()) {
                    iterator.set(null); // cache removed
                    modified = true;
                } else if (applied instanceof IFastCacheLike newCacheLike) {
                    iterator.set(newCacheLike);
                    modified = true;
                } else {
                    throw new UnsupportedOperationException("Unsupported transformation on Wrapping node");
                }
            }
        }

        Supplier<DensityFunction> fallback = this.blendingFallback != null ? Suppliers.memoize(() -> {
            DensityFunction densityFunction = this.blendingFallback.get();
            return densityFunction != null ? densityFunction.mapAll(visitor) : null;
        }) : null;
        if (fallback != this.blendingFallback) {
            modified = true;
        }
        if (modified) {
            CompiledDensityFunction mapped = new CompiledDensityFunction(this.compiledEntry.newInstance(args), fallback);
            // SuperChunk GPU (Stage 3b): NoiseChunk mapAll's the router per chunk to
            // rebind cache markers to its runtime caches. The GPU kernel inlines those
            // (pass-through) markers, so it is independent of that rebinding -> carry the
            // GPU delegate forward, otherwise every per-chunk sampler would lose GPU
            // routing (the delegate is only attached to the pre-mapAll instance).
            mapped.c2me$copyGpuDelegateFrom(this);
            return mapped;
        } else {
            return this;
        }
    }

}
