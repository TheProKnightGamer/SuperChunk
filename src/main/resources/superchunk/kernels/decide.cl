// =====================================================================
// SuperChunk GPU backend — COMPACT-READBACK decide-kernel STATIC helpers.
//
// STAGE 5 of the GPU-AHEAD plan (-Dsuperchunk.gpu.compactIds=probe, default
// off): the PRODUCTION per-block decide kernel is chained inside the batched
// dispatch (GpuBatchDispatcher.batchFill) between the corner NDRange and the
// corner readback, reading the SAME device-resident K*M*cornerN multichunk
// corner buffer (df_batch_lattice_multichunk layout,
// corners[(c*M + k)*cornerN + (iy*dimX + ix)*dimZ + iz]) and writing ONE byte
// per block (id | bit7 = shouldScheduleFluidUpdate) into a K*fullN pinned
// buffer. In PROBE mode the bytes are read back and DISCARDED by the consumer
// (plumbing-tax tripwire) — terrain is untouched.
//
// This file holds only the STATIC, dimension-independent helpers. The
// dimension-dependent parts — the REAL finalDensity tail (emitted by
// OpenCLAstEmitter.emitDecide from the actual AST between the interpolated()
// markers and the router root), the barrierNoise function, and the
// sc_decide_multichunk kernel entry with the fused-root indices baked in —
// are generated at runtime (CompactIds.java) and appended after this file.
//
// Host concatenation order (CompactIds.assembleSource):
//   fp64 pragma + noise.cl + dfc_support.cl + rng.cl + aquifer.cl + THIS +
//   emitted(tail/barrier fns) + generated kernel entry.
//
// PRECISION: the CORNER BUFFER is always fp64 (produced by the fp64 batcher
// corner NDRange; the `corners` pointers below are hard-typed double and
// converted at load). The DECISION math (`real`: interpolation, emitted
// tail/barrier/vein noise ASTs) follows the build: fp64 by default, fp32
// under -DUSE_FP32 (gpu.decideFp32 — the decide chain is ALU-bound and GA104
// fp64 is 1/64 rate). aq_decide's comparison algebra stays hard-typed double
// in BOTH builds (its inputs carry any fp32 error either way; double
// comparisons add none). fp64 build: the (real) casts are identity —
// bit-exact per the Stage-3 census (crDivide). fp32 build: the parity
// envelope is MEASURED by the compact-consume-verify flip census, not assumed.
//
// INTERPOLATION ORDERS (both proven bit-exact against their CPU consumers,
// OnDeviceInterp 349.65M comparisons BUGS=0 / census 133.8M blocks FLIPS=0):
//   * sc_dec_lerp3_grid — X->Y->Z Mth.lerp3, the CacheAllInCell cell-cache
//     order that feeds finalDensity/aquifer (mirrors fullfield.cl
//     df_full_field_interp_lerp3 corner naming + arg order exactly);
//   * sc_dec_value_grid — Y->X->Z updateForY/X/Z `this.value` order, what the
//     per-block OreVeinifier vein DF reads see (mirrors df_full_field_interp).
// =====================================================================

// ---- output byte alphabet (bits 0..6; bit7 = shouldScheduleFluidUpdate) ----
//   0 stone(null) 1 air 2 water 3 lava
//   4 copper_ore 5 raw_copper 6 granite  (COPPER vein: ore/raw/filler)
//   7 deepslate_iron_ore 8 raw_iron 9 tuff (IRON vein: ore/raw/filler)
//  10 beard-sentinel (inside the chunk's beard AABB -> CPU decides)
// 255 absent (chunk had no aux -> no decision; host never slices these)
#define SC_DEC_BEARD  10
#define SC_DEC_ABSENT 255

// ---- per-chunk aux int-table layout (K * SC_AUX_INTS ints) ----
//  0 hasAux  1 minGridX  2 minGridY  3 minGridZ  4 gridSizeX  5 gridSizeZ
//  6 seaLevel  7 lavaLevel  8 defaultFluidId
//  9 hasBeard  10..15 beard AABB minX,minY,minZ,maxX,maxY,maxZ (inclusive)
// 16 veinsEnabled  17 airSkipY (high-air fast-path floor — aq_airpath_similarity)
#define SC_AUX_INTS 18

// In-register 8-corner fetch + X->Y->Z Mth.lerp3 (the finalDensity cell-cache
// order). Same corner flat index (Y-outer/X-mid/Z-inner) and n[X][Y][Z] naming
// as df_full_field_interp_lerp3 (fullfield.cl); mc_lerp3 == Mth.lerp3
// op-for-op (mc_lerp = a + t*(b-a), FP_CONTRACT OFF from noise.cl).
inline real sc_dec_lerp3_grid(__global const double* corners, int base,
                              int cx, int cy, int cz, int dimX, int dimZ,
                              real dx, real dy, real dz) {
    real n000 = (real) corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz)];
    real n010 = (real) corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz)];
    real n100 = (real) corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz)];
    real n110 = (real) corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz)];
    real n001 = (real) corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz + 1)];
    real n011 = (real) corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz + 1)];
    real n101 = (real) corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real n111 = (real) corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz + 1)];
    return mc_lerp3(dx, dy, dz, n000, n100, n010, n110, n001, n101, n011, n111);
}

