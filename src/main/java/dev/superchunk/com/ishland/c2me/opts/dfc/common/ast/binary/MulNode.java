package dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.CacheLikeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.ConstantNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.BytecodeGen;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

public class MulNode extends AbstractBinaryNode {

    public MulNode(AstNode left, AstNode right) {
        super(left, right);
    }

    @Override
    protected AstNode newInstance(AstNode left, AstNode right) {
        return new MulNode(left, right);
    }

    @Override
    public double evalSingle(int x, int y, int z, EvalType type) {
        double evaled = this.left.evalSingle(x, y, z, type);
        return evaled == 0.0 ? 0.0 : evaled * this.right.evalSingle(x, y, z, type);
    }

    @Override
    public void evalMulti(double[] res, int[] x, int[] y, int[] z, EvalType type) {
        this.left.evalMulti(res, x, y, z, type);
        for (int i = 0; i < res.length; i++) {
            res[i] = res[i] == 0.0 ? 0.0 : res[i] * this.right.evalSingle(x[i], y[i], z[i], type);
        }
    }

    @Override
    public void doBytecodeGenSingle(BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        String leftMethod = context.newSingleMethod(this.left);
        String rightMethod = context.newSingleMethod(this.right);

        Label notZero = new Label();

        context.callDelegateSingle(m, leftMethod);
        m.dup2();
        m.dconst(0.0);
        m.cmpl(Type.DOUBLE_TYPE);
        m.ifne(notZero);
        m.dconst(0.0);
        m.areturn(Type.DOUBLE_TYPE);

        m.visitLabel(notZero);
        context.callDelegateSingle(m, rightMethod);
        m.mul(Type.DOUBLE_TYPE);
        m.areturn(Type.DOUBLE_TYPE);
    }

    /**
     * SuperChunk: {@code mul(constant, cache)} — vanilla's final density is
     * {@code mul(0.64, interpolated(...))} — used to read its cache once per element through the
     * single-point path (a call chain per block of every cell). With a non-zero constant vanilla
     * multiplies every element ({@code d == 0 ? 0 : d * arg2}), so read the cache in bulk when it
     * can answer exactly as the per-element reads would, then multiply {@code c * res[i]}.
     * Otherwise (or {@code -Dsuperchunk.dfc.mulBulk=false}) the code below runs unchanged.
     */
    private static final boolean BULK_CONSTANT_FACTOR =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.dfc.mulBulk", "true"));

    @Override
    public void doBytecodeGenMulti(BytecodeGen.Context context, InstructionAdapter m, BytecodeGen.Context.LocalVarConsumer localVarConsumer) {
        if (BULK_CONSTANT_FACTOR && this.left instanceof ConstantNode constant && constant.getValue() != 0.0
                && this.right instanceof CacheLikeNode cache && cache.getCacheLike() != null) {
            String cacheField = context.newField(IFastCacheLike.class, cache.getCacheLike());
            Label perElement = new Label();
            m.load(0, InstructionAdapter.OBJECT_TYPE);
            m.getfield(context.className, cacheField, Type.getDescriptor(IFastCacheLike.class));
            m.ifnull(perElement);
            m.load(0, InstructionAdapter.OBJECT_TYPE);
            m.getfield(context.className, cacheField, Type.getDescriptor(IFastCacheLike.class));
            m.load(1, InstructionAdapter.OBJECT_TYPE);
            m.load(2, InstructionAdapter.OBJECT_TYPE);
            m.load(3, InstructionAdapter.OBJECT_TYPE);
            m.load(4, InstructionAdapter.OBJECT_TYPE);
            m.load(5, InstructionAdapter.OBJECT_TYPE);
            m.invokeinterface(Type.getInternalName(IFastCacheLike.class), "c2me$getCachedPointwise",
                    Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(double[].class), Type.getType(int[].class),
                            Type.getType(int[].class), Type.getType(int[].class), Type.getType(EvalType.class)));
            m.ifeq(perElement);
            final double factor = constant.getValue();
            context.doCountedLoop(m, localVarConsumer, idx -> {
                m.load(1, InstructionAdapter.OBJECT_TYPE);
                m.load(idx, Type.INT_TYPE);
                m.dconst(factor);
                m.load(1, InstructionAdapter.OBJECT_TYPE);
                m.load(idx, Type.INT_TYPE);
                m.aload(Type.DOUBLE_TYPE);
                m.mul(Type.DOUBLE_TYPE);
                m.astore(Type.DOUBLE_TYPE);
            });
            m.areturn(Type.VOID_TYPE);
            m.visitLabel(perElement);
        }
        String leftMethod = context.newMultiMethod(this.left);
        String rightMethodSingle = context.newSingleMethod(this.right);
        context.callDelegateMulti(m, leftMethod);

        context.doCountedLoop(m, localVarConsumer, idx -> {
            Label minLabel = new Label();
            Label end = new Label();

            m.load(1, InstructionAdapter.OBJECT_TYPE);
            m.load(idx, Type.INT_TYPE);

            m.load(1, InstructionAdapter.OBJECT_TYPE);
            m.load(idx, Type.INT_TYPE);
            m.aload(Type.DOUBLE_TYPE);

            m.dup2();
            m.dconst(0.0);
            m.cmpl(Type.DOUBLE_TYPE);
            m.ifne(minLabel);
            m.pop2();
            m.dconst(0.0);
            m.goTo(end);

            m.visitLabel(minLabel);
            m.load(0, InstructionAdapter.OBJECT_TYPE);
            m.load(2, InstructionAdapter.OBJECT_TYPE);
            m.load(idx, Type.INT_TYPE);
            m.aload(Type.INT_TYPE);
            m.load(3, InstructionAdapter.OBJECT_TYPE);
            m.load(idx, Type.INT_TYPE);
            m.aload(Type.INT_TYPE);
            m.load(4, InstructionAdapter.OBJECT_TYPE);
            m.load(idx, Type.INT_TYPE);
            m.aload(Type.INT_TYPE);
            m.load(5, InstructionAdapter.OBJECT_TYPE);
            m.invokevirtual(context.className, rightMethodSingle, BytecodeGen.Context.SINGLE_DESC, false);
            m.mul(Type.DOUBLE_TYPE);

            m.visitLabel(end);
            m.astore(Type.DOUBLE_TYPE);
        });

        m.areturn(Type.VOID_TYPE);
    }

    @Override
    protected void bytecodeGenMultiBody(InstructionAdapter m, int idx, int res1) {
        throw new UnsupportedOperationException();
    }
}
