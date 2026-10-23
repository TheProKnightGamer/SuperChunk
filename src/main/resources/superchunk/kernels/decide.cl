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
//  9 beard mode (0 none, SC_BEARD_CPU sentinel, SC_BEARD_GPU summed here)
// 10..15 beard AABB minX,minY,minZ,maxX,maxY,maxZ (inclusive)
// 16 veinsEnabled  17 airSkipY (high-air fast-path floor — aq_airpath_similarity)
// 18 beard terms offset into beardData  19 pieces  20 junctions (SC_BEARD_GPU only)
#define SC_AUX_INTS 21
#define SC_BEARD_CPU 1
#define SC_BEARD_GPU 2

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

// =====================================================================
// BEARDIFIER on the GPU (SC_BEARD_GPU). The CPU's per-block density is
// finalDensity + Beardifier.compute (NoiseChunk adds the beardifier after the
// router), and compute() sums one term per structure piece, then one per jigsaw
// junction, in list order (C2ME's compute() overwrite, MixinStructureWeightSampler).
//
// Each term is looked up, not computed: except for BEARD_BOX, a term depends only on
// the block's small integer distances to its piece or junction, so the CPU fills
// tables of every such term once (ScBeardBoxAccess.superchunk$beardTables), each
// computed by the same functions and factor compute() uses — a lookup IS that term's
// double. The sum stays double in every build (like aq_decide). BEARD_BOX terms also
// depend on the piece's height and are computed here: op-for-op vanilla
// getBeardContribution and Mth.fastInvSqrt, FP_CONTRACT OFF from aquifer.cl, on
// vanilla's own BEARD_KERNEL floats.
//
// A term that is exactly 0.0 is skipped: the sum starts at +0.0, an IEEE add only
// produces -0.0 from two -0.0 operands, so the sum is never -0.0 and leaving out a
// 0.0 term changes nothing. These integer tests each mark a range where it is 0.0:
//   getBeardContribution: an argument outside [-12, 11] (x + 12 outside [0, 24)).
//   getBuryContribution(x, y, z): x > 6 or z > 6 returns 0.0; |y| > 6 makes the length
//     exceed 6, which returns 0.0. ENCAPSULATE passes halves, so an integer distance
//     of 12 gives exactly 6 (length >= 6, 1 - d / 6 <= 0.0: 0.0 at 6, 0.0 beyond) and
//     13 or more exceeds it.
// The table index ranges match: junction and BEARD_THIN [-12, 11]^3, BURY i1, j1 in
// [0, 6] with l1 in [-12, 12], ENCAPSULATE all three in [0, 12].
// =====================================================================
#define SC_BT_JUNC  0
#define SC_BT_THIN  13824
#define SC_BT_BURY  27648
#define SC_BT_ENCAP 28873

inline double sc_fast_inv_sqrt(double number) {
    double d0 = 0.5 * number;
    long i = as_long(number);
    i = 6910469410427058090L - (i >> 1);
    number = as_double(i);
    return number * (1.5 - d0 * number * number);
}

// Vanilla getBeardContribution, for arguments already inside the kernel range.
inline double sc_beard_contribution(int x, int y, int z, int height, __global const float* kern) {
    double d0 = (double) height + 0.5;
    double d1 = (double) x * (double) x + d0 * d0 + (double) z * (double) z;
    double d2 = -d0 * sc_fast_inv_sqrt(d1 / 2.0) / 2.0;
    return d2 * (double) kern[(z + 12) * 24 * 24 + (x + 12) * 24 + (y + 12)];
}

