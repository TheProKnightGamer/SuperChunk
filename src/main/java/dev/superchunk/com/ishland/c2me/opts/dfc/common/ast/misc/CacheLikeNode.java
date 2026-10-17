package dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstTransformer;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.BytecodeGen;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.IMultiMethod;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.ISingleMethod;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.SubCompiledDensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

import java.util.Objects;

public class CacheLikeNode implements AstNode {

    private final IFastCacheLike cacheLike;
    private final AstNode delegate;

    public CacheLikeNode(IFastCacheLike cacheLike, AstNode delegate) {
        this.cacheLike = cacheLike;
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public double evalSingle(int x, int y, int z, EvalType type) {
        if (this.cacheLike == null) {
            return this.delegate.evalSingle(x, y, z, type);
        }
        double cached = this.cacheLike.c2me$getCached(x, y, z, type);
        if (Double.doubleToRawLongBits(cached) != IFastCacheLike.CACHE_MISS_NAN_BITS) {
            return cached;
        } else {
            double eval = this.delegate.evalSingle(x, y, z, type);
            this.cacheLike.c2me$cache(x, y, z, type, eval);
            return eval;
        }
    }

    @Override
    public void evalMulti(double[] res, int[] x, int[] y, int[] z, EvalType type) {
        if (this.cacheLike == null) {
            this.delegate.evalMulti(res, x, y, z, type);
            return;
        }
        boolean cached = this.cacheLike.c2me$getCached(res, x, y, z, type);
        if (!cached) {
            this.delegate.evalMulti(res, x, y, z, type);
            this.cacheLike.c2me$cache(res, x, y, z, type);
        }
    }

    @Override
    public AstNode[] getChildren() {
        return new AstNode[]{this.delegate};
    }

    @Override
    public AstNode transform(AstTransformer transformer) {
        AstNode delegate = this.delegate.transform(transformer);
        if (this.delegate == delegate) {
            return transformer.transform(this);
        } else {
            return transformer.transform(new CacheLikeNode(this.cacheLike, delegate));
        }
    }

    @Override
    public void doBytecodeGenSingle(BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        String delegateMethod = context.newSingleMethod(this.delegate);
        String cacheLikeField = context.newField(IFastCacheLike.class, this.cacheLike);
        genPostprocessingMethod(context, cacheLikeField);

        int eval = localVarConsumer.createLocalVariable("eval", Type.DOUBLE_TYPE.getDescriptor());

        Label cacheExists = new Label();
        Label cacheMiss = new Label();

        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));
        m.ifnonnull(cacheExists);
        context.callDelegateSingle(m, delegateMethod);
        m.areturn(Type.DOUBLE_TYPE);

