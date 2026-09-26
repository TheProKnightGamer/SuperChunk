package dev.superchunk.worldgen;

import net.minecraft.world.level.biome.Climate;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Array-backed replica of vanilla {@code Climate.RTree}'s nearest-biome search.
 *
 * <p><b>Why.</b> JFR puts {@code Climate.RTree} at 6.6% of worldgen worker CPU in CPU mode and
 * 10.1% with the GPU offload, and every call also allocates the {@code long[7]} from
 * {@code TargetPoint.toParameterArray()} (4.35 GB sampled over a 37k-chunk pregen). Vanilla walks
 * {@code Node -> Parameter[] -> Parameter} for each of 7 dimensions of every node it tests; here
 * each node's 14 bounds sit contiguously in one {@code int[]} and each subtree's children are
 * numbered consecutively, so testing a child list is a linear scan.
 *
 * <p><b>Why identical.</b> The traversal is the vanilla recursion: children in the same order, the
 * same strict {@code best > distance} tests, and the same warm start. The warm start is not
 * replicated but shared: this class reads and writes the tree's own {@code lastResult}
 * {@code ThreadLocal}, so interleaving with vanilla searches (a custom distance metric, a
 * subclass that falls back) sees exactly the state vanilla would. Two arithmetic changes, both
 * exact:
 * <ul>
 *   <li>Bounds are stored as {@code int} only when every one fits in +/-2^28 (vanilla's are
 *       quantized floats in [-2, 2], i.e. +/-20,000); otherwise the index is not built.</li>
 *   <li>A distance stops accumulating once it reaches the current best, because the caller only
 *       acts when {@code best > distance}. That needs the partial sums to be monotone, which
 *       holds when targets also fit in +/-2^28: each squared term is then below 2^58 and seven
 *       of them cannot overflow. Targets outside that range take the vanilla search.</li>
 * </ul>
 * Summing a node's squared terms in any order yields the same {@code long}, and the parent's
 * recomputation of a returned leaf's distance ({@code metric.distance(leaf1, values)}) is
 * reproduced, so every comparison sees the value vanilla computes.
 */
public final class FlatClimateIndex {

    /** Bounds and targets within +/-2^28 keep seven squared differences below 2^61. */
    private static final int LIMIT = 1 << 28;
    private static final int STRIDE = 14;

    private static final String RTREE = "net.minecraft.world.level.biome.Climate$RTree";
    private static final String NODE = RTREE + "$Node";
    private static final String SUBTREE = RTREE + "$SubTree";
    private static final String LEAF = RTREE + "$Leaf";

    /** Leaves carry their index here in production (a mixin adds it); tests use the identity map. */
    public interface LeafSlot {
        int superchunk$flatIndex();

        void superchunk$setFlatIndex(int index);
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SuperChunk-ClimateSearch");
    private static final AtomicLong VERIFIED = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();

    /** Verify mode: one flat answer compared with vanilla's from the same warm start. */
    public static void recordVerify(Climate.TargetPoint target, Object flat, Object vanilla, boolean sameLeaf) {
        long checked = VERIFIED.incrementAndGet();
        if (flat != vanilla || !sameLeaf) {
            if (MISMATCHES.incrementAndGet() <= 20) {
                LOGGER.error("[flat-climate] VERIFY MISMATCH at {}: flat={} vanilla={} sameLeaf={}",
                        target, flat, vanilla, sameLeaf);
            }
        }
        if ((checked & ((1L << 22) - 1)) == 0) {
            LOGGER.info("[flat-climate] verify: checked={} mismatches={}", checked, MISMATCHES.get());
        }
    }

    /** Verify-mode summary; zero mismatches over a pregen is the gate. */
    public static void reportVerify() {
        long m = MISMATCHES.get();
        LOGGER.info("[flat-climate] VERIFY: checked={} MISMATCHES={} -> {}", VERIFIED.get(), m, m == 0 ? "PASS" : "FAIL");
    }

    private final int[] bounds;
    /** First child's node index, or -1 for a leaf. Children of a subtree are consecutive. */
    private final int[] first;
    private final int[] count;
    private final Object[] leaf;
    private final Object[] value;
    private final ThreadLocal<Object> lastResult;
    private final IdentityHashMap<Object, Integer> leafIndex;

    private FlatClimateIndex(int[] bounds, int[] first, int[] count, Object[] leaf, Object[] value,
                             ThreadLocal<Object> lastResult, IdentityHashMap<Object, Integer> leafIndex) {
        this.bounds = bounds;
        this.first = first;
        this.count = count;
        this.leaf = leaf;
        this.value = value;
        this.lastResult = lastResult;
        this.leafIndex = leafIndex;
    }

