// =====================================================================
// SuperChunk GPU backend — FULL-FIELD interpolation kernel.
//
// STAGE 1 (-Dsuperchunk.gpu.fullFieldBench): PURELY ADDITIVE TIMING — the output
// is read back and DISCARDED; it never feeds terrain. Measures the "on-device
// residency" hypothesis: keep the per-chunk COARSE cell-corner grid (computed by
// the fused df_batch_lattice_multi dispatch) device-resident and do the FULL chunk
// interpolation (16 x 384 x 16 = 98304 blocks per density grid) on the GPU.
//
// STAGE 2 (-Dsuperchunk.gpu.onDeviceInterp[.verify]): the result is KEPT
// (GpuFusedInterpolator.provideFullField reads it into per-thread pinned host
// staging and registers it via OnDeviceInterp). TWO fields are now computed from
// the SAME device-resident corner buffer, differing ONLY in lerp order:
//   * df_full_field_interp        — Y->X->Z (updateForX/Y/Z `this.value` path:
//                                   aquifer/ore DFs, per-root updateForZ).
//   * df_full_field_interp_lerp3  — X->Y->Z (Mth.lerp3 cell-cache `fillingCell`
//                                   path: the DOMINANT finalDensity terrain DF).
// The NoiseChunk fill then consumes per-block values
// out[root*fullN + (by*fullX+bx)*fullZ+bz] instead of running the CPU lerp.
// Both are BIT-EXACT to their vanilla CPU counterparts at fp64. FP lerp is
// non-associative, so the two fields are bit-DISTINCT and each must match its own
// CPU order.
//
// The host PREPENDS noise.cl, which provides `real` / REAL_C() (fp32 vs fp64,
// matching the -DUSE_FP32 build flag), the Mth.lerp port `mc_lerp`, AND
// `#pragma OPENCL FP_CONTRACT OFF` (so a + t*(b-a) is two separately-rounded
// ops, NOT a fused multiply-add — exactly like vanilla NoiseChunk).
//
// Layout (must match OpenCLAstEmitter.emitMulti / ChunkGridCache):
//   * corners[]  — M consecutive grids; root k based at k*cornerN. Within a
//                  grid the flat index is Y-outer / X-mid / Z-inner:
//                      (iy*dimX + ix)*dimZ + iz.
//   * out[]      — M consecutive interpolated fields; root k based at k*fullN,
//                  same Y-outer / X-mid / Z-inner ordering over the block field.
//   * global work size = M * fullN (one work-item per output block per grid).
//
// Trilinear interpolation replicates vanilla: read the 8 cell corners
// n[X][Y][Z] (middle digit = Y, matching updateForY's noise000/noise010), then
// lerp Y first, then X, then Z. mc_lerp(t,a,b) = a + t*(b-a) (NOT fused).
// =====================================================================

__kernel void df_full_field_interp(
        __global const real* corners,                       // device-resident cell-corner grid (M*cornerN reals)
        const int dimX, const int dimY, const int dimZ,     // corner dims (cellCountXZ+1, cellCountY+1, cellCountXZ+1)
        const int sx, const int sy, const int sz,           // cell block sizes (cellWidth, cellHeight, cellWidth)
        const int cornerN,                                  // dimX*dimY*dimZ (per grid)
        const int fullX, const int fullY, const int fullZ,  // interpolated block extents ((dim-1)*cellSize)
        const int fullN,                                    // fullX*fullY*fullZ (per grid)
        const int total,                                    // M*fullN — work-item guard
        __global real* out) {
    int gid = get_global_id(0);
    if (gid >= total) return;

    // Which density grid (root k) and which output block within it.
    int k = gid / fullN;
    int lin = gid - k * fullN;
    int fullPlane = fullX * fullZ;
    int by = lin / fullPlane;              // 0 .. fullY-1
    int rem = lin - by * fullPlane;
    int bx = rem / fullZ;                  // 0 .. fullX-1
    int bz = rem - bx * fullZ;             // 0 .. fullZ-1

    // Cell index + in-cell offset per axis (cell = sx/sy/sz blocks).
    int cx = bx / sx; int lx = bx - cx * sx;
    int cy = by / sy; int ly = by - cy * sy;
    int cz = bz / sz; int lz = bz - cz * sz;

    real dx = (real) lx / (real) sx;
    real dy = (real) ly / (real) sy;
    real dz = (real) lz / (real) sz;

    // The 8 cell corners of cell (cx,cy,cz). Corner flat index (Y-outer/X-mid/
    // Z-inner) = (iy*dimX + ix)*dimZ + iz; root k based at k*cornerN. Naming
    // n[X][Y][Z]: middle digit is Y (matches vanilla updateForY).
    int kb = k * cornerN;
    real n000 = corners[kb + ((cy)     * dimX + (cx))     * dimZ + (cz)];
    real n010 = corners[kb + ((cy + 1) * dimX + (cx))     * dimZ + (cz)];
    real n100 = corners[kb + ((cy)     * dimX + (cx + 1)) * dimZ + (cz)];
    real n110 = corners[kb + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz)];
    real n001 = corners[kb + ((cy)     * dimX + (cx))     * dimZ + (cz + 1)];
    real n011 = corners[kb + ((cy + 1) * dimX + (cx))     * dimZ + (cz + 1)];
    real n101 = corners[kb + ((cy)     * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real n111 = corners[kb + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz + 1)];

    // Vanilla trilinear order: Y first, then X, then Z (mc_lerp = a + t*(b-a)).
    real vXZ00 = mc_lerp(dy, n000, n010);
    real vXZ10 = mc_lerp(dy, n100, n110);
    real vXZ01 = mc_lerp(dy, n001, n011);
    real vXZ11 = mc_lerp(dy, n101, n111);
    real vZ0 = mc_lerp(dx, vXZ00, vXZ10);
    real vZ1 = mc_lerp(dx, vXZ01, vXZ11);
    out[gid] = mc_lerp(dz, vZ0, vZ1);
}

