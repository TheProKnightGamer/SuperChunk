package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IOnDeviceInterpCache;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.vif.NoisePosVanillaInterface;
import dev.superchunk.gpu.dfc.OnDeviceInterp;
import dev.superchunk.worldgen.LerpTables;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Yarn ChunkNoiseSampler.DensityInterpolator -> Mojmap NoiseChunk.NoiseInterpolator
// Yarn fields: field_34622 -> this$0 (NoiseChunk), delegate -> noiseFiller,
//   x0y0z0..x1y1z1 -> noise000..noise111, result -> value.
// lerp3 corner order matches: Mojmap noiseXYZ == Yarn x<X>y<Y>z<Z>.
@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class MixinChunkNoiseSamplerDensityInterpolator implements IFastCacheLike, IOnDeviceInterpCache {

    @Shadow @Final NoiseChunk this$0;

    // SuperChunk GPU — STAGE 2 on-device interp: per-interpolator WHERE-to-read cache (see
    // IOnDeviceInterpCache / OnDeviceInterp.resolveCtx). Hoists the per-block ThreadLocal get +
    // root-map lookup to ONCE per interpolator per chunk. Touched only by the single worker filling
    // this interpolator's chunk; validated by the (opaque FieldCtx) ctx ref + generation stamp so a
    // reused/migrated interpolator can never serve a stale root/base. Off-safe: never written or read
    // when OnDeviceInterp.ENABLED is false (all call sites are ENABLED-guarded).
    @Unique private Object superchunk$odiCtx;
    @Unique private long superchunk$odiGen;
    @Unique private int superchunk$odiRoot;
    @Unique private int superchunk$odiBase;

    @Shadow private double noise000;

    @Shadow private double noise100;

    @Shadow private double noise010;

    @Shadow private double noise110;

    @Shadow private double noise001;

    @Shadow private double noise101;

    @Shadow private double noise011;

    @Shadow private double noise111;

    @Shadow private double value;

    @Mutable
    @Shadow @Final private DensityFunction noiseFiller;

    @WrapMethod(method = "compute")
    private double wrapSample(DensityFunction.FunctionContext pos, Operation<Double> original) {
        if (pos instanceof NoiseChunk) {
            return original.call(pos);
        }
        if (pos instanceof NoisePosVanillaInterface vif && vif.getType() == EvalType.INTERPOLATION) {
            boolean isInInterpolationLoop = ((IChunkNoiseSampler) this.this$0).getIsInInterpolationLoop();
            boolean isSamplingForCaches = ((IChunkNoiseSampler) this.this$0).getIsSamplingForCaches();
            if (!isInInterpolationLoop) {
                return original.call(pos);
            }
            int startBlockX = ((IChunkNoiseSampler) this.this$0).getStartBlockX();
            int startBlockY = ((IChunkNoiseSampler) this.this$0).getStartBlockY();
            int startBlockZ = ((IChunkNoiseSampler) this.this$0).getStartBlockZ();
            int horizontalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getHorizontalCellBlockCount();
            int verticalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getVerticalCellBlockCount();
            int cellBlockX = pos.blockX() - startBlockX;
            int cellBlockY = pos.blockY() - startBlockY;
            int cellBlockZ = pos.blockZ() - startBlockZ;
            return isSamplingForCaches
                    ? superchunk$cellRead(cellBlockX, cellBlockY, cellBlockZ,
                    horizontalCellBlockCount, verticalCellBlockCount, pos.blockX(), pos.blockY(), pos.blockZ())
                    : this.value;
        }
        return original.call(pos);
    }

    @Override
    public double c2me$getCached(int x, int y, int z, EvalType evalType) {
        if (evalType == EvalType.INTERPOLATION) {
            boolean isInInterpolationLoop = ((IChunkNoiseSampler) this.this$0).getIsInInterpolationLoop();
            if (isInInterpolationLoop) {
                if (((IChunkNoiseSampler) this.this$0).getIsSamplingForCaches()) {
                    int startBlockX = ((IChunkNoiseSampler) this.this$0).getStartBlockX();
                    int startBlockY = ((IChunkNoiseSampler) this.this$0).getStartBlockY();
                    int startBlockZ = ((IChunkNoiseSampler) this.this$0).getStartBlockZ();
                    int horizontalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getHorizontalCellBlockCount();
                    int verticalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getVerticalCellBlockCount();
                    int cellBlockX = x - startBlockX;
                    int cellBlockY = y - startBlockY;
                    int cellBlockZ = z - startBlockZ;
                    return superchunk$cellRead(cellBlockX, cellBlockY, cellBlockZ,
                            horizontalCellBlockCount, verticalCellBlockCount, x, y, z);
                } else {
                    return this.value;
                }
            } else {
                throw new IllegalStateException("Trying to sample interpolator outside the interpolation loop");
            }
        }

        return Double.longBitsToDouble(CACHE_MISS_NAN_BITS);
    }

    @Override
    public boolean c2me$getCachedPointwise(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        // Element-wise twin of the single-point c2me$getCached above, minus its throw (left to the
        // caller's per-element path): the in-cell read while sampling for caches, else the value.
        if (evalType != EvalType.INTERPOLATION || !((IChunkNoiseSampler) this.this$0).getIsInInterpolationLoop()) {
            return false;
        }
        if (!((IChunkNoiseSampler) this.this$0).getIsSamplingForCaches()) {
            java.util.Arrays.fill(res, this.value);
            return true;
        }
        int startBlockX = ((IChunkNoiseSampler) this.this$0).getStartBlockX();
        int startBlockY = ((IChunkNoiseSampler) this.this$0).getStartBlockY();
        int startBlockZ = ((IChunkNoiseSampler) this.this$0).getStartBlockZ();
        int horizontalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getHorizontalCellBlockCount();
        int verticalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getVerticalCellBlockCount();
        for (int i = 0; i < res.length; i++) {
            res[i] = superchunk$cellRead(x[i] - startBlockX, y[i] - startBlockY, z[i] - startBlockZ,
                    horizontalCellBlockCount, verticalCellBlockCount, x[i], y[i], z[i]);
        }
        return true;
    }

    @Override
    public boolean c2me$getCached(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        if (evalType == EvalType.INTERPOLATION) {
            boolean isInInterpolationLoop = ((IChunkNoiseSampler) this.this$0).getIsInInterpolationLoop();
            if (isInInterpolationLoop) {
                if (((IChunkNoiseSampler) this.this$0).getIsSamplingForCaches()) {
                    int startBlockX = ((IChunkNoiseSampler) this.this$0).getStartBlockX();
                    int startBlockY = ((IChunkNoiseSampler) this.this$0).getStartBlockY();
                    int startBlockZ = ((IChunkNoiseSampler) this.this$0).getStartBlockZ();
                    int horizontalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getHorizontalCellBlockCount();
                    int verticalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getVerticalCellBlockCount();
                    // SuperChunk GPU — STAGE 2: TRUE bulk-fill from the device-resident lerp3 field.
                    // Resolves once, then a tight served loop (no per-index CTX.get / map lookup /
                    // dispatch). When OnDeviceInterp is off (static final false) this short-circuits
                    // to the vanilla per-index loop below — byte-identical to upstream. Returns false
                    // (falls through to the per-index loop) whenever this interpolator is not served.
                    if (OnDeviceInterp.ENABLED && OnDeviceInterp.fillLerp3Bulk(
                            this, this.noiseFiller, this.this$0, res, x, y, z,
                            startBlockX, startBlockY, startBlockZ,
                            horizontalCellBlockCount, verticalCellBlockCount)) {
                        return true;
                    }
                    for (int i = 0; i < res.length; i ++) {
                        res[i] = superchunk$cellRead(x[i] - startBlockX, y[i] - startBlockY, z[i] - startBlockZ,
                                horizontalCellBlockCount, verticalCellBlockCount, x[i], y[i], z[i]);
                    }
                    return true;
                } else {
                    return false;
                }
            }
        }

        return false;
    }

    // ---- SuperChunk: per-cell lerp tables (dev.superchunk.worldgen.LerpTables) ----
    // x-lerps per in-cell column cx, y-lerps per in-cell row (cx, cy), each stamped with the cell
    // generation they were built in; selectCellYZ (the only writer of noise000..noise111) bumps it.
    @Unique private int superchunk$cellGen = 1;
    @Unique private int superchunk$tableW;
    @Unique private int superchunk$tableH;
    @Unique private double[] superchunk$xLerps;
    @Unique private double[] superchunk$yLerps;
    @Unique private int[] superchunk$xGen;
    @Unique private int[] superchunk$yGen;

    @Inject(method = "selectCellYZ", at = @At("RETURN"))
    private void superchunk$newCell(int cellY, int cellZ, CallbackInfo ci) {
        this.superchunk$cellGen++;
    }

    /**
     * The in-cell read at cell-relative (cx, cy, cz): exactly {@link #superchunk$lerp3} with the
     * vanilla deltas {@code cx / w, cy / h, cz / w}, but sharing the x- and y-lerps between the
     * blocks of the cell (see {@link dev.superchunk.worldgen.LerpTables}). The GPU on-device path,
     * the kill switch and any out-of-cell coordinate take the per-block lerp3 as before.
     */
    @Unique
    private double superchunk$cellRead(int cx, int cy, int cz, int w, int h, int blockX, int blockY, int blockZ) {
        if (OnDeviceInterp.ENABLED || !LerpTables.ENABLED
                || cx < 0 || cx >= w || cy < 0 || cy >= h || cz < 0 || cz >= w) {
            return superchunk$lerp3((double) cx / (double) w, (double) cy / (double) h, (double) cz / (double) w,
                    blockX, blockY, blockZ);
        }
        if (w != this.superchunk$tableW || h != this.superchunk$tableH) {
            this.superchunk$tableW = w;
            this.superchunk$tableH = h;
            this.superchunk$xLerps = new double[w * 4];
            this.superchunk$yLerps = new double[w * h * 2];
            this.superchunk$xGen = new int[w];
            this.superchunk$yGen = new int[w * h];
        }
        final int gen = this.superchunk$cellGen;
        final int row = cx * h + cy;
        final double[] yl = this.superchunk$yLerps;
        if (this.superchunk$yGen[row] != gen) {
            final double[] xl = this.superchunk$xLerps;
            final int xb = cx * 4;
            if (this.superchunk$xGen[cx] != gen) {
                final double tx = (double) cx / (double) w;
                xl[xb] = Mth.lerp(tx, this.noise000, this.noise100);
                xl[xb + 1] = Mth.lerp(tx, this.noise010, this.noise110);
                xl[xb + 2] = Mth.lerp(tx, this.noise001, this.noise101);
                xl[xb + 3] = Mth.lerp(tx, this.noise011, this.noise111);
                this.superchunk$xGen[cx] = gen;
            }
            final double ty = (double) cy / (double) h;
            yl[row * 2] = Mth.lerp(ty, xl[xb], xl[xb + 1]);
            yl[row * 2 + 1] = Mth.lerp(ty, xl[xb + 2], xl[xb + 3]);
            this.superchunk$yGen[row] = gen;
        }
        final double value = Mth.lerp((double) cz / (double) w, yl[row * 2], yl[row * 2 + 1]);
        if (LerpTables.VERIFY) {
            return LerpTables.verify(value,
                    superchunk$vanillaLerp3((double) cx / (double) w, (double) cy / (double) h, (double) cz / (double) w));
        }
        return value;
    }

    /**
     * SuperChunk GPU — STAGE 2 on-device interpolation, <b>lerp3 (X&rarr;Y&rarr;Z) cell-cache
     * path</b> (flag {@code -Dsuperchunk.gpu.onDeviceInterp}; verify variant {@code .verify}).
     *
     * <p>Every cell-cache fill site above computes the per-block interpolated value via
     * {@code Mth.lerp3} (X&rarr;Y&rarr;Z order over this cell's 8 corners). This routes that
     * through the device-resident GPU full field instead:
     * <ul>
     *   <li><b>FAST</b> ({@code onDeviceInterp}): if this interpolator maps to a GPU root and
     *       the block is in-field, return the GPU lerp3 value and SKIP the CPU {@code Mth.lerp3}
     *       (the GPU field genuinely replaces the CPU lerp). Otherwise compute the CPU value.</li>
     *   <li><b>VERIFY</b> ({@code onDeviceInterp.verify}): compute BOTH, compare into the shared
     *       {@code [on-device-interp]} parity counters (BUGS/maxAbsErr; separate lerp3 comparison
     *       count), and RETURN THE VANILLA value so terrain stays correct while parity is proven.</li>
     * </ul>
     * Bit-exact to the vanilla {@code Mth.lerp3} at fp64 (same corner buffer, same X&rarr;Y&rarr;Z
     * order, {@code mc_lerp3 == Mth.lerp3}). When the flag is OFF, {@code OnDeviceInterp.ENABLED}
     * is a {@code static final false} so the JIT constant-folds this to the bare {@code Mth.lerp3}
     * — zero behaviour change. {@code blockX/Y/Z} are ABSOLUTE block coords (the cell fill batches
     * arbitrary positions); the deltas {@code tx/ty/tz} are the vanilla in-cell deltas.
     */
    @Unique
    private double superchunk$lerp3(double tx, double ty, double tz, int blockX, int blockY, int blockZ) {
        if (OnDeviceInterp.ENABLED) {
            double gpu = OnDeviceInterp.sampleLerp3(this, this.noiseFiller, this.this$0, blockX, blockY, blockZ);
            if (!Double.isNaN(gpu)) {
                if (OnDeviceInterp.VERIFY) {
                    double cpu = superchunk$vanillaLerp3(tx, ty, tz);
                    OnDeviceInterp.recordCompareLerp3(gpu, cpu);
                    return cpu;   // return vanilla while parity is proven
                }
                return gpu;       // FAST: GPU field replaces the CPU Mth.lerp3
            }
        }
        return superchunk$vanillaLerp3(tx, ty, tz);
    }

    /** The exact vanilla {@code Mth.lerp3} over this cell's 8 corners (X&rarr;Y&rarr;Z order). */
    @Unique
    private double superchunk$vanillaLerp3(double tx, double ty, double tz) {
        return Mth.lerp3(tx, ty, tz,
                this.noise000, this.noise100, this.noise010, this.noise110,
                this.noise001, this.noise101, this.noise011, this.noise111);
    }

    // ---- IOnDeviceInterpCache: per-interpolator hoisted-resolution cache (see OnDeviceInterp) ----

    @Override
    public Object superchunk$odiCtx() {
        return this.superchunk$odiCtx;
    }

    @Override
    public long superchunk$odiGen() {
        return this.superchunk$odiGen;
    }

    @Override
    public int superchunk$odiRoot() {
        return this.superchunk$odiRoot;
    }

    @Override
    public int superchunk$odiBase() {
        return this.superchunk$odiBase;
    }

    @Override
    public void superchunk$odiStore(Object ctx, long generation, int root, int base) {
        this.superchunk$odiCtx = ctx;
        this.superchunk$odiGen = generation;
        this.superchunk$odiRoot = root;
        this.superchunk$odiBase = base;
    }

    @Override
    public double superchunk$odiVanillaLerp3(double tx, double ty, double tz) {
        return superchunk$vanillaLerp3(tx, ty, tz);
    }

    @Override
    public void c2me$cache(int x, int y, int z, EvalType evalType, double cached) {
        // nop
    }

    @Override
    public void c2me$cache(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        // nop
    }

    @Override
    public DensityFunction c2me$getDelegate() {
        return this.noiseFiller;
    }

    @Override
    public DensityFunction c2me$withDelegate(DensityFunction delegate) {
        this.noiseFiller = delegate;
        return this;
    }
}
