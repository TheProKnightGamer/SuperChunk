package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import net.minecraft.util.CubicSpline;
import net.minecraft.util.Mth;
import net.minecraft.util.ToFloatFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

// Yarn Spline.Implementation -> Mojmap CubicSpline$Multipoint. Upstream C2ME mixin that was
// silently dropped in the original port (present in upstream c2me-opts-dfc.mixins.json and
// the analysis/c2me.md inventory, absent here — restored 2026-07-02, see MERGE_NOTES.md).
// Two halves, both faithful to upstream:
//  1) PERF: inline the findIntervalStart binary search (upstream findRangeForLocation) and a
//     simplified apply() — bit-identical float math (verified against neoforge-21.1.215
//     sources: same ops on the same operands; the inlined loop IS Mth.binarySearch's body).
//     This is the spline speed-up for every UNCOMPILED density-function evaluation: the
//     blendingFallback path (old-chunk upgrades run the whole vanilla DF tree per point),
//     interpreter/parity reference paths, and third-party consumers of the vanilla router.
//  2) VALUE equals/hashCode (implicit overwrite of the record-generated ones): deep array
//     comparison instead of the record's reference comparison on float[] components —
//     upstream ships this for dedup of value-identical splines; provably result-identical
//     (equal components => identical outputs).
@Mixin(CubicSpline.Multipoint.class)
public abstract class MixinSplineImplementation<C, I extends ToFloatFunction<C>> {

    /**
     * @author ishland
     * @reason inline binary search
     */
    @Overwrite
    private static int findIntervalStart(float[] locations, float start) {
        // Exactly Mth.binarySearch(0, locations.length, i -> start < locations[i]) - 1,
        // without the per-call lambda + indirection.
        int min = 0;
        int i = locations.length;

        while (i > 0) {
            int j = i / 2;
            int k = min + j;
            if (start < locations[k]) {
                i = j;
            } else {
                min = k + 1;
                i -= j + 1;
            }
        }

        return min - 1;
    }

    @Shadow @Final private I coordinate;

    @Shadow @Final private float[] locations;

    @Shadow
    private static float linearExtend(float coordinate, float[] locations, float value, float[] derivatives, int index) {
        throw new AbstractMethodError();
    }

    @Shadow @Final private List<CubicSpline<C, I>> values;

    @Shadow @Final private float[] derivatives;

    /**
     * @author ishland
     * @reason simplify method a bit
     */
    @Overwrite
    public float apply(C x) {
        float point = this.coordinate.apply(x);
        int rangeForLocation = findIntervalStart(this.locations, point);
        int last = this.locations.length - 1;
        if (rangeForLocation < 0) {
            return linearExtend(point, this.locations, this.values.get(0).apply(x), this.derivatives, 0);
        } else if (rangeForLocation == last) {
            return linearExtend(point, this.locations, this.values.get(last).apply(x), this.derivatives, last);
        } else {
            float loc0 = this.locations[rangeForLocation];
            float loc1 = this.locations[rangeForLocation + 1];
            float locDist = loc1 - loc0;
            float k = (point - loc0) / locDist;
            float n = this.values.get(rangeForLocation).apply(x);
            float o = this.values.get(rangeForLocation + 1).apply(x);
            float onDist = o - n;
            float p = this.derivatives[rangeForLocation] * locDist - onDist;
            float q = -this.derivatives[rangeForLocation + 1] * locDist + onDist;
            return Mth.lerp(k, n, o) + k * (1.0F - k) * Mth.lerp(k, p, q);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CubicSpline.Multipoint<?, ?> that = (CubicSpline.Multipoint<?, ?>) o;
        return Objects.equals(coordinate, that.coordinate()) && Arrays.equals(locations, that.locations())
                && Objects.equals(values, that.values()) && Arrays.equals(derivatives, that.derivatives());
    }

    @Override
    public int hashCode() {
        int result = 1;

        result = 31 * result + Objects.hashCode(coordinate);
        result = 31 * result + Arrays.hashCode(locations);
        result = 31 * result + Objects.hashCode(values);
        result = 31 * result + Arrays.hashCode(derivatives);

        return result;
    }
}