// One piece's term at block (i, j, k), or 0.0 (skipped by the caller's test first).
// Returns whether the term may be nonzero; *v is the term.
inline int sc_beard_piece(__global const int* bd, int i1, int j1, int j, int k1, int l1,
                          __global const float* kern, __global const double* bt, double* v) {
    int adj = bd[7];
    if (adj == 1) {                                             // BURY
        if (i1 > 6 || j1 > 6 || l1 > 12 || l1 < -12) {
            return 0;
        }
        *v = bt[SC_BT_BURY + (i1 * 25 + (l1 + 12)) * 7 + j1];
    } else if (adj == 2) {                                      // BEARD_THIN
        if (l1 < -12 || l1 > 11) {
            return 0;
        }
        *v = bt[SC_BT_THIN + (i1 + 12) * 576 + (l1 + 12) * 24 + (j1 + 12)];
    } else if (adj == 3) {                                      // BEARD_BOX
        int q = max(0, max(k1 - j, j - bd[4]));
        if (q > 11) {
            return 0;
        }
        *v = sc_beard_contribution(i1, q, j1, l1, kern) * 0.8;
    } else {                                                    // ENCAPSULATE
        int q = max(0, max(bd[1] - j, j - bd[4]));
        if (q > 12) {
            return 0;
        }
        *v = bt[SC_BT_ENCAP + (i1 * 13 + q) * 13 + j1];
    }
    return 1;
}

// Beardifier.compute at block (i, j, k) over this chunk's packed terms (layout:
// ScBeardBoxAccess.superchunk$beardTerms, without its two-int header).
inline double sc_beard(int i, int j, int k, __global const int* bd, int nPieces, int nJunctions,
                       __global const float* kern, __global const double* bt) {
    double d0 = 0.0;
    for (int p = 0; p < nPieces; ++p, bd += 8) {
        int i1 = max(0, max(bd[0] - i, i - bd[3]));
        int j1 = max(0, max(bd[2] - k, k - bd[5]));
        if (i1 >= 12 || j1 >= 12) {
            continue;
        }
        int k1 = bd[1] + bd[6];
        double v;
        if (sc_beard_piece(bd, i1, j1, j, k1, j - k1, kern, bt, &v)) {
            d0 += v;
        }
    }
    for (int n = 0; n < nJunctions; ++n, bd += 3) {
        int j2 = i - bd[0];
        int k2 = j - bd[1];
        int l2 = k - bd[2];
        if (j2 < -12 || j2 > 11 || k2 < -12 || k2 > 11 || l2 < -12 || l2 > 11) {
            continue;
        }
        d0 += bt[SC_BT_JUNC + (j2 + 12) * 576 + (k2 + 12) * 24 + (l2 + 12)];
    }
    return d0;
}

// sc_beard for the n <= SC_BEARD_RUN blocks (i, j, k0 + l) of one z-run: the same terms,
// with the same zero tests, summed in the same order for each block (out[l] gets the
// pieces in list order, then the junctions). Only the tests that depend on x or y are
// done once for the run instead of once per block.
#define SC_BEARD_RUN 8
inline void sc_beard_run(int i, int j, int k0, int n, __global const int* bd, int nPieces, int nJunctions,
                         __global const float* kern, __global const double* bt, __private double* out) {
    for (int l = 0; l < n; ++l) {
        out[l] = 0.0;
    }
    for (int p = 0; p < nPieces; ++p, bd += 8) {
        int i1 = max(0, max(bd[0] - i, i - bd[3]));
        if (i1 >= 12) {
            continue;
        }
        int k1 = bd[1] + bd[6];
        int l1 = j - k1;
        int adj = bd[7];
        if ((adj == 1 && (i1 > 6 || l1 > 12 || l1 < -12)) || (adj == 2 && (l1 < -12 || l1 > 11))) {
            continue;
        }
        for (int l = 0; l < n; ++l) {
            int k = k0 + l;
            int j1 = max(0, max(bd[2] - k, k - bd[5]));
            double v;
            if (j1 < 12 && sc_beard_piece(bd, i1, j1, j, k1, l1, kern, bt, &v)) {
                out[l] += v;
            }
        }
    }
    for (int m = 0; m < nJunctions; ++m, bd += 3) {
        int j2 = i - bd[0];
        int k2 = j - bd[1];
        if (j2 < -12 || j2 > 11 || k2 < -12 || k2 > 11) {
            continue;
        }
        __global const double* row = bt + SC_BT_JUNC + (j2 + 12) * 576 + (k2 + 12) * 24 + 12;
        for (int l = 0; l < n; ++l) {
            int l2 = k0 + l - bd[2];
            if (l2 >= -12 && l2 <= 11) {
                out[l] += row[l2];
            }
        }
    }
}