    /**
     * The value vanilla's {@code RTree.search(target, Node::distance)} returns, or {@code null}
     * when the target is outside the exact range; the caller then runs vanilla's search.
     * A tree never stores a null value in a reachable leaf used here: {@link #build} refuses one.
     */
    public Object search(Climate.TargetPoint target) {
        long l0 = target.temperature(), l1 = target.humidity(), l2 = target.continentalness(),
                l3 = target.erosion(), l4 = target.depth(), l5 = target.weirdness();
        if (!fits(l0) || !fits(l1) || !fits(l2) || !fits(l3) || !fits(l4) || !fits(l5)) {
            return null;
        }
        int t0 = (int) l0, t1 = (int) l1, t2 = (int) l2, t3 = (int) l3, t4 = (int) l4, t5 = (int) l5;
        ThreadLocal<Object> last = this.lastResult;
        Object warmLeaf = last.get();
        int best;
        if (this.first[0] < 0) {
            best = 0; // the root is the only leaf; vanilla's Leaf.search returns itself
        } else {
            long bestDist;
            if (warmLeaf == null) {
                best = -1;
                bestDist = Long.MAX_VALUE;
            } else {
                best = indexOf(warmLeaf);
                if (best < 0) {
                    return null; // not one of this tree's leaves: let vanilla decide
                }
                bestDist = distance(best, Long.MAX_VALUE, t0, t1, t2, t3, t4, t5);
            }
            best = searchSub(0, best, bestDist, t0, t1, t2, t3, t4, t5);
        }
        Object result = this.leaf[best];
        if (result != warmLeaf) {
            last.set(result);
        }
        return this.value[best];
    }

    /** The calling thread's warm-start leaf: vanilla's {@code lastResult} for this tree. */
    public Object warmStart() {
        return this.lastResult.get();
    }

    public void setWarmStart(Object leafObject) {
        this.lastResult.set(leafObject);
    }

    private int searchSub(int node, int best, long bestDist, int t0, int t1, int t2, int t3, int t4, int t5) {
        final int[] first = this.first;
        int c = first[node];
        final int end = c + this.count[node];
        for (; c < end; c++) {
            long d = distance(c, bestDist, t0, t1, t2, t3, t4, t5);
            if (bestDist > d) {
                if (first[c] < 0) {
                    best = c;
                    bestDist = d;
                } else {
                    int found = searchSub(c, best, bestDist, t0, t1, t2, t3, t4, t5);
                    if (found != best) {
                        // A subtree only returns a different leaf when it is strictly closer.
                        best = found;
                        bestDist = distance(found, Long.MAX_VALUE, t0, t1, t2, t3, t4, t5);
                    }
                }
            }
        }
        return best;
    }

    /** Vanilla {@code Node.distance}; a result {@code >= limit} may be a partial sum. */
    private long distance(int node, long limit, int t0, int t1, int t2, int t3, int t4, int t5) {
        final int[] b = this.bounds;
        int o = node * STRIDE;
        // Integer sums are order-independent; test the usually most selective axes first
        // (continentalness, erosion, then weirdness and depth) so rejections exit early.
        long s = term(t2, b[o + 4], b[o + 5]) + term(t3, b[o + 6], b[o + 7]);
        if (s >= limit) {
            return s;
        }
        s += term(t5, b[o + 10], b[o + 11]) + term(t4, b[o + 8], b[o + 9]);
        if (s >= limit) {
            return s;
        }
        // TargetPoint.toParameterArray() fixes the seventh (offset) coordinate at 0.
        return s + term(t0, b[o], b[o + 1]) + term(t1, b[o + 2], b[o + 3]) + term(0, b[o + 12], b[o + 13]);
    }

    /** {@code Mth.square(Parameter.distance(v))} for bounds and value within +/-2^28. */
    private static long term(int v, int min, int max) {
        long above = (long) v - max;
        long below = (long) min - v;
        long d = above > 0L ? above : Math.max(below, 0L);
        return d * d;
    }

    private int indexOf(Object leafObject) {
        if (leafObject instanceof LeafSlot slot) {
            int index = slot.superchunk$flatIndex();
            return index >= 0 && index < this.leaf.length && this.leaf[index] == leafObject ? index : -1;
        }
        Integer index = this.leafIndex.get(leafObject);
        return index == null ? -1 : index;
    }

    private static boolean fits(long v) {
        return v >= -LIMIT && v <= LIMIT;
    }

    // ---------------------------------------------------------------- construction

    /**
     * Flattens the {@code Climate.RTree} behind {@code list}, or returns {@code null} when its shape
     * is not exactly vanilla's: unknown node classes, a dimension count other than 7, bounds
     * outside +/-2^28, null values, or leaves shared with another index. Callers keep the vanilla
     * search in that case. Must be called at most once per tree (callers synchronize).
     */
    public static FlatClimateIndex build(Climate.ParameterList<?> list) {
        try {
            return buildTree(Access.get().listIndex.get(list));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return refuse(e.toString());
        }
    }

    /** Why the last {@link #build} on this thread returned {@code null}. */
    private static final ThreadLocal<String> REFUSAL = new ThreadLocal<>();

