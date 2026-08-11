// Driver kernel for tools/noisebench.c — appended after noise.cl.
//
// Mirrors the shape of the production corner kernel (df_batch_lattice_multichunk):
// one work-item per (chunk, lattice cell) over a 5 x 49 x 5 cell-corner grid, Y-outer /
// X-mid / Z-inner, evaluating `reps` NormalNoise samplers per point. That is the same
// access pattern and the same noise chain the real kernel spends 69% of GPU time in.
__kernel void sc_bench_corner(
        perm_ptr perm,
        const int nOctaves,
        __global const int*  octaveActive,
        __global const real* xo,
        __global const real* yo,
        __global const real* zo,
        __global const real* amplitudes,
        const real lif,
        const real liv,
        const real valueFactor,
        const int dimX, const int dimY, const int dimZ,
        const int reps,
        __global real* out,
        const int total) {
    int gid = get_global_id(0);
    if (gid >= total) return;

    const int n = dimX * dimY * dimZ;
    const int plane = dimX * dimZ;
    int chunkIdx = gid / n;
    int cell = gid - chunkIdx * n;
    int iy = cell / plane;
    int rem = cell - iy * plane;
    int ix = rem / dimZ;
    int iz = rem - ix * dimZ;

    // Spread the chunks over a plausible pregen square so the noise inputs (and hence
    // the permutation gathers) have realistic spatial spread rather than one hot cell.
    int cx = chunkIdx & 31, cz = chunkIdx >> 5;
    real x = (real) (cx * 16 + ix * 4);
    real y = (real) (iy * 8 - 64);
    real z = (real) (cz * 16 + iz * 4);

    real acc = REAL_C(0.0);
    for (int r = 0; r < reps; ++r) {
        // Each rep is a DISTINCT NormalNoise, with its own octave state and its own
        // permutation tables — as in the real kernel, where ~40 noises' tables are live at
        // once. Sharing one table across reps would keep the whole working set in L1 and
        // hide exactly the cache effect this bench exists to measure.
        int ob = r * 2 * nOctaves;
        int pb = ob * 256;
        real off = (real) r * REAL_C(0.25);
        acc += normal_noise(perm + pb, nOctaves, octaveActive + ob, xo + ob, yo + ob, zo + ob,
                            amplitudes + ob, lif, liv, valueFactor, x + off, y, z - off);
    }
    out[gid] = acc;
}
