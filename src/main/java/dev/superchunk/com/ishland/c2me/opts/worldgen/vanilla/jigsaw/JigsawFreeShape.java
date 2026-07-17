package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.jigsaw;

import net.minecraft.world.phys.AABB;

/**
 * SuperChunk clean-room replacement for the per-region "free space" {@code VoxelShape}
 * that {@code JigsawPlacement.Placer.tryPlacingChildren} accumulates while assembling a
 * jigsaw structure.
 *
 * <p>A free region in vanilla is exactly {@code positiveBoundary - union(placed child boxes)}:
 * it is initialised to a single block-aligned AABB (the parent piece's bounding box, or for the
 * start piece the placement bounds minus the start box) and every accepted child's box is
 * subtracted from it via {@code joinUnoptimized(free, childBox, ONLY_FIRST)}. The ONLY query
 * vanilla makes is {@code joinIsNotEmpty(free, deflatedCandidate, ONLY_SECOND)}, which asks
 * "does the candidate stick out of the free region", i.e. is the candidate not fully inside the
 * boundary, OR does it intersect an already-placed box.
 *
 * <p>This class represents that region as the boundary AABB plus a loose octree of the placed
 * (negative) boxes, so the intersection query is ~O(log n) instead of vanilla's per-candidate
 * VoxelShape merge over a shape that grows with every placed piece (~O(n^2) overall).
 *
 * <p><b>Why this is bit-identical to vanilla:</b> vanilla's shapes are all block-aligned AABBs and
 * the candidate is {@code AABB.of(box).deflate(0.25)}, so every comparison has at least 0.25 of
 * slack — far larger than VoxelShape's 1e-7 boolean-op epsilon — and plain double comparisons
 * reproduce the VoxelShape boolean result exactly. This is verified at runtime (see
 * {@link JigsawShapeTracker}, flag {@code superchunk.worldgen.jigsawOctree.verify}).
 *
 * <p>Technique inspired by TelepathicGrunt's Structure Layout Optimizer (MIT). This is an
 * independent clean-room implementation; no code was copied.
 */
public final class JigsawFreeShape {

    /** Matches VoxelShape's boolean-op epsilon; irrelevant given the >=0.25 deflate slack, kept for fidelity. */
    private static final double EPS = 1.0E-7;

    private final double pMinX, pMinY, pMinZ, pMaxX, pMaxY, pMaxZ; // positive boundary
    private final BoxOctree placed;                                // subtracted (negative) boxes

    public JigsawFreeShape(AABB boundary) {
        this.pMinX = boundary.minX;
        this.pMinY = boundary.minY;
        this.pMinZ = boundary.minZ;
        this.pMaxX = boundary.maxX;
        this.pMaxY = boundary.maxY;
        this.pMaxZ = boundary.maxZ;
        this.placed = new BoxOctree(boundary.minX, boundary.minY, boundary.minZ, boundary.maxX, boundary.maxY, boundary.maxZ, 0);
    }

