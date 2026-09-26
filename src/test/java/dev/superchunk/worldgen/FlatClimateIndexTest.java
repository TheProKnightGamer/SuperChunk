package dev.superchunk.worldgen;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

/**
 * Differential checks of {@link FlatClimateIndex} against the unmodified vanilla R-tree.
 * Every lookup runs both searches from the same warm-start leaf and compares the returned value
 * and the warm-start leaf each search leaves behind. Pass {@code bench} to also time both.
 */
public final class FlatClimateIndexTest {
    private static long cases;

    public static void main(String[] args) {
        Random random = new Random(0xc11_3a7eL);
        Map<MultiNoiseBiomeSourceParameterList.Preset, Climate.ParameterList<Object>> presets = presets();
        for (var entry : presets.entrySet()) {
            Climate.ParameterList<Object> list = entry.getValue();
            FlatClimateIndex flat = FlatClimateIndex.build(list);
            check(flat != null, "vanilla preset " + entry.getKey() + " must be indexable: " + FlatClimateIndex.lastRefusal());
            exercise(list, flat, random, list.values());
        }
        // Synthetic shapes: a single-leaf root, one-level roots, duplicate points (exact ties),
        // and enough entries for several levels.
        for (int size : new int[] {1, 2, 5, 6, 7, 36, 37, 250}) {
            for (int dup = 0; dup < 2; dup++) {
                List<Pair<Climate.ParameterPoint, Object>> values = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    Climate.ParameterPoint point = dup == 1 && i % 3 == 1 ? values.get(i - 1).getFirst() : randomPoint(random);
                    values.add(Pair.of(point, "v" + i));
                }
                Climate.ParameterList<Object> list = new Climate.ParameterList<>(values);
                FlatClimateIndex flat = FlatClimateIndex.build(list);
                check(flat != null, "synthetic list of " + size + " must be indexable");
                exercise(list, flat, random, values);
            }
        }
        // Values outside the exact integer range are refused (the caller then runs vanilla)
        // without touching the warm start.
        FlatClimateIndex flat = FlatClimateIndex.build(presets.values().iterator().next());
        Object warm = flat.warmStart();
        for (long extreme : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, (1L << 28) + 1, -(1L << 28) - 1}) {
            check(flat.search(new Climate.TargetPoint(0, 0, extreme, 0, 0, 0)) == null, "range guard");
            check(flat.warmStart() == warm, "range guard must not move the warm start");
        }
        List<Pair<Climate.ParameterPoint, Object>> wide = List.of(
                Pair.of(new Climate.ParameterPoint(new Climate.Parameter(0, 1L << 40), p(0), p(0), p(0), p(0), p(0), 0), "a"),
                Pair.of(randomPoint(random), "b"));
        check(FlatClimateIndex.build(new Climate.ParameterList<>(wide)) == null, "wide bounds must not be indexed");
        System.out.println("FlatClimateIndex: " + cases + " lockstep lookups matched vanilla value and warm start");
        if (args.length > 0 && args[0].equals("bench")) {
            bench(presets.get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD), random);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<MultiNoiseBiomeSourceParameterList.Preset, Climate.ParameterList<Object>> presets() {
        return (Map) MultiNoiseBiomeSourceParameterList.knownPresets();
    }

    private static void exercise(Climate.ParameterList<Object> list, FlatClimateIndex flat, Random random,
                                 List<Pair<Climate.ParameterPoint, Object>> values) {
        flat.setWarmStart(null);
        // Uniform points, including far outside every region.
        for (int i = 0; i < 20_000; i++) {
            compare(list, flat, new Climate.TargetPoint(r(random, 30_000), r(random, 30_000), r(random, 30_000),
                    r(random, 30_000), r(random, 30_000), r(random, 30_000)));
        }
        // Coherent random walks, the shape of real quart-by-quart sampling.
        long[] t = new long[6];
        for (int walk = 0; walk < 40; walk++) {
            for (int d = 0; d < 6; d++) t[d] = r(random, 15_000);
            for (int step = 0; step < 5_000; step++) {
                for (int d = 0; d < 6; d++) t[d] += random.nextInt(401) - 200;
                compare(list, flat, new Climate.TargetPoint(t[0], t[1], t[2], t[3], t[4], t[5]));
            }
        }
        // Region corners and edges, where equal distances are common and the warm start decides.
        for (int i = 0; i < 20_000; i++) {
            Climate.ParameterPoint a = values.get(random.nextInt(values.size())).getFirst();
            List<Climate.Parameter> space = List.of(a.temperature(), a.humidity(), a.continentalness(),
                    a.erosion(), a.depth(), a.weirdness());
            long[] v = new long[6];
            for (int d = 0; d < 6; d++) {
                Climate.Parameter p = space.get(d);
                v[d] = switch (random.nextInt(5)) {
                    case 0 -> p.min();
                    case 1 -> p.max();
                    case 2 -> p.min() - 1;
                    case 3 -> p.max() + 1;
                    default -> (p.min() + p.max()) / 2;
                };
            }
            compare(list, flat, new Climate.TargetPoint(v[0], v[1], v[2], v[3], v[4], v[5]));
        }
    }

    private static void compare(Climate.ParameterList<Object> list, FlatClimateIndex flat, Climate.TargetPoint target) {
        Object warm = flat.warmStart();
        Object flatValue = flat.search(target);
        Object flatLeaf = flat.warmStart();
        flat.setWarmStart(warm);
        Object vanillaValue = list.findValue(target);
        check(flatValue != null, "in-range target refused: " + target);
        check(flatValue == vanillaValue, "value mismatch at " + target + ": " + flatValue + " vs " + vanillaValue);
        check(flatLeaf == flat.warmStart(), "warm-start mismatch at " + target);
        cases++;
    }

    private static void bench(Climate.ParameterList<Object> list, Random random) {
        FlatClimateIndex flat = FlatClimateIndex.build(list);
        int n = 400_000;
        Climate.TargetPoint[] targets = new Climate.TargetPoint[n];
        long[] t = new long[6];
        for (int i = 0; i < n; i++) {
            if (i % 2_000 == 0) for (int d = 0; d < 6; d++) t[d] = r(random, 12_000);
            for (int d = 0; d < 6; d++) t[d] += random.nextInt(201) - 100;
            targets[i] = new Climate.TargetPoint(t[0], t[1], t[2], t[3], t[4], t[5]);
        }
        long sink = 0;
        for (int round = 0; round < 8; round++) {
            long a = System.nanoTime();
            for (Climate.TargetPoint target : targets) sink += list.findValue(target).hashCode();
            long b = System.nanoTime();
            for (Climate.TargetPoint target : targets) sink += flat.search(target).hashCode();
            long c = System.nanoTime();
            System.out.printf("round %d: vanilla %.1f ns/lookup, flat %.1f ns/lookup (%.2fx)%n", round,
                    (b - a) / (double) n, (c - b) / (double) n, (b - a) / (double) (c - b));
        }
        System.out.println("sink " + sink);
    }

    private static Climate.ParameterPoint randomPoint(Random random) {
        return new Climate.ParameterPoint(span(random), span(random), span(random), span(random), span(random),
                span(random), random.nextInt(3) * 1000L);
    }

    private static Climate.Parameter span(Random random) {
        long a = r(random, 20_000), b = r(random, 20_000);
        return new Climate.Parameter(Math.min(a, b), Math.max(a, b));
    }

    private static Climate.Parameter p(long v) {
        return new Climate.Parameter(v, v);
    }

    private static long r(Random random, int bound) {
        return random.nextInt(2 * bound + 1) - bound;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
