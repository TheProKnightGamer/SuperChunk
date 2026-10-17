package dev.superchunk.worldgen;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.AddNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MaxNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MaxShortNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MinNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MinShortNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MulNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.CacheLikeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.ConstantNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.RangeChoiceNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.YClampedGradientNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.AbsNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.CubeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.NegMulNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.SquareNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.SqueezeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.CompiledDensityFunction;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.SubCompiledDensityFunction;
import dev.superchunk.gpu.dfc.GpuDfcDelegate;
import dev.superchunk.gpu.dfc.ScBeardBoxAccess;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cell sign skip in the CPU noise fill.
 *
 * <p>{@code NoiseChunk.selectCellYZ} fills the cell cache with the final density of every block of
 * the cell: vanilla's {@code add(finalDensity, beardifier)}, 128 evaluations for a 4x8x4 cell. In
 * vanilla's routers the per-block part of that expression is small arithmetic over interpolated
 * values ({@code min}, {@code squeeze}, {@code range_choice}, {@code abs}, constants), and an
 * interpolated value inside a cell is a trilinear blend of the cell's 8 corners, so it lies between
 * their minimum and maximum (up to the lerp's rounding, which the bound widens for). Evaluating the
 * expression over intervals bounds the final density of every block of the cell at once.
 *
 * <p>Only the sign is then needed:
 * <ul>
 *   <li>Entirely positive: {@code computeSubstance} returns {@code null} for any density
 *       {@code > 0} without reading its value, so every block takes the solid path.</li>
 *   <li>Entirely non-positive, and the aquifer's air predicate ({@link AirCells.AirPredicate})
 *       holds for the whole cell: every block is {@code AIR} through the aquifer's adaptive air
 *       branch, which does not read the value either. The air-cell skip then answers the cell.</li>
 * </ul>
 * In both cases the cell cache is filled with the bound (a value of the proven sign) and the
 * per-block evaluation is skipped. Every block comes out as before; mixed cells, cells that may
 * touch a structure's beard box, blending and any expression node this class does not know take
 * the original fill. GPU routing may stay on: a cell's fill is never served by the GPU (see
 * {@link #bind}), and the compact-ids hybrid fill's CPU cells all touch the beard box.
 *
 * <p>Kill switch {@code -Dsuperchunk.worldgen.cellSignSkip=false}. Verify
 * {@code -Dsuperchunk.worldgen.cellSignSkip.verify=true} skips nothing and checks every decided
 * cell's real values against its decision.
 */
public final class CellSignSkip {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.cellSignSkip", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.cellSignSkip.verify");
    public static final boolean METRICS = VERIFY || Boolean.getBoolean("superchunk.worldgen.cellSignSkip.metrics");

    public static final int UNDECIDED = 0;
    public static final int SOLID = 1;
    public static final int NON_POSITIVE = 2;

    /** Implemented on {@code NoiseChunk.NoiseInterpolator}: the current cell's 8 corner values. */
    public interface Corners {
        /** Writes the corners' minimum to {@code out[at]} and maximum to {@code out[at + 1]}. */
        void superchunk$cornerRange(double[] out, int at);
    }

    /** Implemented on {@code NoiseChunk}: the cell sign program and the proven-air hand-off. */
    public interface Chunk {
        Object superchunk$cellSignProgram();

        void superchunk$setCellSignProgram(Object program);

        /** True once per cell when this skip proved the cell air; clears the mark. */
        boolean superchunk$takeProvenAir();

        /** Whether this skip proved the current cell solid with no ore vein: every block is the default block. */
        boolean superchunk$provenSolid();

        /** The ore vein toggle this chunk's vein filler reads (captured in the constructor). */
        void superchunk$setVeinToggle(DensityFunction veinToggle);
    }

    private static final Object UNSUPPORTED = new Object();

    private static final AtomicLong CELLS = new AtomicLong();
    private static final AtomicLong SOLID_CELLS = new AtomicLong();
    private static final AtomicLong AIR_CELLS = new AtomicLong();
    private static final AtomicLong NON_POSITIVE_NOT_AIR = new AtomicLong();
    private static final AtomicLong VERIFIED_BLOCKS = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();

    private CellSignSkip() {
    }

    // ---- template: the per-block expression, compiled once per router-level function ----

    private static final int CONST = 0, INTERP = 1, ADD = 2, MUL = 3, MIN = 4, MAX = 5, MIN_SHORT = 6,
            MAX_SHORT = 7, ABS = 8, SQUARE = 9, CUBE = 10, NEG_MUL = 11, SQUEEZE = 12, RANGE = 13, Y_GRAD = 14;

    /** Post-order node arrays; {@code a}, {@code b}, {@code c} index children, {@code arg} the field. */
    private static final class Template {
        final Class<?> entryClass;
        final int[] op;
        final int[] a;
        final int[] b;
        final int[] c;
        final double[] k0;
        final double[] k1;
        final double[] k2;
        final double[] k3;
        final int[] interpArgs;

        Template(Class<?> entryClass, List<int[]> nodes, List<double[]> constants, int[] interpArgs) {
            this.entryClass = entryClass;
            int n = nodes.size();
            this.op = new int[n];
            this.a = new int[n];
            this.b = new int[n];
            this.c = new int[n];
            this.k0 = new double[n];
            this.k1 = new double[n];
            this.k2 = new double[n];
            this.k3 = new double[n];
            for (int i = 0; i < n; i++) {
                int[] node = nodes.get(i);
                double[] k = constants.get(i);
                this.op[i] = node[0];
                this.a[i] = node[1];
                this.b[i] = node[2];
                this.c[i] = node[3];
                this.k0[i] = k[0];
                this.k1[i] = k[1];
                this.k2[i] = k[2];
                this.k3[i] = k[3];
            }
            this.interpArgs = interpArgs;
        }
    }

    private static final class Builder {
        final IdentityHashMap<Object, Integer> argIndex;
        final java.util.HashMap<Object, Integer> equalIndex;
        final List<int[]> nodes = new ArrayList<>();
        final List<double[]> constants = new ArrayList<>();
        final List<Integer> interpArgs = new ArrayList<>();

        Builder(IdentityHashMap<Object, Integer> argIndex, java.util.HashMap<Object, Integer> equalIndex) {
            this.argIndex = argIndex;
            this.equalIndex = equalIndex;
        }

        private int add(int op, int a, int b, int c, double k0, double k1, double k2, double k3) {
            this.nodes.add(new int[]{op, a, b, c});
            this.constants.add(new double[]{k0, k1, k2, k3});
            return this.nodes.size() - 1;
        }

        /** The node index, or -1 when the subtree has a node this class does not bound. */
        int build(AstNode node) {
            if (node instanceof ConstantNode constant) {
                double v = constant.getValue();
                return Double.isFinite(v) ? add(CONST, -1, -1, -1, v, 0, 0, 0) : -1;
            }
            if (node instanceof CacheLikeNode cache) {
                Object cacheLike = cache.getCacheLike();
                // The compiled class's fields can hold an equal copy of the tree's marker. Equal
                // markers bind to the same per-chunk interpolator (NoiseChunk.wrap is keyed by equals).
                Integer arg = cacheLike == null ? null : this.argIndex.get(cacheLike);
                if (arg == null && cacheLike != null) {
                    arg = this.equalIndex.get(cacheLike);
                }
                if (arg == null) {
                    standDown("interpolator field not found: " + (cacheLike == null ? "null" : cacheLike.getClass().getName()));
                    return -1;
                }
                this.interpArgs.add(arg);
                return add(INTERP, this.interpArgs.size() - 1, -1, -1, 0, 0, 0, 0);
            }
            if (node instanceof YClampedGradientNode g) {
                if (!Double.isFinite(g.getFromValue()) || !Double.isFinite(g.getToValue())) {
                    return -1;
                }
                return add(Y_GRAD, -1, -1, -1, g.getFromY(), g.getToY(), g.getFromValue(), g.getToValue());
            }
            if (node instanceof RangeChoiceNode r) {
                int in = build(r.getInput());
                int yes = in < 0 ? -1 : build(r.getWhenInRange());
                int no = yes < 0 ? -1 : build(r.getWhenOutOfRange());
                return no < 0 ? -1 : add(RANGE, in, yes, no, r.getMinInclusive(), r.getMaxExclusive(), 0, 0);
            }
            // Exact classes only: a subclass could compute something else.
            Class<?> type = node.getClass();
            if (type == AddNode.class || type == MulNode.class || type == MinNode.class || type == MaxNode.class
                    || type == MinShortNode.class || type == MaxShortNode.class) {
                var binary = (dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.AbstractBinaryNode) node;
                int l = build(binary.getLeft());
                int r = l < 0 ? -1 : build(binary.getRight());
                if (r < 0) {
                    return -1;
                }
                if (type == AddNode.class) return add(ADD, l, r, -1, 0, 0, 0, 0);
                if (type == MulNode.class) return add(MUL, l, r, -1, 0, 0, 0, 0);
                if (type == MinNode.class) return add(MIN, l, r, -1, 0, 0, 0, 0);
                if (type == MaxNode.class) return add(MAX, l, r, -1, 0, 0, 0, 0);
                if (type == MinShortNode.class) return add(MIN_SHORT, l, r, -1, ((MinShortNode) node).getRightMin(), 0, 0, 0);
                return add(MAX_SHORT, l, r, -1, ((MaxShortNode) node).getRightMax(), 0, 0, 0);
            }
            if (type == AbsNode.class || type == SquareNode.class || type == CubeNode.class
                    || type == NegMulNode.class || type == SqueezeNode.class) {
                var unary = (dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.AbstractUnaryNode) node;
                int o = build(unary.getOperand());
                if (o < 0) {
                    return -1;
                }
                if (type == AbsNode.class) return add(ABS, o, -1, -1, 0, 0, 0, 0);
                if (type == SquareNode.class) return add(SQUARE, o, -1, -1, 0, 0, 0, 0);
                if (type == CubeNode.class) return add(CUBE, o, -1, -1, 0, 0, 0, 0);
                if (type == SqueezeNode.class) return add(SQUEEZE, o, -1, -1, 0, 0, 0, 0);
                double negMul = ((NegMulNode) node).getNegMul();
                return Double.isFinite(negMul) ? add(NEG_MUL, o, -1, -1, negMul, 0, 0, 0) : -1;
            }
            return -1; // noise, splines, delegates: not bounded here
        }
    }

    private static Object template(SubCompiledDensityFunction origin) {
        Object template = origin.superchunk$cellSignTemplate();
        if (template != null) {
            return template;
        }
        synchronized (origin) {
            template = origin.superchunk$cellSignTemplate();
            if (template == null) {
                template = buildTemplate(origin);
                origin.superchunk$setCellSignTemplate(template);
            }
            return template;
        }
    }

    private static Object buildTemplate(SubCompiledDensityFunction origin) {
        try {
            AstNode ast = origin.c2me$getSourceAst();
            if (ast == null || !(origin instanceof CompiledDensityFunction compiled)) {
                return UNSUPPORTED;
            }
            List<Object> args = compiled.superchunk$args();
            IdentityHashMap<Object, Integer> argIndex = new IdentityHashMap<>();
            java.util.HashMap<Object, Integer> equalIndex = new java.util.HashMap<>();
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.get(i);
                argIndex.putIfAbsent(arg, i);
                if (arg instanceof DensityFunctions.Marker) {
                    equalIndex.putIfAbsent(arg, i);
                }
            }
            Builder builder = new Builder(argIndex, equalIndex);
            int root = builder.build(ast);
            if (root < 0 || root != builder.nodes.size() - 1) {
                return UNSUPPORTED;
            }
            int[] interpArgs = builder.interpArgs.stream().mapToInt(Integer::intValue).toArray();
            return new Template(compiled.superchunk$entryClass(), builder.nodes, builder.constants, interpArgs);
        } catch (RuntimeException e) {
            return UNSUPPORTED;
        }
    }

    // ---- per-chunk program ----

    /** One chunk's binding of a template: its interpolators and beardifier, plus scratch arrays. */
    public static final class Program {
        private final Template t;
        private final Corners[] corners;
        private final ScBeardBoxAccess beard; // null: the beardifier is the constant-zero marker
        private final double[] lo;
        private final double[] hi;
        private final double[] range;
        private final int[] beardBox = new int[6];
        private boolean beardKnown;
        private boolean hasBeard;
        double bound;

        private Program(Template t, Corners[] corners, ScBeardBoxAccess beard) {
            this.t = t;
            this.corners = corners;
            this.beard = beard;
            this.lo = new double[t.op.length];
            this.hi = new double[t.op.length];
            this.range = new double[corners.length * 2];
        }

        /**
         * {@link #SOLID}, {@link #NON_POSITIVE} or {@link #UNDECIDED} for the cell whose minimum block
         * is (x0, y0, z0), {@code w} blocks wide and {@code h} tall. On a decision {@link #bound} holds
         * a value of the proven sign (the bound itself).
         */
        int classify(int x0, int y0, int z0, int w, int h) {
            if (this.beard != null && !beardFree(x0, y0, z0, w, h)) {
                return UNDECIDED;
            }
            if (!evaluate(y0, h)) {
                return UNDECIDED;
            }
            double l = this.lo[this.lo.length - 1];
            double u = this.hi[this.hi.length - 1];
            if (l > 0.0 && l < Double.POSITIVE_INFINITY) {
                this.bound = l;
                return SOLID;
            }
            if (u <= 0.0 && u > Double.NEGATIVE_INFINITY) {
                this.bound = u;
                return NON_POSITIVE;
            }
            return UNDECIDED;
        }

        /** Whether the expression's magnitude stays below {@code limit} for every block of the cell. */
        boolean below(int y0, int h, double limit) {
            if (!evaluate(y0, h)) {
                return false;
            }
            return Math.max(Math.abs(this.lo[this.lo.length - 1]), Math.abs(this.hi[this.hi.length - 1])) < limit;
        }

        /** Interval-evaluates the expression over the current cell into lo/hi; false on NaN. */
        private boolean evaluate(int y0, int h) {
            final Template t = this.t;
            final double[] lo = this.lo;
            final double[] hi = this.hi;
            final double[] range = this.range;
            for (int i = 0; i < this.corners.length; i++) {
                this.corners[i].superchunk$cornerRange(range, i * 2);
            }
            final int n = t.op.length;
            for (int i = 0; i < n; i++) {
                double l;
                double u;
                switch (t.op[i]) {
                    case CONST -> {
                        l = t.k0[i];
                        u = l;
                    }
                    case INTERP -> {
                        // lerp3 of the 8 corners stays within their range; each of its three nested
                        // lerps can round past an endpoint by a few ulps of the largest magnitude.
                        double cl = range[t.a[i] * 2];
                        double cu = range[t.a[i] * 2 + 1];
                        double slack = Math.max(Math.abs(cl), Math.abs(cu)) * 0x1p-44;
                        l = cl - slack;
                        u = cu + slack;
                    }
                    case ADD -> {
                        l = lo[t.a[i]] + lo[t.b[i]];
                        u = hi[t.a[i]] + hi[t.b[i]];
                    }
                    case MUL -> {
                        // evaled == 0 ? 0 : evaled * right; 0 is inside the product's range whenever
                        // the left interval contains it, so the plain product bound covers both.
                        double al = lo[t.a[i]], au = hi[t.a[i]], bl = lo[t.b[i]], bu = hi[t.b[i]];
                        double p1 = al * bl, p2 = al * bu, p3 = au * bl, p4 = au * bu;
                        l = Math.min(Math.min(p1, p2), Math.min(p3, p4));
                        u = Math.max(Math.max(p1, p2), Math.max(p3, p4));
                    }
                    case MIN -> {
                        l = Math.min(lo[t.a[i]], lo[t.b[i]]);
                        u = Math.min(hi[t.a[i]], hi[t.b[i]]);
                    }
                    case MAX -> {
                        l = Math.max(lo[t.a[i]], lo[t.b[i]]);
                        u = Math.max(hi[t.a[i]], hi[t.b[i]]);
                    }
                    case MIN_SHORT -> {
                        // left <= rightMin ? left : min(left, right): either left or min(left, right).
                        l = Math.min(lo[t.a[i]], lo[t.b[i]]);
                        u = hi[t.a[i]];
                    }
                    case MAX_SHORT -> {
                        l = lo[t.a[i]];
                        u = Math.max(hi[t.a[i]], hi[t.b[i]]);
                    }
                    case ABS -> {
                        double ol = lo[t.a[i]], ou = hi[t.a[i]];
                        if (ol >= 0.0) {
                            l = ol;
                            u = ou;
                        } else if (ou <= 0.0) {
                            l = -ou;
                            u = -ol;
                        } else {
                            l = 0.0;
                            u = Math.max(-ol, ou);
                        }
                    }
                    case SQUARE -> {
                        double ol = lo[t.a[i]], ou = hi[t.a[i]];
                        double m = Math.max(ol * ol, ou * ou);
                        l = ol <= 0.0 && ou >= 0.0 ? 0.0 : Math.min(ol * ol, ou * ou);
                        u = m;
                    }
                    case CUBE -> {
                        double ol = lo[t.a[i]], ou = hi[t.a[i]];
                        l = ol * ol * ol;
                        u = ou * ou * ou;
                        l -= Math.abs(l) * 0x1p-48;
                        u += Math.abs(u) * 0x1p-48;
                    }
                    case NEG_MUL -> {
                        // v > 0 ? v : v * k with k >= 0 is non-decreasing; with k < 0 bound both pieces.
                        double k = t.k0[i];
                        double ol = lo[t.a[i]], ou = hi[t.a[i]];
                        if (k >= 0.0) {
                            l = ol > 0.0 ? ol : ol * k;
                            u = ou > 0.0 ? ou : ou * k;
                        } else {
                            double nl = Math.min(ol, 0.0) * k, nu = Math.min(ou, 0.0) * k;
                            l = Math.min(ou > 0.0 ? Math.max(ol, 0.0) : Double.POSITIVE_INFINITY, Math.min(nl, nu));
                            u = Math.max(ou > 0.0 ? ou : Double.NEGATIVE_INFINITY, Math.max(nl, nu));
                        }
                    }
                    case SQUEEZE -> {
                        // clamp to [-1, 1], then v / 2 - v^3 / 24: increasing on [-1, 1].
                        double vl = Mth.clamp(lo[t.a[i]], -1.0, 1.0);
                        double vu = Mth.clamp(hi[t.a[i]], -1.0, 1.0);
                        l = vl / 2.0 - vl * vl * vl / 24.0 - 0x1p-48;
                        u = vu / 2.0 - vu * vu * vu / 24.0 + 0x1p-48;
                    }
                    case RANGE -> {
                        double il = lo[t.a[i]], iu = hi[t.a[i]];
                        double min = t.k0[i], max = t.k1[i];
                        if (il >= min && iu < max) {
                            l = lo[t.b[i]];
                            u = hi[t.b[i]];
                        } else if (iu < min || il >= max) {
                            l = lo[t.c[i]];
                            u = hi[t.c[i]];
                        } else {
                            l = Math.min(lo[t.b[i]], lo[t.c[i]]);
                            u = Math.max(hi[t.b[i]], hi[t.c[i]]);
                        }
                    }
                    case Y_GRAD -> {
                        // clampedMap(y, fromY, toY, fromValue, toValue) is monotonic in y.
                        double g0 = Mth.clampedMap(y0, t.k0[i], t.k1[i], t.k2[i], t.k3[i]);
                        double g1 = Mth.clampedMap(y0 + h - 1, t.k0[i], t.k1[i], t.k2[i], t.k3[i]);
                        double slack = Math.max(Math.abs(t.k2[i]), Math.abs(t.k3[i])) * 0x1p-44;
                        l = Math.min(g0, g1) - slack;
                        u = Math.max(g0, g1) + slack;
                    }
                    default -> {
                        return false;
                    }
                }
                if (!(l <= u)) { // NaN anywhere: no decision
                    return false;
                }
                lo[i] = l;
                hi[i] = u;
            }
            return true;
        }

        /** Whether the beardifier is provably 0 for every block of the cell (outside its beard box). */
        private boolean beardFree(int x0, int y0, int z0, int w, int h) {
            if (!this.beardKnown) {
                this.hasBeard = this.beard.superchunk$beardBox(this.beardBox);
                this.beardKnown = true;
            }
            if (!this.hasBeard) {
                return true;
            }
            int[] b = this.beardBox;
            return x0 + w - 1 < b[0] || x0 > b[3]
                    || y0 + h - 1 < b[1] || y0 > b[4]
                    || z0 + w - 1 < b[2] || z0 > b[5];
        }
    }

    /**
     * The program for a cell cache's filler, or {@code null} when it does not have the shape this
     * class bounds: {@code add(compiled final density, beardifier)} with every per-block leaf an
     * interpolator of this chunk. With GPU routing on, the final density carries a GPU delegate;
     * it is bound only when the delegate leaves a {@code cellBlocks}-point cell fill to the CPU
     * bytecode, so the cell's values are computed exactly as with the GPU off.
     */
    public static Program bind(DensityFunction filler, int cellBlocks) {
        if (!(filler instanceof DensityFunctions.TwoArgumentSimpleFunction two)
                || two.type() != DensityFunctions.TwoArgumentSimpleFunction.Type.ADD
                || !(two.argument1() instanceof CompiledDensityFunction compiled)
                || (compiled.c2me$getGpuDelegate() != null && !GpuDfcDelegate.cpuFillsOffLattice(cellBlocks))) {
            standDown("cell cache filler " + describe(filler));
            return null;
        }
        ScBeardBoxAccess beard;
        DensityFunction second = two.argument2();
        if (second == DensityFunctions.BeardifierMarker.INSTANCE) {
            beard = null;
        } else if (second instanceof net.minecraft.world.level.levelgen.Beardifier
                && second instanceof ScBeardBoxAccess access) {
            beard = access;
        } else {
            standDown("beardifier " + second.getClass().getName());
            return null;
        }
        return bindCompiled(compiled, beard);
    }

    private static Program bindCompiled(CompiledDensityFunction compiled, ScBeardBoxAccess beard) {
        Object template = template(compiled.superchunk$origin());
        if (!(template instanceof Template t) || t.entryClass != compiled.superchunk$entryClass()) {
            standDown(template instanceof Template ? "compiled class differs from its origin"
                    : "final density has a node without a bound (ast " + describeAst(compiled.superchunk$origin()) + ")");
            return null;
        }
        List<Object> args = compiled.superchunk$args();
        Corners[] corners = new Corners[t.interpArgs.length];
        for (int i = 0; i < corners.length; i++) {
            Object arg = t.interpArgs[i] < args.size() ? args.get(t.interpArgs[i]) : null;
            if (!(arg instanceof net.minecraft.world.level.levelgen.NoiseChunk.NoiseInterpolator)
                    || !(arg instanceof Corners c)) {
                standDown("per-block leaf " + (arg == null ? "null" : arg.getClass().getName()));
                return null;
            }
            corners[i] = c;
        }
        return new Program(t, corners, beard);
    }

    /**
     * A program bounding a per-chunk density function with no beardifier (the ore vein toggle), or
     * {@code null}: a compiled expression of this chunk's interpolators, or one interpolator. The
     * vein filler reads it per block through {@code compute}, which never takes a GPU delegate.
     */
    public static Program bindValue(DensityFunction df) {
        if (df instanceof net.minecraft.world.level.levelgen.NoiseChunk.NoiseInterpolator && df instanceof Corners corners) {
            Template t = new Template(null, List.<int[]>of(new int[]{INTERP, 0, -1, -1}),
                    List.<double[]>of(new double[4]), new int[]{0});
            return new Program(t, new Corners[]{corners}, null);
        }
        if (!(df instanceof CompiledDensityFunction compiled)) {
            return null;
        }
        return bindCompiled(compiled, null);
    }

    /** Vanilla's vein filler returns null for |toggle| + edge < 0.4F, and edge <= 0. */
    public static final double VEIN_TOGGLE_LIMIT = 0.4;

    /** Whether the vein program proves every block of the cell vein-free. */
    public static boolean veinFree(Program vein, int y0, int h) {
        return vein.below(y0, h, VEIN_TOGGLE_LIMIT);
    }

    /** {@link Program#classify}, callable from the NoiseChunk mixin. */
    public static int decide(Program program, int x0, int y0, int z0, int w, int h) {
        return program.classify(x0, y0, z0, w, h);
    }

    /** The bound of the last decided cell: a value of the proven sign. */
    public static double bound(Program program) {
        return program.bound;
    }

    private static final java.util.Set<String> STOOD_DOWN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Logs, once per distinct reason, why a chunk's fill keeps the per-block evaluation. */
    public static void standDown(String reason) {
        if (STOOD_DOWN.size() < 32 && STOOD_DOWN.add(reason)) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-CellSign").info("[cell-sign] not used for a fill: {}", reason);
        }
    }

    private static String describe(DensityFunction df) {
        if (df instanceof DensityFunctions.TwoArgumentSimpleFunction two) {
            return two.type() + "(" + two.argument1().getClass().getSimpleName() + ", " + two.argument2().getClass().getSimpleName() + ")";
        }
        return df.getClass().getName();
    }

    private static String describeAst(SubCompiledDensityFunction origin) {
        AstNode ast = origin.c2me$getSourceAst();
        if (ast == null) {
            return "missing";
        }
        StringBuilder sb = new StringBuilder();
        java.util.ArrayDeque<AstNode> queue = new java.util.ArrayDeque<>();
        queue.add(ast);
        java.util.Set<String> seen = new java.util.TreeSet<>();
        while (!queue.isEmpty() && seen.size() < 64) {
            AstNode n = queue.poll();
            seen.add(n.getClass().getSimpleName());
            if (!(n instanceof CacheLikeNode)) {
                for (AstNode c : n.getChildren()) {
                    queue.add(c);
                }
            }
        }
        return String.join(",", seen);
    }

    /** Count one classified cell (metrics/verify only). */
    public static void count(int decision, boolean air) {
        if (!METRICS) {
            return;
        }
        CELLS.incrementAndGet();
        if (decision == SOLID) {
            SOLID_CELLS.incrementAndGet();
        } else if (decision == NON_POSITIVE) {
            (air ? AIR_CELLS : NON_POSITIVE_NOT_AIR).incrementAndGet();
        }
    }

    /** Verify mode: the real values of a decided cell must all have the decided sign. */
    public static void verify(int decision, double[] values) {
        VERIFIED_BLOCKS.addAndGet(values.length);
        for (double v : values) {
            boolean ok = decision == SOLID ? v > 0.0 : v <= 0.0;
            if (!ok) {
                if (MISMATCHES.incrementAndGet() <= 16) {
                    org.slf4j.LoggerFactory.getLogger("SuperChunk-CellSign").warn(
                            "[cell-sign] VERIFY MISMATCH: decided {} but a block's final density is {}",
                            decision == SOLID ? "solid" : "non-positive", v);
                }
                return;
            }
        }
    }

    private static final AtomicLong SOLID_ANSWERED = new AtomicLong();
    private static final AtomicLong SOLID_MISMATCHES = new AtomicLong();

    /** Verify mode: a block of a proven-solid, vein-free cell must come out of the material rules null. */
    public static void verifySolidBlock(net.minecraft.world.level.block.state.BlockState state) {
        SOLID_ANSWERED.incrementAndGet();
        if (state != null && SOLID_MISMATCHES.incrementAndGet() <= 16) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-CellSign").warn(
                    "[cell-sign] VERIFY MISMATCH: a proven-solid cell produced {}", state);
        }
    }

    public static void report() {
        if (METRICS) {
            long cells = CELLS.get();
            long m = MISMATCHES.get();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-CellSign").info(
                    "[cell-sign] cells={} solid={} air={} non-positive-not-air={} ({}% decided){}",
                    cells, SOLID_CELLS.get(), AIR_CELLS.get(), NON_POSITIVE_NOT_AIR.get(),
                    cells == 0 ? "0" : String.format("%.1f", 100.0 * (SOLID_CELLS.get() + AIR_CELLS.get()) / cells),
                    VERIFY ? " VERIFY: blocks=" + VERIFIED_BLOCKS.get() + " MISMATCHES=" + m + "; solid-answer blocks="
                            + SOLID_ANSWERED.get() + " MISMATCHES=" + SOLID_MISMATCHES.get() + " -> "
                            + (m == 0 && SOLID_MISMATCHES.get() == 0 ? "PASS" : "FAIL") : "");
        }
    }
}