    /** Subtract a placed child box from the free region (== {@code joinUnoptimized(free, box, ONLY_FIRST)}). */
    public void addBox(AABB box) {
        this.placed.add(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /**
     * Equivalent to {@code Shapes.joinIsNotEmpty(this, Shapes.create(cand), ONLY_SECOND)}:
     * returns true iff {@code cand} is NOT fully contained in the free region — i.e. it pokes
     * past the boundary, or it overlaps a previously-placed box.
     */
    public boolean candidateSticksOut(AABB cand) {
        // Outside the positive boundary?
        if (cand.minX < pMinX - EPS || cand.maxX > pMaxX + EPS
                || cand.minY < pMinY - EPS || cand.maxY > pMaxY + EPS
                || cand.minZ < pMinZ - EPS || cand.maxZ > pMaxZ + EPS) {
            return true;
        }
        // Overlaps an already-placed (subtracted) box?
        return this.placed.intersects(cand.minX, cand.minY, cand.minZ, cand.maxX, cand.maxY, cand.maxZ);
    }

    /**
     * Loose octree of axis-aligned boxes. A box that fits wholly inside one child octant descends;
     * a box straddling a split plane is stored at the node. Queries prune octants the query box
     * cannot reach. Two boxes touching only at a face do NOT count as intersecting (EPS), matching
     * VoxelShape; with the candidate's 0.25 deflation this never actually arises.
     */
    private static final class BoxOctree {

        private static final int MAX_DEPTH = 4;
        private static final int SUBDIVIDE_THRESHOLD = 12;

        private final double minX, minY, minZ, maxX, maxY, maxZ;
        private final int depth;

        // Flat box store at this node: groups of 6 doubles {minX,minY,minZ,maxX,maxY,maxZ}.
        private double[] boxes = null;
        private int count = 0; // number of boxes (each = 6 doubles)
        private BoxOctree[] children = null;

        BoxOctree(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int depth) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
            this.depth = depth;
        }

        void add(double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
            if (children != null) {
                int oct = octantFor(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
                if (oct >= 0) {
                    children[oct].add(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
                    return;
                }
                // straddles a split plane -> keep at this node
                store(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
                return;
            }
            store(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
            if (count > SUBDIVIDE_THRESHOLD && depth < MAX_DEPTH) {
                subdivide();
            }
        }

        private void store(double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
            if (boxes == null) {
                boxes = new double[6 * 8];
            } else if (count * 6 == boxes.length) {
                double[] grown = new double[boxes.length * 2];
                System.arraycopy(boxes, 0, grown, 0, boxes.length);
                boxes = grown;
            }
            int i = count * 6;
            boxes[i] = bMinX; boxes[i + 1] = bMinY; boxes[i + 2] = bMinZ;
            boxes[i + 3] = bMaxX; boxes[i + 4] = bMaxY; boxes[i + 5] = bMaxZ;
            count++;
        }

        private void subdivide() {
            double midX = (minX + maxX) * 0.5, midY = (minY + maxY) * 0.5, midZ = (minZ + maxZ) * 0.5;
            children = new BoxOctree[8];
            for (int o = 0; o < 8; o++) {
                double cx0 = (o & 1) == 0 ? minX : midX, cx1 = (o & 1) == 0 ? midX : maxX;
                double cy0 = (o & 2) == 0 ? minY : midY, cy1 = (o & 2) == 0 ? midY : maxY;
                double cz0 = (o & 4) == 0 ? minZ : midZ, cz1 = (o & 4) == 0 ? midZ : maxZ;
                children[o] = new BoxOctree(cx0, cy0, cz0, cx1, cy1, cz1, depth + 1);
            }
            double[] old = boxes;
            int oldCount = count;
            boxes = null;
            count = 0;
            for (int b = 0; b < oldCount; b++) {
                int i = b * 6;
                add(old[i], old[i + 1], old[i + 2], old[i + 3], old[i + 4], old[i + 5]);
            }
        }

        /** Index of the child octant wholly containing the box, or -1 if it straddles a split plane. */
        private int octantFor(double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
            double midX = (minX + maxX) * 0.5, midY = (minY + maxY) * 0.5, midZ = (minZ + maxZ) * 0.5;
            int o;
            if (bMaxX <= midX) o = 0; else if (bMinX >= midX) o = 1; else return -1;
            if (bMaxY <= midY) o |= 0; else if (bMinY >= midY) o |= 2; else return -1;
            if (bMaxZ <= midZ) o |= 0; else if (bMinZ >= midZ) o |= 4; else return -1;
            return o;
        }

        boolean intersects(double qMinX, double qMinY, double qMinZ, double qMaxX, double qMaxY, double qMaxZ) {
            for (int b = 0; b < count; b++) {
                int i = b * 6;
                if (qMinX < boxes[i + 3] - EPS && qMaxX > boxes[i] + EPS
                        && qMinY < boxes[i + 4] - EPS && qMaxY > boxes[i + 1] + EPS
                        && qMinZ < boxes[i + 5] - EPS && qMaxZ > boxes[i + 2] + EPS) {
                    return true;
                }
            }
            if (children != null) {
                for (BoxOctree c : children) {
                    if (qMinX < c.maxX && qMaxX > c.minX
                            && qMinY < c.maxY && qMaxY > c.minY
                            && qMinZ < c.maxZ && qMaxZ > c.minZ
                            && c.intersects(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