// In-register 8-corner fetch + Y->X->Z value-path order (NoiseInterpolator
// updateForY/updateForX/updateForZ `this.value` — the per-block vein DF reads).
// Mirrors df_full_field_interp (fullfield.cl) exactly.
inline real sc_dec_value_grid(__global const double* corners, int base,
                              int cx, int cy, int cz, int dimX, int dimZ,
                              real dx, real dy, real dz) {
    real n000 = (real) corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz)];
    real n010 = (real) corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz)];
    real n100 = (real) corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz)];
    real n110 = (real) corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz)];
    real n001 = (real) corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz + 1)];
    real n011 = (real) corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz + 1)];
    real n101 = (real) corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real n111 = (real) corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real vXZ00 = mc_lerp(dy, n000, n010);
    real vXZ10 = mc_lerp(dy, n100, n110);
    real vXZ01 = mc_lerp(dy, n001, n011);
    real vXZ11 = mc_lerp(dy, n101, n111);
    real vZ0 = mc_lerp(dx, vXZ00, vXZ10);
    real vZ1 = mc_lerp(dx, vXZ01, vXZ11);
    return mc_lerp(dz, vZ0, vZ1);
}

// NOTE (Phase A, vein activation): the per-block ore-vein decision is the SHARED
// aq_vein_decide (aquifer.cl, host-prepended before this file) — the same function
// the Stage-3 census kernel (aquifer_census) proves, including its private
// nextFloat port. No vein code lives here; the generated sc_decide_multichunk
// entry interpolates the vein grid slots (sc_dec_value_grid) / evaluates the
// emitted vein tail functions and calls aq_vein_decide with the results.

// =====================================================================
// Z-RUN shared interpolation prefixes. One work-item covers the sz blocks of
// one z-cell run (fullZ = (dimZ-1)*sz, so every aligned sz-run is exactly one
// cell in z: all its blocks share cx/cy/cz, ddx/ddy, and therefore all 8
// corners of every grid slot). For fixed (ddx, ddy):
//   mc_lerp3(ddx,ddy,ddz, n...) == mc_lerp(ddz, P0, P1)
// with P0/P1 the two mc_lerp2 x/y planes (see mc_lerp3, noise.cl) — computing
// P0/P1 ONCE per run and only the final mc_lerp per block executes the SAME
// IEEE op sequence per block as the full per-block lerp3: BIT-EXACT sharing,
// 8x fewer corner loads and ~2.3x fewer lerps per block. Same structure for
// the value order (sc_dec_value_grid): its dy-then-dx stages are the shared
// prefix and its final mc_lerp(ddz, vZ0, vZ1) is the per-block op.
// =====================================================================
inline void sc_dec_lerp3_prefix(__global const double* corners, int base,
                                int cx, int cy, int cz, int dimX, int dimZ,
                                real dx, real dy,
                                __private real* p0, __private real* p1) {
    real n000 = (real) corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz)];
    real n010 = (real) corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz)];
    real n100 = (real) corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz)];
    real n110 = (real) corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz)];
    real n001 = (real) corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz + 1)];
    real n011 = (real) corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz + 1)];
    real n101 = (real) corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real n111 = (real) corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz + 1)];
    *p0 = mc_lerp2(dx, dy, n000, n100, n010, n110);
    *p1 = mc_lerp2(dx, dy, n001, n101, n011, n111);
}

inline void sc_dec_value_prefix(__global const double* corners, int base,
                                int cx, int cy, int cz, int dimX, int dimZ,
                                real dx, real dy,
                                __private real* p0, __private real* p1) {
    real n000 = (real) corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz)];
    real n010 = (real) corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz)];
    real n100 = (real) corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz)];
    real n110 = (real) corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz)];
    real n001 = (real) corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz + 1)];
    real n011 = (real) corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz + 1)];
    real n101 = (real) corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real n111 = (real) corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real vXZ00 = mc_lerp(dy, n000, n010);
    real vXZ10 = mc_lerp(dy, n100, n110);
    real vXZ01 = mc_lerp(dy, n001, n011);
    real vXZ11 = mc_lerp(dy, n101, n111);
    *p0 = mc_lerp(dx, vXZ00, vXZ10);
    *p1 = mc_lerp(dx, vXZ01, vXZ11);
}
