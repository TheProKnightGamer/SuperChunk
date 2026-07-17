package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.gpu.dfc.OnDeviceInterp;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SuperChunk GPU — <b>STAGE 2 on-device interpolation</b> consumption hook
 * (flag {@code -Dsuperchunk.gpu.onDeviceInterp}; verify variant
 * {@code -Dsuperchunk.gpu.onDeviceInterp.verify}).
 *
 * <p>{@code NoiseChunk.NoiseInterpolator.updateForZ} finalises the per-block
 * interpolated value via the Y&rarr;X&rarr;Z lerp chain
 * ({@code this.value = Mth.lerp(deltaZ, valueZ0, valueZ1)}). Every direct interpolator
 * read during block iteration ({@code fillingCell == false}: aquifer/ore DFs, etc.)
 * returns exactly this {@code this.value}. We inject at the TAIL — after the CPU lerp
 * has run — and, when this interpolator maps to a root of the device-resident GPU full
 * field, overwrite {@code this.value} with the GPU-computed value (matching
 * {@code fullfield.cl}'s Y&rarr;X&rarr;Z order, bit-exact at fp64). Because the override
 * is on the field every reader consults, NO C2ME consumption code changes.
 *
 * <ul>
 *   <li><b>FAST</b> (HEAD, cancellable): for a served interpolator, set
 *       {@code this.value = gpu} and CANCEL — the vanilla per-block
 *       {@code this.value = Mth.lerp(deltaZ, valueZ0, valueZ1)} is SKIPPED, so the GPU
 *       field genuinely replaces the CPU lerp (no doubled work). Not served (NaN) =&gt;
 *       no cancel, the CPU lerp runs as normal.</li>
 *   <li><b>VERIFY</b> (TAIL): the CPU lerp has already run; compare {@code gpu} vs the
 *       just-computed CPU {@code this.value} (count BUGS), and leave {@code this.value}
 *       as the vanilla value so terrain stays correct while parity is proven.</li>
 * </ul>
 *
 * <p>{@code updateForZ} sets {@code this.value} as its only statement, and
 * {@code valueZ0}/{@code valueZ1} feed nothing else, so cancelling it after writing the
 * field value is exactly equivalent to the vanilla write — just without the redundant
 * lerp. When the flag is OFF (production default) the guard {@code OnDeviceInterp.ENABLED}
 * is a {@code static final false}, so the JIT constant-folds both handlers to an immediate
 * return — zero behaviour change, no measurable overhead. The {@code Mth.lerp3} cell-cache
 * path ({@code finalDensity}) is never touched here ({@code updateForZ} is not called
 * during the cell-cache fill).
 */
@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class MixinNoiseInterpolatorOnDeviceInterp {

    @Shadow @Final NoiseChunk this$0;

    @Shadow @Final private DensityFunction noiseFiller;

    @Shadow private double value;

    /** FAST path: replace the CPU lerp with the device-resident field value (skip the lerp). */
    @Inject(method = "updateForZ", at = @At("HEAD"), cancellable = true)
    private void superchunk$onDeviceInterpFast(double deltaZ, CallbackInfo ci) {
        if (!OnDeviceInterp.ENABLED || OnDeviceInterp.VERIFY) {
            return;   // constant-folded away when off; verify uses the TAIL handler instead
        }
        double gpu = OnDeviceInterp.sample(this, this.noiseFiller, this.this$0);
        if (Double.isNaN(gpu)) {
            return;   // not served -> let the vanilla CPU lerp run
        }
        this.value = gpu;   // consume the device-resident field
        ci.cancel();        // skip the per-block CPU Mth.lerp (its only effect was this.value)
    }

    /** VERIFY path: compare the GPU field value against the vanilla CPU lerp; return vanilla. */
    @Inject(method = "updateForZ", at = @At("TAIL"))
    private void superchunk$onDeviceInterpVerify(double deltaZ, CallbackInfo ci) {
        if (!OnDeviceInterp.VERIFY) {
            return;   // constant-folded away unless verify mode is on
        }
        double gpu = OnDeviceInterp.sample(this, this.noiseFiller, this.this$0);
        if (Double.isNaN(gpu)) {
            return;   // not served by the GPU field
        }
        OnDeviceInterp.recordCompare(gpu, this.value);
        // return the vanilla value (terrain stays correct while parity is proven)
    }
}
