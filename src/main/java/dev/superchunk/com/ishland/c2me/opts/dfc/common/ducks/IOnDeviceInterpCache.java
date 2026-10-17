package dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks;

/**
 * SuperChunk GPU — <b>STAGE 2 on-device interpolation</b> per-interpolator WHERE-to-read cache
 * (flag {@code -Dsuperchunk.gpu.onDeviceInterp}; verify variant {@code .verify}).
 *
 * <p>Implemented by {@code NoiseChunk.NoiseInterpolator} (via the density-interpolator mixin).
 * It carries the ONE-TIME resolution of an interpolator to its device-resident GPU full-field
 * root, hoisted out of the per-block consume so the hot path is a bare array read instead of a
 * {@code ThreadLocal} get + {@code IdentityHashMap} root lookup on every one of ~98304 blocks.
 *
 * <p>The cache is validated by an opaque field-context reference plus a monotonically-unique
 * {@code generation} stamp: {@code OnDeviceInterp} re-resolves whenever the cached generation
 * no longer matches the field-context's current generation (the generation is bumped on every
 * field install/teardown), so a reused interpolator can NEVER serve a root/base from a prior
 * chunk's field. The {@code ctx} is stored as {@link Object} because the backing
 * {@code FieldCtx} type is private to {@code OnDeviceInterp}. All accessors are only ever
 * touched by the single worker thread that owns the interpolator's chunk fill (except the
 * generation staleness read, which the {@code FieldCtx.generation} field makes volatile).
 */
public interface IOnDeviceInterpCache {

    /** The field-context this cache was resolved against (opaque {@code FieldCtx}), or null. */
    Object superchunk$odiCtx();

    /** The field-context generation the resolution below is valid for. */
    long superchunk$odiGen();

    /** Resolved root index in the active fused group; {@code >= 0} served, {@code -1} not served. */
    int superchunk$odiRoot();

    /** Cached field base offset {@code root * fullN} (only meaningful when {@code root >= 0}). */
    int superchunk$odiBase();

    /** Installs a fresh resolution (called by {@code OnDeviceInterp} on a cache miss). */
    void superchunk$odiStore(Object ctx, long generation, int root, int base);

    /** The exact vanilla {@code Mth.lerp3} over this interpolator's current 8 cell corners
     * (X&rarr;Y&rarr;Z order) — the CPU fallback used by the bulk lerp3 fill for out-of-field
     * indices and by the verify comparison. */
    double superchunk$odiVanillaLerp3(double tx, double ty, double tz);
}