// =====================================================================
// STAGE 2 (-Dsuperchunk.gpu.onDeviceInterp[.verify]) — SECOND full-field
// kernel, identical to df_full_field_interp above EXCEPT the trilinear lerp
// order: X first, then Y, then Z (Mth.lerp3 / NoiseChunk.NoiseInterpolator
// CACHE-FILL order), NOT the Y->X->Z updateForX/Y/Z order.
//
// This serves the DOMINANT finalDensity cell-cache (`fillingCell == true`)
// path: C2ME computes the per-block interpolated value during the per-cell
// fill via Mth.lerp3 (X->Y->Z), and FP lerp is non-associative so this is
// BIT-DISTINCT from df_full_field_interp's Y->X->Z field. Verified against
// vanilla NoiseChunk.NoiseInterpolator.compute (fillingCell branch) +
// Mth.lerp3:
//   * deltas tx=(double)inCellX/cellWidth, ty=(double)inCellY/cellHeight,
//     tz=(double)inCellZ/cellWidth == dx/dy/dz below (in-cell offset / cell
//     size, same as the cell the block falls in);
//   * corner args (n000,n100,n010,n110,n001,n101,n011,n111) ==
//     Mth.lerp3(tx,ty,tz, noise000,noise100,noise010,noise110, noise001,
//     noise101,noise011,noise111) — same 8 corners, same arg order;
//   * mc_lerp3 (noise.cl) == Mth.lerp3 op-for-op (lerp X, then Y, then Z;
//     mc_lerp = a + t*(b-a), FP_CONTRACT OFF).
//
// SAME device-resident corner buffer + SAME decode/output layout
// (root*fullN + (by*fullX+bx)*fullZ+bz) as df_full_field_interp; only the
// final 3-way lerp differs. Reads corners read-only, writes a SEPARATE
// output buffer (the host keeps both fields live for one chunk).
// =====================================================================
__kernel void df_full_field_interp_lerp3(
        __global const real* corners,
        const int dimX, const int dimY, const int dimZ,
        const int sx, const int sy, const int sz,
        const int cornerN,
        const int fullX, const int fullY, const int fullZ,
        const int fullN,
        const int total,
        __global real* out) {
    int gid = get_global_id(0);
    if (gid >= total) return;

    int k = gid / fullN;
    int lin = gid - k * fullN;
    int fullPlane = fullX * fullZ;
    int by = lin / fullPlane;
    int rem = lin - by * fullPlane;
    int bx = rem / fullZ;
    int bz = rem - bx * fullZ;

    int cx = bx / sx; int lx = bx - cx * sx;
    int cy = by / sy; int ly = by - cy * sy;
    int cz = bz / sz; int lz = bz - cz * sz;

    real dx = (real) lx / (real) sx;
    real dy = (real) ly / (real) sy;
    real dz = (real) lz / (real) sz;

    // Same 8 corners, same flat index (Y-outer/X-mid/Z-inner), naming n[X][Y][Z].
    int kb = k * cornerN;
    real n000 = corners[kb + ((cy)     * dimX + (cx))     * dimZ + (cz)];
    real n010 = corners[kb + ((cy + 1) * dimX + (cx))     * dimZ + (cz)];
    real n100 = corners[kb + ((cy)     * dimX + (cx + 1)) * dimZ + (cz)];
    real n110 = corners[kb + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz)];
    real n001 = corners[kb + ((cy)     * dimX + (cx))     * dimZ + (cz + 1)];
    real n011 = corners[kb + ((cy + 1) * dimX + (cx))     * dimZ + (cz + 1)];
    real n101 = corners[kb + ((cy)     * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real n111 = corners[kb + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz + 1)];

    // Mth.lerp3 order (X -> Y -> Z): mc_lerp3 is op-for-op identical to vanilla
    // Mth.lerp3 with this exact corner argument order.
    out[gid] = mc_lerp3(dx, dy, dz, n000, n100, n010, n110, n001, n101, n011, n111);
}
