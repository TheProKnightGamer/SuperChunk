package dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstTransformer;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.BytecodeGen;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.vif.EachApplierVanillaInterface;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.InvocationShim;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.vif.NoisePosVanillaInterface;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

import java.util.Objects;

public class DelegateNode implements AstNode {

//    private static final ConcurrentHashMap<Class<?>, LongAdder> statistics = new ConcurrentHashMap<>();

    private final DensityFunction densityFunction;

    /**
     * SuperChunk GPU (Stage 3b): for cache-marker DelegateNodes built by
     * {@code McToAst} (which wrap a compiled density function we can't round-trip to
     * AST), this optionally carries the ORIGINAL uncompiled sub-tree AST. The
     * OpenCL emitter uses it to inline the cache marker (cache markers are pure
     * pass-through in the DFC, so this is bit-exact). Null for plain DelegateNodes.
     */
    private final AstNode superchunk$innerAst;

    public DelegateNode(DensityFunction densityFunction) {
        this(densityFunction, null);
    }

    public DelegateNode(DensityFunction densityFunction, AstNode superchunk$innerAst) {
        this.densityFunction = Objects.requireNonNull(densityFunction);
        this.superchunk$innerAst = superchunk$innerAst;
//        statistics.computeIfAbsent(densityFunction.getClass(), unused -> new LongAdder()).increment();
    }

    /** SuperChunk GPU: the original sub-tree AST for cache-marker delegates, or null. */
    public AstNode superchunk$getInnerAst() {
        return this.superchunk$innerAst;
    }

    @Override
    public double evalSingle(int x, int y, int z, EvalType type) {
        return densityFunction.compute(new NoisePosVanillaInterface(x, y, z, type));
    }

    @Override
    public void evalMulti(double[] res, int[] x, int[] y, int[] z, EvalType type) {
        if (res.length == 1) {
            res[0] = this.evalSingle(x[0], y[0], z[0], type);
            return;
        }
        densityFunction.fillArray(res, new EachApplierVanillaInterface(x, y, z, type));
    }

    @Override
    public AstNode[] getChildren() {
        return new AstNode[0];
    }

    @Override
    public AstNode transform(AstTransformer transformer) {
        return transformer.transform(this);
    }

    @Override
    public void doBytecodeGenSingle(BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        String newField = context.newField(DensityFunction.class, this.densityFunction);
        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, newField, Type.getDescriptor(DensityFunction.class));
        m.anew(Type.getType(NoisePosVanillaInterface.class));
        m.dup();
        m.load(1, Type.INT_TYPE);
        m.load(2, Type.INT_TYPE);
        m.load(3, Type.INT_TYPE);
        m.load(4, InstructionAdapter.OBJECT_TYPE);
        m.invokespecial(Type.getInternalName(NoisePosVanillaInterface.class), "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.getType(EvalType.class)), false);
        m.invokestatic(
                Type.getInternalName(InvocationShim.class),
                "invokeDensityFunctionSample",
                Type.getMethodDescriptor(Type.DOUBLE_TYPE, Type.getType(DensityFunction.class), Type.getType(DensityFunction.FunctionContext.class)),
                false
        );
        m.areturn(Type.DOUBLE_TYPE);
    }

    @Override
    public void doBytecodeGenMulti(BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        String newField = context.newField(DensityFunction.class, this.densityFunction);

        Label moreThanTwoLabel = new Label();

        m.load(1, InstructionAdapter.OBJECT_TYPE);
        m.arraylength();
        m.iconst(1);
        m.ificmpgt(moreThanTwoLabel);

        m.load(1, InstructionAdapter.OBJECT_TYPE);
        m.iconst(0);

        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, newField, Type.getDescriptor(DensityFunction.class));
        m.anew(Type.getType(NoisePosVanillaInterface.class));
        m.dup();
        m.load(2, InstructionAdapter.OBJECT_TYPE);
        m.iconst(0);
        m.aload(Type.INT_TYPE);
        m.load(3, InstructionAdapter.OBJECT_TYPE);
        m.iconst(0);
        m.aload(Type.INT_TYPE);
        m.load(4, InstructionAdapter.OBJECT_TYPE);
        m.iconst(0);
        m.aload(Type.INT_TYPE);
        m.load(5, InstructionAdapter.OBJECT_TYPE);
        m.invokespecial(Type.getInternalName(NoisePosVanillaInterface.class), "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.getType(EvalType.class)), false);
        m.invokestatic(
                Type.getInternalName(InvocationShim.class),
                "invokeDensityFunctionSample",
                Type.getMethodDescriptor(Type.DOUBLE_TYPE, Type.getType(DensityFunction.class), Type.getType(DensityFunction.FunctionContext.class)),
                false
        );

        m.astore(Type.DOUBLE_TYPE);
        m.areturn(Type.VOID_TYPE);

        m.visitLabel(moreThanTwoLabel);

        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, newField, Type.getDescriptor(DensityFunction.class));
        m.load(1, InstructionAdapter.OBJECT_TYPE);
        m.anew(Type.getType(EachApplierVanillaInterface.class));
        m.dup();
        m.load(2, InstructionAdapter.OBJECT_TYPE);
        m.load(3, InstructionAdapter.OBJECT_TYPE);
        m.load(4, InstructionAdapter.OBJECT_TYPE);
        m.load(5, InstructionAdapter.OBJECT_TYPE);
        m.load(6, InstructionAdapter.OBJECT_TYPE);
        m.invokespecial(Type.getInternalName(EachApplierVanillaInterface.class), "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(int[].class), Type.getType(int[].class), Type.getType(int[].class), Type.getType(EvalType.class), Type.getType(ArrayCache.class)), false);
        m.invokestatic(
                Type.getInternalName(InvocationShim.class),
                "invokeDensityFunctionFill",
                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(DensityFunction.class), Type.getType(double[].class), Type.getType(DensityFunction.ContextProvider.class)),
                false
        );
        m.areturn(Type.VOID_TYPE);
    }

    public DensityFunction getDelegate() {
        return this.densityFunction;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DelegateNode that = (DelegateNode) o;
        return Objects.equals(densityFunction, that.densityFunction);
    }

    @Override
    public int hashCode() {
        int result = 1;

        result = 31 * result + Objects.hashCode(this.getClass());
        result = 31 * result + Objects.hashCode(densityFunction);

        return result;
    }

    @Override
    public boolean relaxedEquals(AstNode o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DelegateNode that = (DelegateNode) o;
        return densityFunction.getClass() == that.densityFunction.getClass();
    }

    @Override
    public int relaxedHashCode() {
        int result = 1;

        result = 31 * result + Objects.hashCode(this.getClass());
        result = 31 * result + Objects.hashCode(densityFunction.getClass());

        return result;
    }
}
