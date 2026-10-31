package dev.superchunk.worldgen;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.CompiledDensityFunction;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A compiled density function whose height-independent parts are computed once per column.
 *
 * <p>A caller that probes one column at many heights (Distant Horizons' rough surface search:
 * about 30 single-point evaluations of the final density per column) recomputes continentalness,
 * erosion, ridges and the offset/factor/jaggedness splines at every probe, because on the level's
 * own router the {@code flat_cache}/{@code cache_2d} markers cache nothing. The compiled code
 * already asks each marker for a cached value ({@code CacheLikeNode}; a vanilla marker always
 * answers "miss"). This makes a per-thread copy of the compiled function ({@code mapAll}) whose
 * markers keep the last column's value, but only markers whose input {@link ClimateColumns.Walk}
 * proves independent of y: such an input is the same at every height of a column, so the value
 * kept is the one the compiled code would compute again. Everything else, the 3D noises and caves
 * included, runs as compiled code as before.
 *
 * <p>Keyed by (x, z) and the evaluation type; single-point evaluation only (array fills and
 * anything with a blender take the function's own paths). Not a compiled function (the DFC
 * compiler off) or no such marker: the function itself is used.
 */
public final class ColumnMemoDensity {
    private static final ThreadLocal<PerThread> COPIES = ThreadLocal.withInitial(PerThread::new);
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private ColumnMemoDensity() {
    }

    /** This thread's column-memo copy of {@code density} (built on first use), or {@code density} itself. */
    public static DensityFunction forThisThread(DensityFunction density) {
        PerThread slot = COPIES.get();
        if (slot.source != density) {
            slot.copy = build(density);
            slot.source = density;
        }
        return slot.copy;
    }

    private static DensityFunction build(DensityFunction density) {
        if (!(density instanceof CompiledDensityFunction)) {
            return density;
        }
        ClimateColumns.Walk walk = new ClimateColumns.Walk();
        int[] counts = new int[2];
        DensityFunction copy = density.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction df) {
                if (df instanceof DensityFunctions.Marker marker) {
                    if (walk.yIndependent(marker.wrapped())) {
                        counts[0]++;
                        return new Memo(marker.wrapped());
                    }
                    counts[1]++;
                }
                return df;
            }
        });
        if (LOGGED.compareAndSet(false, true)) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Worldgen").info(
                    "[SuperChunk] column memo: {} of {} density markers are height-independent and kept per column",
                    counts[0], counts[0] + counts[1]);
        }
        return counts[0] == 0 ? density : copy;
    }

    private static final class PerThread {
        DensityFunction source;
        DensityFunction copy;
    }

    /** One marker of one thread's copy: the last column's value. */
    private static final class Memo implements IFastCacheLike {
        private final DensityFunction wrapped;
        private boolean valid;
        private int x;
        private int z;
        private EvalType type;
        private double value;

        Memo(DensityFunction wrapped) {
            this.wrapped = wrapped;
        }

        @Override
        public double c2me$getCached(int x, int y, int z, EvalType evalType) {
            if (this.valid && this.x == x && this.z == z && this.type == evalType) {
                return this.value;
            }
            return Double.longBitsToDouble(CACHE_MISS_NAN_BITS);
        }

        @Override
        public boolean c2me$getCached(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
            return false;
        }

        @Override
        public void c2me$cache(int x, int y, int z, EvalType evalType, double cached) {
            this.x = x;
            this.z = z;
            this.type = evalType;
            this.value = cached;
            this.valid = true;
        }

        @Override
        public void c2me$cache(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        }

        @Override
        public DensityFunction c2me$getDelegate() {
            return this.wrapped;
        }

        @Override
        public DensityFunction c2me$withDelegate(DensityFunction delegate) {
            return new Memo(delegate);
        }

        // As a plain density function (outside the compiled code) it is just its input.
        @Override
        public double compute(FunctionContext context) {
            return this.wrapped.compute(context);
        }

        @Override
        public void fillArray(double[] array, ContextProvider provider) {
            this.wrapped.fillArray(array, provider);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new Memo(this.wrapped.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return this.wrapped.minValue();
        }

        @Override
        public double maxValue() {
            return this.wrapped.maxValue();
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            return this.wrapped.codec();
        }
    }
}
