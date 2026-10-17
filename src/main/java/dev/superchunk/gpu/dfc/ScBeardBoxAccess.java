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
}
