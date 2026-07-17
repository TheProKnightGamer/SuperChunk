package dev.superchunk.com.ishland.c2me.opts.dfc.common.vif;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.CacheLikeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.DelegateNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.BlendedNoiseNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Objects;

public class AstVanillaInterface implements DensityFunction {

    private final AstNode astNode;
    private final DensityFunction blendingFallback;

    public AstVanillaInterface(AstNode astNode, DensityFunction blendingFallback) {
        this.astNode = Objects.requireNonNull(astNode);
        this.blendingFallback = blendingFallback;
    }

    @Override
    public double compute(FunctionContext pos) {
        if (pos.getBlender() != Blender.empty()) {
            if (this.blendingFallback == null) {
                throw new IllegalStateException("blendingFallback is no more");
            }
            return this.blendingFallback.compute(pos);
        } else {
            return this.astNode.evalSingle(pos.blockX(), pos.blockY(), pos.blockZ(), EvalType.from(pos));
        }
    }

    @Override
    public void fillArray(double[] densities, ContextProvider applier) {
        if (applier instanceof NoiseChunk sampler) {
            if (sampler.getBlender() != Blender.empty()) {
                if (this.blendingFallback == null) {
                    throw new IllegalStateException("blendingFallback is no more");
                }
                this.blendingFallback.fillArray(densities, applier);
                return;
            }
        }
        if (applier instanceof EachApplierVanillaInterface vanillaInterface) {
            this.astNode.evalMulti(densities, vanillaInterface.getX(), vanillaInterface.getY(), vanillaInterface.getZ(), EvalType.from(applier));
            return;
        }

        int[] x = new int[densities.length];
        int[] y = new int[densities.length];
        int[] z = new int[densities.length];
        for (int i = 0; i < densities.length; i ++) {
            FunctionContext pos = applier.forIndex(i);
            x[i] = pos.blockX();
            y[i] = pos.blockY();
            z[i] = pos.blockZ();
        }
        this.astNode.evalMulti(densities, x, y, z, EvalType.from(applier));
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        AstNode transformed = this.astNode.transform(astNode -> {
            // SuperChunk GPU: preserve the typed BlendedNoiseNode across mapAll —
            // the generic DelegateNode arm below would downgrade it to a plain
            // (GPU-opaque) DelegateNode. Vanilla visitors return BlendedNoise
            // unchanged (or a re-seeded BlendedNoise via RandomState); a visitor
            // that maps it to anything else falls back to the opaque node, which
            // keeps the exact pre-existing CPU semantics.
            if (astNode instanceof BlendedNoiseNode blendedNoiseNode) {
                DensityFunction mapped = blendedNoiseNode.getBlendedNoise().mapAll(visitor);
                return mapped instanceof BlendedNoise blended
                        ? new BlendedNoiseNode(blended)
                        : new DelegateNode(mapped);
            }
            if (astNode instanceof DelegateNode delegateNode) {
                return new DelegateNode(delegateNode.getDelegate().mapAll(visitor));
            }
            if (astNode instanceof CacheLikeNode cacheLikeNode) {
                return new CacheLikeNode((IFastCacheLike) cacheLikeNode.getCacheLike().mapAll(visitor), cacheLikeNode.getDelegate());
            }
            return astNode;
        });
        DensityFunction blendingFallback1 = this.blendingFallback != null ? this.blendingFallback.mapAll(visitor) : null;
        if (transformed == this.astNode && blendingFallback1 == this.blendingFallback) {
            return this;
        } else {
            return new AstVanillaInterface(
                    transformed,
                    blendingFallback1
            );
        }
    }

    @Override
    public double minValue() {
        return this.blendingFallback.minValue();
    }

    @Override
    public double maxValue() {
        return this.blendingFallback.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        throw new UnsupportedOperationException();
    }

    public AstNode getAstNode() {
        return astNode;
    }

    public DensityFunction getBlendingFallback() {
        return blendingFallback;
    }
}