        m.visitLabel(cacheExists);
        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));
        m.load(1, Type.INT_TYPE);
        m.load(2, Type.INT_TYPE);
        m.load(3, Type.INT_TYPE);
        m.load(4, InstructionAdapter.OBJECT_TYPE);
        m.invokeinterface(Type.getInternalName(IFastCacheLike.class), "c2me$getCached", Type.getMethodDescriptor(Type.DOUBLE_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.getType(EvalType.class)));
        m.dup2();
        m.invokestatic(Type.getInternalName(Double.class), "doubleToRawLongBits", Type.getMethodDescriptor(Type.LONG_TYPE, Type.DOUBLE_TYPE), false);
        m.lconst(IFastCacheLike.CACHE_MISS_NAN_BITS);
        m.lcmp();
        m.ifeq(cacheMiss); // operand1 == operand2, branched with cache res
        m.areturn(Type.DOUBLE_TYPE);

        m.visitLabel(cacheMiss);
        m.pop2();

        context.callDelegateSingle(m, delegateMethod);
        m.store(eval, Type.DOUBLE_TYPE);
        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));
        m.load(1, Type.INT_TYPE);
        m.load(2, Type.INT_TYPE);
        m.load(3, Type.INT_TYPE);
        m.load(4, InstructionAdapter.OBJECT_TYPE);
        m.load(eval, Type.DOUBLE_TYPE);
        m.invokeinterface(Type.getInternalName(IFastCacheLike.class), "c2me$cache", Type.getMethodDescriptor(Type.VOID_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.INT_TYPE, Type.getType(EvalType.class), Type.DOUBLE_TYPE));

        m.load(eval, Type.DOUBLE_TYPE);
        m.areturn(Type.DOUBLE_TYPE);
    }

    @Override
    public void doBytecodeGenMulti(BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        String delegateMethod = context.newMultiMethod(this.delegate);
        String cacheLikeField = context.newField(IFastCacheLike.class, this.cacheLike);

        genPostprocessingMethod(context, cacheLikeField);

        Label cacheExists = new Label();
        Label cacheMiss = new Label();

        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));
        m.ifnonnull(cacheExists);
        context.callDelegateMulti(m, delegateMethod);
        m.areturn(Type.VOID_TYPE);

        m.visitLabel(cacheExists);
        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));
        m.load(1, InstructionAdapter.OBJECT_TYPE);
        m.load(2, InstructionAdapter.OBJECT_TYPE);
        m.load(3, InstructionAdapter.OBJECT_TYPE);
        m.load(4, InstructionAdapter.OBJECT_TYPE);
        m.load(5, InstructionAdapter.OBJECT_TYPE);
        m.invokeinterface(Type.getInternalName(IFastCacheLike.class), "c2me$getCached", Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(double[].class), Type.getType(int[].class), Type.getType(int[].class), Type.getType(int[].class), Type.getType(EvalType.class)));
        m.ifeq(cacheMiss);
        m.areturn(Type.VOID_TYPE);

        m.visitLabel(cacheMiss);
        context.callDelegateMulti(m, delegateMethod);
        m.load(0, InstructionAdapter.OBJECT_TYPE);
        m.getfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));
        m.load(1, InstructionAdapter.OBJECT_TYPE);
        m.load(2, InstructionAdapter.OBJECT_TYPE);
        m.load(3, InstructionAdapter.OBJECT_TYPE);
        m.load(4, InstructionAdapter.OBJECT_TYPE);
        m.load(5, InstructionAdapter.OBJECT_TYPE);
        m.invokeinterface(Type.getInternalName(IFastCacheLike.class), "c2me$cache", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(double[].class), Type.getType(int[].class), Type.getType(int[].class), Type.getType(int[].class), Type.getType(EvalType.class)));
        m.areturn(Type.VOID_TYPE);
    }

    private void genPostprocessingMethod(BytecodeGen.Context context, String cacheLikeField) {
        String methodName = String.format("postProcessing_%s", cacheLikeField);
        String delegateSingle = context.newSingleMethod(this.delegate);
        String delegateMulti = context.newMultiMethod(this.delegate);
        context.genPostprocessingMethod(methodName, m -> {
            Label cacheExists = new Label();

            m.load(0, InstructionAdapter.OBJECT_TYPE);

            {
                m.load(0, InstructionAdapter.OBJECT_TYPE);
                m.getfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));
                m.dup();
                m.ifnonnull(cacheExists);
                m.pop();
                m.pop();
                m.areturn(Type.VOID_TYPE);

                m.visitLabel(cacheExists);

                {
                    m.anew(Type.getType(SubCompiledDensityFunction.class));
                    m.dup();

                    m.load(0, InstructionAdapter.OBJECT_TYPE);
                    m.invokedynamic(
                            "evalSingle",
                            Type.getMethodDescriptor(Type.getType(ISingleMethod.class), Type.getType(context.classDesc)),
                            new Handle(
                                    Opcodes.H_INVOKESTATIC,
                                    "java/lang/invoke/LambdaMetafactory",
                                    "metafactory",
                                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                                    false
                            ),
                            new Object[]{
                                    Type.getMethodType(BytecodeGen.Context.SINGLE_DESC),
                                    new Handle(
                                            Opcodes.H_INVOKEVIRTUAL,
                                            context.className,
                                            delegateSingle,
                                            BytecodeGen.Context.SINGLE_DESC,
                                            false
                                    ),
                                    Type.getMethodType(BytecodeGen.Context.SINGLE_DESC)
                            }
                    );

                    m.load(0, InstructionAdapter.OBJECT_TYPE);
                    m.invokedynamic(
                            "evalMulti",
                            Type.getMethodDescriptor(Type.getType(IMultiMethod.class), Type.getType(context.classDesc)),
                            new Handle(
                                    Opcodes.H_INVOKESTATIC,
                                    "java/lang/invoke/LambdaMetafactory",
                                    "metafactory",
                                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                                    false
                            ),
                            new Object[]{
                                    Type.getMethodType(BytecodeGen.Context.MULTI_DESC),
                                    new Handle(
                                            Opcodes.H_INVOKEVIRTUAL,
                                            context.className,
                                            delegateMulti,
                                            BytecodeGen.Context.MULTI_DESC,
                                            false
                                    ),
                                    Type.getMethodType(BytecodeGen.Context.MULTI_DESC)
                            }
                    );

                    m.load(0, InstructionAdapter.OBJECT_TYPE);
                    m.getfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));
                    m.checkcast(Type.getType(DensityFunction.class));

                    m.invokespecial(
                            Type.getInternalName(SubCompiledDensityFunction.class),
                            "<init>",
                            Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(ISingleMethod.class), Type.getType(IMultiMethod.class), Type.getType(DensityFunction.class)),
                            false
                    );

                    // SuperChunk GPU (Stage 3b): THIS generated SubCompiledDensityFunction
                    // is the wrapper the live chunk-noise path actually calls fillArray on
                    // (it is stored back into the runtime cache via c2me$withDelegate). Its
                    // evalMulti computes exactly this.delegate, so a GPU delegate built from
                    // this.delegate is bit-exact. Attach it here -- this is what finally
                    // routes live worldgen density fills onto the GPU.
                    //
                    // DETERMINISTIC FIELD SLOT (world-load crash fix): the GPU-delegate
                    // field must be emitted as a function of the AST SHAPE, never of the
                    // live clBuildProgram result. compile0 reuses the cached class for any
                    // relaxed-equal node and re-runs THIS codegen pass only to collect the
                    // `args` list, whose ordinals come from the order newField is called;
                    // the cached class's <init> then checkcasts each list.get(ordinal) to
                    // the field type recorded when the class was first defined. If field
                    // presence flipped with a transient GPU build success/failure between
                    // the cache-populating compile and a later relaxed-equal cache HIT, the
                    // arg ordinals would shift and those checkcasts would throw
                    // ClassCastException, aborting NoiseRouter construction / world load.
                    //
                    // So whenever GPU routing is enabled this run we ALWAYS emit the slot:
                    // tryBuildDelegateOrEmpty returns the real delegate on a successful
                    // build and a non-null EMPTY delegate (inner gpu == null) otherwise. An
                    // empty delegate's fill() returns false -> the CPU evalMulti path runs,
                    // so density values stay bit-identical. It returns null ONLY when GPU
                    // routing is disabled for the whole run (run-constant) -> no field at
                    // all, the unchanged pure-CPU layout. A distinct delegate object per
                    // node (not a literal null) is required: newField dedups by value
                    // identity and would collapse every null-valued slot into ONE shared
                    // field, changing the arg count and reintroducing the divergence.
                    dev.superchunk.gpu.dfc.GpuDfcDelegate scGpuDelegate =
                            dev.superchunk.gpu.dfc.GpuDfcHook.tryBuildDelegateOrEmpty(this.delegate);
                    if (scGpuDelegate != null) {
                        String gpuField = context.newField(dev.superchunk.gpu.dfc.GpuDfcDelegate.class, scGpuDelegate);
                        m.dup(); // dup the SubCompiledDensityFunction reference
                        m.load(0, InstructionAdapter.OBJECT_TYPE);
                        m.getfield(context.className, gpuField, Type.getDescriptor(dev.superchunk.gpu.dfc.GpuDfcDelegate.class));
                        m.invokevirtual(
                                Type.getInternalName(SubCompiledDensityFunction.class),
                                "c2me$setGpuDelegate",
                                Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(dev.superchunk.gpu.dfc.GpuDfcDelegate.class)),
                                false
                        );
                    }

                    m.checkcast(Type.getType(DensityFunction.class));
                }

                m.invokeinterface(
                        Type.getInternalName(IFastCacheLike.class),
                        "c2me$withDelegate",
                        Type.getMethodDescriptor(Type.getType(DensityFunction.class), Type.getType(DensityFunction.class))
                );
            }

            m.putfield(context.className, cacheLikeField, Type.getDescriptor(IFastCacheLike.class));

            m.areturn(Type.VOID_TYPE);
        });
    }

    public IFastCacheLike getCacheLike() {
        return cacheLike;
    }

    public AstNode getDelegate() {
        return delegate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheLikeNode that = (CacheLikeNode) o;
        return equals(cacheLike, that.cacheLike) && Objects.equals(delegate, that.delegate);
    }

    private static boolean equals(IFastCacheLike a, IFastCacheLike b) {
        if ((Object) a instanceof DensityFunctions.Marker wrappingA && (Object) b instanceof DensityFunctions.Marker wrappingB) {
            return wrappingA.type() == wrappingB.type();
        } else {
            return a.equals(b);
        }
    }

    @Override
    public int hashCode() {
        int result = 1;

        result = 31 * result + this.getClass().hashCode();
        result = 31 * result + hashCode(cacheLike);
        result = 31 * result + delegate.hashCode();

        return result;
    }

    private static int hashCode(IFastCacheLike o) {
        if ((Object) o instanceof DensityFunctions.Marker wrapping) {
            return wrapping.type().hashCode();
        } else {
            return o.hashCode();
        }
    }

    @Override
    public boolean relaxedEquals(AstNode o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheLikeNode that = (CacheLikeNode) o;
        return relaxedEquals(cacheLike, that.cacheLike) && delegate.relaxedEquals(that.delegate);
    }

    private static boolean relaxedEquals(IFastCacheLike a, IFastCacheLike b) {
        if ((Object) a instanceof DensityFunctions.Marker wrappingA && (Object) b instanceof DensityFunctions.Marker wrappingB) {
            return wrappingA.type() == wrappingB.type();
        } else {
            return a.getClass() == b.getClass();
        }
    }

    @Override
    public int relaxedHashCode() {
        int result = 1;

        result = 31 * result + this.getClass().hashCode();
        result = 31 * result + relaxedHashCode(this.cacheLike);
        result = 31 * result + delegate.relaxedHashCode();

        return result;
    }

    private static int relaxedHashCode(IFastCacheLike o) {
        if ((Object) o instanceof DensityFunctions.Marker wrapping) {
            return wrapping.type().hashCode();
        } else {
            return o.getClass().hashCode();
        }
    }
}
