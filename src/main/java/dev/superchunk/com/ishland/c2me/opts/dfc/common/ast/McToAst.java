package dev.superchunk.com.ishland.c2me.opts.dfc.common.ast;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.AddNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MaxNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MaxShortNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MinNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MinShortNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MulNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.CacheLikeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.ConstantNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.DelegateNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.RangeChoiceNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.YClampedGradientNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.BlendedNoiseNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTNoiseNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTShiftANode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTShiftBNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTShiftNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTWeirdScaledSamplerNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.ShiftedNoiseNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.spline.SplineAstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.AbsNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.CubeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.NegMulNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.SquareNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.SqueezeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.vif.AstVanillaInterface;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

import java.util.Objects;

public class McToAst {

//    private static final ConcurrentHashMap<Class<?>, LongAdder> delegateStatistics = new ConcurrentHashMap<>();

    // Forge 1.20.1 port: Java 17 has no switch patterns (JEP 441 is Java 21), so the
    // 1.21.1 pattern-switch is an instanceof chain here. Same dispatch order.
    public static AstNode toAst(DensityFunction df) {
        Objects.requireNonNull(df);
        if (df instanceof AstVanillaInterface f) {
            return f.getAstNode();
        } else if (df instanceof NoiseChunk.BlendAlpha) {
            return new ConstantNode(1.0);
        } else if (df instanceof NoiseChunk.BlendOffset) {
            return new ConstantNode(0.0);
        } else if (df instanceof DensityFunctions.BlendAlpha) {
            return new ConstantNode(1.0);
        } else if (df instanceof DensityFunctions.BlendOffset) {
            return new ConstantNode(0.0);
        } else if (df instanceof DensityFunctions.TwoArgumentSimpleFunction f) {
            return switch (f.type()) {
                case ADD -> new AddNode(toAst(f.argument1()), toAst(f.argument2()));
                case MUL -> new MulNode(toAst(f.argument1()), toAst(f.argument2()));
                case MIN -> {
                    double rightMin = f.argument2().minValue();
                    if (f.argument1().minValue() < rightMin) {
                        yield new MinShortNode(toAst(f.argument1()), toAst(f.argument2()), rightMin);
                    } else {
                        yield new MinNode(toAst(f.argument1()), toAst(f.argument2()));
                    }
                }
                case MAX -> {
                    double rightMax = f.argument2().maxValue();
                    if (f.argument1().maxValue() > rightMax) {
                        yield new MaxShortNode(toAst(f.argument1()), toAst(f.argument2()), rightMax);
                    } else {
                        yield new MaxNode(toAst(f.argument1()), toAst(f.argument2()));
                    }
                }
            };
        } else if (df instanceof DensityFunctions.BlendDensity f) {
            return toAst(f.input());
        } else if (df instanceof DensityFunctions.Clamp f) {
            return new MinNode(new ConstantNode(f.maxValue()), new MaxNode(new ConstantNode(f.minValue()), toAst(f.input())));
        } else if (df instanceof DensityFunctions.Constant f) {
            return new ConstantNode(f.value());
        } else if (df instanceof DensityFunctions.HolderHolder f) {
            return toAst(f.function().value());
        } else if (df instanceof DensityFunctions.Mapped f) {
            return switch (f.type()) {
                case ABS -> new AbsNode(toAst(f.input()));
                case SQUARE -> new SquareNode(toAst(f.input()));
                case CUBE -> new CubeNode(toAst(f.input()));
                case HALF_NEGATIVE -> new NegMulNode(toAst(f.input()), 0.5);
                case QUARTER_NEGATIVE -> new NegMulNode(toAst(f.input()), 0.25);
                case SQUEEZE -> new SqueezeNode(toAst(f.input()));
            };
        } else if (df instanceof DensityFunctions.RangeChoice f) {
            return new RangeChoiceNode(toAst(f.input()), f.minInclusive(), f.maxExclusive(), toAst(f.whenInRange()), toAst(f.whenOutOfRange()));
        } else if (df instanceof IFastCacheLike f) {
            return new CacheLikeNode(f, toAst(f.c2me$getDelegate()));
        } else if (df instanceof DensityFunctions.ShiftedNoise f) {
            return new ShiftedNoiseNode(toAst(f.shiftX()), toAst(f.shiftY()), toAst(f.shiftZ()), f.xzScale(), f.yScale(), f.noise());
        } else if (df instanceof DensityFunctions.Noise f) {
            return new DFTNoiseNode(f.noise(), f.xzScale(), f.yScale());
        } else if (df instanceof DensityFunctions.Shift f) {
            return new DFTShiftNode(f.offsetNoise());
        } else if (df instanceof DensityFunctions.ShiftA f) {
            return new DFTShiftANode(f.offsetNoise());
        } else if (df instanceof DensityFunctions.ShiftB f) {
            return new DFTShiftBNode(f.offsetNoise());
        } else if (df instanceof DensityFunctions.YClampedGradient f) {
            return new YClampedGradientNode(f.fromY(), f.toY(), f.fromValue(), f.toValue());
        } else if (df instanceof DensityFunctions.WeirdScaledSampler f) {
            return new DFTWeirdScaledSamplerNode(toAst(f.input()), f.noise(), f.rarityValueMapper());
        } else if (df instanceof DensityFunctions.Spline f) {
            return new SplineAstNode(f.spline());
        } else if (df instanceof BlendedNoise f) {
            // SuperChunk GPU: vanilla BlendedNoise ("old_blended_noise") IS a
            // DensityFunction (SimpleFunction) and used to fall into the opaque
            // default DelegateNode below, blocking GPU compilation of every DF
            // containing it (the router finalDensity / MAIN terrain grid). The
            // typed node keeps DelegateNode's CPU semantics (it IS one) and adds
            // GPU emission via df_blended_noise (OpenCLAstEmitter/NoiseRegistry).
            return new BlendedNoiseNode(f);
        } else {
//            delegateStatistics.computeIfAbsent(df.getClass(), unused -> new LongAdder()).increment();;
            return new DelegateNode(df);
        }
    }

    public static DensityFunction wrapVanilla(DensityFunction densityFunction) {
        return new AstVanillaInterface(McToAst.toAst(densityFunction), densityFunction);
    }

}