    public static String lastRefusal() {
        return REFUSAL.get();
    }

    private static FlatClimateIndex refuse(String reason) {
        REFUSAL.set(reason);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static FlatClimateIndex buildTree(Object rtree) {
        try {
            Access a = Access.get();
            if (rtree == null || rtree.getClass() != a.rtree) {
                return refuse("not a vanilla RTree: " + rtree);
            }
            Object root = a.root.get(rtree);
            ThreadLocal<Object> lastResult = (ThreadLocal<Object>) a.lastResult.get(rtree);
            if (root == null || lastResult == null) {
                return refuse("missing root or warm-start state");
            }
            // Breadth-first numbering: a subtree's children receive consecutive indices.
            List<Object> nodes = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            nodes.add(root);
            queue.add(0);
            List<int[]> ranges = new ArrayList<>();
            ranges.add(null);
            while (!queue.isEmpty()) {
                int index = queue.poll();
                Object node = nodes.get(index);
                if (node.getClass() == a.subtree) {
                    Object[] children = (Object[]) a.children.get(node);
                    if (children == null || children.length == 0) {
                        return refuse("empty subtree");
                    }
                    ranges.set(index, new int[] {nodes.size(), children.length});
                    for (Object child : children) {
                        if (child == null) {
                            return refuse("null child");
                        }
                        queue.add(nodes.size());
                        nodes.add(child);
                        ranges.add(null);
                    }
                } else if (node.getClass() != a.leaf) {
                    return refuse("unknown node class " + node.getClass().getName());
                }
            }
            int n = nodes.size();
            int[] bounds = new int[n * STRIDE];
            int[] first = new int[n];
            int[] count = new int[n];
            Object[] leaves = new Object[n];
            Object[] values = new Object[n];
            IdentityHashMap<Object, Integer> leafIndex = new IdentityHashMap<>();
            for (int i = 0; i < n; i++) {
                Object node = nodes.get(i);
                Climate.Parameter[] space = (Climate.Parameter[]) a.parameterSpace.get(node);
                if (space == null || space.length != 7) {
                    return refuse("parameter space is not 7-dimensional");
                }
                for (int d = 0; d < 7; d++) {
                    Climate.Parameter p = space[d];
                    if (p == null || !fits(p.min()) || !fits(p.max())) {
                        return refuse("bounds outside +/-2^28: " + p);
                    }
                    bounds[i * STRIDE + 2 * d] = (int) p.min();
                    bounds[i * STRIDE + 2 * d + 1] = (int) p.max();
                }
                int[] range = ranges.get(i);
                if (range == null) {
                    Object v = a.value.get(node);
                    if (v == null || leafIndex.put(node, i) != null) {
                        return refuse(v == null ? "null leaf value" : "leaf reachable twice");
                    }
                    first[i] = -1;
                    leaves[i] = node;
                    values[i] = v;
                } else {
                    first[i] = range[0];
                    count[i] = range[1];
                }
            }
            // Record indices last, and only on leaves no other index has claimed.
            for (int i = 0; i < n; i++) {
                if (leaves[i] instanceof LeafSlot slot && slot.superchunk$flatIndex() >= 0) {
                    return refuse("leaf already indexed by another list");
                }
            }
            for (int i = 0; i < n; i++) {
                if (leaves[i] instanceof LeafSlot slot) {
                    slot.superchunk$setFlatIndex(i);
                }
            }
            return new FlatClimateIndex(bounds, first, count, leaves, values, lastResult, leafIndex);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return refuse(e.toString());
        }
    }

    /** Reflective access to the package-private tree classes, resolved once; used only while building. */
    private static final class Access {
        private static volatile Access instance;

        final Class<?> rtree;
        final Class<?> subtree;
        final Class<?> leaf;
        final Field root;
        final Field lastResult;
        final Field children;
        final Field parameterSpace;
        final Field value;
        final Field listIndex;

        private Access() throws ReflectiveOperationException {
            ClassLoader loader = Climate.class.getClassLoader();
            this.rtree = Class.forName(RTREE, false, loader);
            Class<?> node = Class.forName(NODE, false, loader);
            this.subtree = Class.forName(SUBTREE, false, loader);
            this.leaf = Class.forName(LEAF, false, loader);
            this.root = handle(this.rtree, "root");
            this.lastResult = handle(this.rtree, "lastResult");
            this.children = handle(this.subtree, "children");
            this.parameterSpace = handle(node, "parameterSpace");
            this.value = handle(this.leaf, "value");
            this.listIndex = handle(Climate.ParameterList.class, "index");
        }

        private static Field handle(Class<?> owner, String name) throws ReflectiveOperationException {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        static Access get() throws ReflectiveOperationException {
            Access a = instance;
            if (a == null) {
                a = new Access();
                instance = a;
            }
            return a;
        }
    }
}
