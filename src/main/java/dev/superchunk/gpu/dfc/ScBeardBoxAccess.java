package dev.superchunk.gpu.dfc;

/**
 * SuperChunk compact-ids (Stage 5): exposes the per-chunk beard AABB the
 * beardSkip machinery already computes ({@code MixinStructureWeightSampler}'s
 * union of every contributing piece/junction expanded by the kernel reach,
 * proven exact over 1.738B checks). Implemented by the {@code Beardifier}
 * mixin; called WORKER-side at the batch-prefetch seam so the aux capture can
 * ship the box to the decide kernel (blocks inside it get the beard-sentinel
 * byte and stay CPU-decided).
 */
public interface ScBeardBoxAccess {

    /**
     * Forces the piece/junction arrays + bounds to be computed if they have not
     * been yet (safe: the prefetch runs strictly before this chunk's fill, on the
     * gen path of the same chunk), then fills {@code out6} with
     * {@code minX,minY,minZ,maxX,maxY,maxZ} (inclusive) and returns whether any
     * piece/junction contributes at all ({@code false} = no beard box; the array
     * contents are undefined then).
     */
    boolean superchunk$beardBox(int[] out6);

    /**
     * The chunk's beard terms for the decide kernel to sum ({@code sc_beard}, decide.cl), or
     * {@code null} when they cannot be shipped (a terrain adjustment this code does not know, or
     * more than {@code maxInts}). Same order as {@code compute()} sums them. Layout:
     * {@code nPieces, nJunctions}, then per piece {@code minX, minY, minZ, maxX, maxY, maxZ,
     * groundLevelDelta, adjustment} (1 BURY, 2 BEARD_THIN, 3 BEARD_BOX, 4 ENCAPSULATE), then per
     * junction {@code sourceX, sourceGroundY, sourceZ}. NONE pieces are left out: they add 0.0, and
     * the sum is never -0.0, so leaving out a 0.0 term changes nothing. Call after
     * {@link #superchunk$beardBox} returned true.
     */
    int[] superchunk$beardTerms(int maxInts);

    /** Doubles in {@link #beardTables()}' layout. */
    int BEARD_TABLES_LEN = 31070;

    /**
     * Every beard term the decide kernel looks up instead of computing ({@code sc_beard_run},
     * decide.cl), each one computed here by the very functions {@code compute()} calls, times the
     * same factor, so a lookup is that term's exact double. Indexes by the term's integer
     * distances: [0, 13824) junctions, {@code getBeardContribution(x, y, z, y) * 0.4} at
     * {@code ((x+12)*24 + (y+12))*24 + (z+12)}; [13824, 27648) BEARD_THIN, the same with
     * {@code * 0.8}; [27648, 28873) BURY at {@code (i1*25 + (l1+12))*7 + j1} for i1, j1 in [0, 6]
     * and l1 in [-12, 12]; [28873, 31070) ENCAPSULATE ({@code * 0.8}) at {@code (i1*13 + q)*13 + j1}
     * for all three in [0, 12] (zeros where the version has no ENCAPSULATE). BEARD_BOX terms also
     * depend on the piece's height and are computed in the kernel.
     */
    double[] superchunk$beardTables();
}
