package dev.superchunk.gpu.dfc;

/**
 * (Phase B, optimization #3 — in-kernel coordinate generation.)
 *
 * <p>The chunk-noise density fills C2ME hands us are a <b>regular 3D lattice</b>:
 * {@code NoiseChunk.c2me$fillCoordinates} writes coordinates with three nested
 * loops (outer Y, then X, then Z), so for index {@code i}:
 * <pre>
 *   iy = i / (dx*dz);   rem = i % (dx*dz);
 *   ix = rem / dz;      iz = rem % dz;
 *   x = ox + ix*sx;     y = oy + iy*sy;     z = oz + iz*sz;
 * </pre>
 * When that holds we don't need to upload the x/y/z arrays at all — we pass the
 * 9 lattice scalars (origin, stride, dim per axis) to the kernel and recompute
 * each coordinate from {@code get_global_id(0)}. That eliminates THREE host&rarr;
 * device int-array transfers per dispatch (the dominant upload cost), a gain that
 * carries to the discrete RTX 3070 as well (it removes PCIe traffic, not just
 * iGPU copies).
 *
 * <p>{@link #detect} is conservative: it derives the candidate lattice from the
 * first elements, then <b>verifies every element</b> against it. If anything
 * doesn't match (a non-lattice or differently-strided fill) it returns
 * {@code null} and the caller uploads the arrays as before — so correctness is
 * never at risk.
 */
public final class LatticeCoords {

    public final int ox, oy, oz;   // origins
    public final int sx, sy, sz;   // strides (may be 0 if a dim is 1 or degenerate)
    public final int dx, dy, dz;   // dims (dx*dy*dz == n)

    private LatticeCoords(int ox, int oy, int oz, int sx, int sy, int sz, int dx, int dy, int dz) {
        this.ox = ox; this.oy = oy; this.oz = oz;
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.dx = dx; this.dy = dy; this.dz = dz;
    }

    /**
     * SuperChunk GPU (coalescing): build a lattice directly from a known extent
     * (no detection / verification needed — the caller knows it is a clean grid).
     */
    public static LatticeCoords of(int ox, int oy, int oz, int sx, int sy, int sz, int dx, int dy, int dz) {
        return new LatticeCoords(ox, oy, oz, sx, sy, sz, dx, dy, dz);
    }

    /**
     * Tries to express {@code (x,y,z)[0..n)} as the Y-outer / X-mid / Z-inner
     * lattice above. Returns the lattice on success, or {@code null} if the data
     * is not a clean match (caller falls back to uploading the arrays).
     */
    public static LatticeCoords detect(int[] x, int[] y, int[] z, int n) {
        if (n <= 0) {
            return null;
        }
        if (n == 1) {
            // Trivial 1x1x1 lattice; cheap and exact.
            return new LatticeCoords(x[0], y[0], z[0], 0, 0, 0, 1, 1, 1);
        }

        // --- find inner (Z) dimension: the run length over which y and x are
        //     constant and z advances by a fixed stride from index 0. ---
        int oz = z[0];
        int sz = 0;
        int dz = 1;
        // determine z-stride from index 1 if x,y still equal the origin there.
        // We grow dz while x==x0, y==y0 and z follows the arithmetic progression.
        int x0 = x[0], y0 = y[0];
        if (x[1] == x0 && y[1] == y0) {
            sz = z[1] - z[0];
            dz = 2;
            while (dz < n && x[dz] == x0 && y[dz] == y0 && z[dz] == oz + dz * sz) {
                dz++;
            }
        } else {
            // inner dim is 1 (z does not advance before x/y change)
            dz = 1;
            sz = 0;
        }
        if (n % dz != 0) {
            return null;
        }

        // --- find mid (X) dimension: blocks of dz where x advances by sx,
        //     y constant. ---
        int ox = x[0];
        int sx = 0;
        int dx;
        int planeStride = dz;        // elements per (one x value) = dz
        if (planeStride < n && y[planeStride] == y0) {
            sx = x[planeStride] - x[0];
            dx = 1;
            while ((long) (dx + 1) * dz <= n) {
                int base = dx * dz;
                if (y[base] != y0 || x[base] != ox + dx * sx) {
                    break;
                }
                // z must restart at oz within this block (checked in full verify below)
                dx++;
            }
        } else {
            dx = 1;
            sx = 0;
        }
        if (n % ((long) dx * dz) != 0) {
            return null;
        }

        // --- outer (Y) dimension. ---
        int oy = y[0];
        long block = (long) dx * dz;
        int dy = (int) (n / block);
        int sy = 0;
        if (dy >= 2) {
            sy = y[(int) block] - y[0];
        }
        if ((long) dx * dy * dz != n) {
            return null;
        }

        LatticeCoords cand = new LatticeCoords(ox, oy, oz, sx, sy, sz, dx, dy, dz);
        // --- full verification: every element must match. Guarantees correctness. ---
        if (!cand.verify(x, y, z, n)) {
            return null;
        }
        return cand;
    }

    /** Total lattice point count (dx*dy*dz). */
    public int n() {
        return dx * dy * dz;
    }

    /**
     * (round-4 gridDetectCache) Public full verification: does this lattice describe
     * every {@code (x,y,z)[0..n)} exactly? Used to re-validate a re-anchored cached
     * shape before serving, so correctness never depends on the shape hint being right.
     */
    public boolean matches(int[] x, int[] y, int[] z, int n) {
        return dx * dy * dz == n && verify(x, y, z, n);
    }

    private boolean verify(int[] x, int[] y, int[] z, int n) {
        int i = 0, ey = oy;
        for (int iy = 0; iy < dy; iy++, ey += sy) {
            int ex = ox;
            for (int ix = 0; ix < dx; ix++, ex += sx) {
                int ez = oz;
                for (int iz = 0; iz < dz; iz++, ez += sz) {
                    if (x[i] != ex || y[i] != ey || z[i] != ez) return false;
                    i++;
                }
            }
        }
        return true;
    }
}
