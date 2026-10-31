package dev.superchunk.compat;

import dev.superchunk.worldgen.ColumnMemoDensity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Distant Horizons' rough surface search, evaluated through {@link ColumnMemoDensity} (see
 * {@code MixinDhRoughSurfaceDensity}).
 *
 * <p>{@code -Dsuperchunk.dh.roughVerify=true} also evaluates DH's own function at every point and
 * compares the bits (reported at server stop). {@code -Dsuperchunk.dh.roughBench=<columns>} runs
 * DH's own {@code findSurfaceHeight} over that many columns of {@code .dim} (the overworld by
 * default) when the server has started,
 * alternately with DH's evaluation and with the column memo, and logs the time per column and
 * whether every surface height matched.
 */
public final class DhRoughSurface {
    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");
    private static final boolean VERIFY = Boolean.getBoolean("superchunk.dh.roughVerify");
    private static final LongAdder CHECKED = new LongAdder();
    private static final LongAdder MISMATCHES = new LongAdder();
    private static final AtomicInteger REPORTED = new AtomicInteger();

    /** The benchmark's switch: false evaluates DH's own function. */
    private static volatile boolean memo = true;

    private DhRoughSurface() {
    }

    /** What DH's {@code density.compute(context)} returns, with the height-independent parts kept per column. */
    public static double evaluate(DensityFunction density, DensityFunction.FunctionContext context) {
        if (!memo) {
            return density.compute(context);
        }
        double value = ColumnMemoDensity.forThisThread(density).compute(context);
        if (VERIFY) {
            double own = density.compute(context);
            CHECKED.increment();
            if (Double.doubleToRawLongBits(own) != Double.doubleToRawLongBits(value)) {
                MISMATCHES.increment();
                if (REPORTED.getAndIncrement() < 10) {
                    LOGGER.warn("[SuperChunk-Compat] [dh] rough surface density VERIFY MISMATCH at ({}, {}, {}): DH {} vs {}",
                            context.blockX(), context.blockY(), context.blockZ(), own, value);
                }
            }
            return own;
        }
        return value;
    }

    public static void reportVerify() {
        if (VERIFY) {
            long mismatches = MISMATCHES.sum();
            LOGGER.info("[SuperChunk-Compat] [dh] rough surface density VERIFY: checked={} mismatches={} -> {}",
                    CHECKED.sum(), mismatches, mismatches == 0 ? "PASS" : "FAIL");
        }
    }

    /** {@code -Dsuperchunk.dh.roughBench}: DH's surface search on one dimension, DH's evaluation vs the memo. */
    public static void runBench(MinecraftServer server) {
        int columns = Integer.getInteger("superchunk.dh.roughBench", 0);
        int threads = Integer.getInteger("superchunk.dh.roughBench.threads", 8);
        int rounds = Integer.getInteger("superchunk.dh.roughBench.rounds", 3);
        if (columns <= 0) {
            return;
        }
        try {
            String dimension = System.getProperty("superchunk.dh.roughBench.dim", "minecraft:overworld");
            ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(dimension)));
            if (level == null) {
                LOGGER.warn("[dh-rough-bench] no dimension {}", dimension);
                return;
            }
            String pkg = "com.seibel.distanthorizons.common.wrappers.";
            Object wrapper = Class.forName(pkg + "world.ServerLevelWrapper_neoforge")
                    .getMethod("getWrapper", ServerLevel.class).invoke(null, level);
            Class<?> generator = Class.forName(pkg + "worldGeneration.DhRoughSurfaceGenerator_neoforge");
            Class<?> paramsClass = Class.forName(pkg + "worldGeneration.DhRoughSurfaceGenerator$GenParams_neoforge");
            Constructor<?> constructor = paramsClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object params = constructor.newInstance(null, wrapper); // the chunk generator is only used for superflat worlds
            Method find = null;
            for (Method method : generator.getDeclaredMethods()) {
                if (method.getName().equals("findSurfaceHeight")) {
                    find = method;
                }
            }
            if (find == null) {
                LOGGER.warn("[dh-rough-bench] DH's findSurfaceHeight not found");
                return;
            }
            find.setAccessible(true);
            Method search = find;
            // Columns 32 blocks apart (the spacing of a detail-level-4 LOD), far from spawn (End: its outer islands).
            int[] xs = new int[columns];
            int[] zs = new int[columns];
            int side = (int) Math.ceil(Math.sqrt(columns));
            for (int i = 0; i < columns; i++) {
                xs[i] = 40_000 + (i / side) * 32;
                zs[i] = -25_000 + (i % side) * 32;
            }
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                int[] reference = null;
                long[][] nanos = new long[2][rounds + 1];
                boolean allSame = true;
                for (int round = 0; round <= rounds; round++) { // round 0 warms up both
                    for (int mode = 0; mode < 2; mode++) {
                        memo = mode == 1;
                        int[] heights = new int[columns];
                        long start = System.nanoTime();
                        List<Future<?>> parts = new ArrayList<>();
                        for (int t = 0; t < threads; t++) {
                            int from = columns * t / threads;
                            int to = columns * (t + 1) / threads;
                            parts.add(pool.submit(() -> {
                                for (int i = from; i < to; i++) {
                                    try {
                                        heights[i] = (int) search.invoke(null, params, wrapper, xs[i], zs[i]);
                                    } catch (ReflectiveOperationException e) {
                                        throw new IllegalStateException(e);
                                    }
                                }
                            }));
                        }
                        for (Future<?> part : parts) {
                            part.get();
                        }
                        nanos[mode][round] = System.nanoTime() - start;
                        if (reference == null) {
                            reference = heights;
                        } else if (!java.util.Arrays.equals(reference, heights)) {
                            allSame = false;
                            int differ = 0;
                            for (int i = 0; i < columns; i++) {
                                if (reference[i] != heights[i]) {
                                    differ++;
                                }
                            }
                            LOGGER.warn("[dh-rough-bench] round {} memo={}: {} of {} surface heights differ", round, mode == 1, differ, columns);
                        }
                    }
                }
                for (int round = 1; round <= rounds; round++) {
                    LOGGER.info("[dh-rough-bench] round {}: DH {} us/column, memo {} us/column ({} columns, {} threads)",
                            round, perColumn(nanos[0][round], columns, threads), perColumn(nanos[1][round], columns, threads),
                            columns, threads);
                }
                long own = 0;
                long memoed = 0;
                for (int round = 1; round <= rounds; round++) {
                    own += nanos[0][round];
                    memoed += nanos[1][round];
                }
                LOGGER.info("[dh-rough-bench] total: DH {} ms, memo {} ms ({}% less); surface heights identical: {}",
                        own / 1_000_000, memoed / 1_000_000, String.format("%.1f", 100.0 * (own - memoed) / own), allSame);
                // The other per-column step: DH reads the biome at every data column's surface, four
                // per searched column (the search samples every other column in x and z).
                Method biomeAt = null;
                for (Method method : generator.getDeclaredMethods()) {
                    if (method.getName().equals("getBiomeAtBlockPos")) {
                        biomeAt = method;
                    }
                }
                if (biomeAt != null) {
                    biomeAt.setAccessible(true);
                    Method biome = biomeAt;
                    int[] surface = reference;
                    long start = System.nanoTime();
                    List<Future<?>> parts = new ArrayList<>();
                    for (int t = 0; t < threads; t++) {
                        int from = columns * t / threads;
                        int to = columns * (t + 1) / threads;
                        parts.add(pool.submit(() -> {
                            for (int i = from; i < to; i++) {
                                for (int d = 0; d < 4; d++) {
                                    try {
                                        biome.invoke(null, params, wrapper, xs[i] + (d & 1) * 16, surface[i], zs[i] + (d >> 1) * 16);
                                    } catch (ReflectiveOperationException e) {
                                        throw new IllegalStateException(e);
                                    }
                                }
                            }
                        }));
                    }
                    for (Future<?> part : parts) {
                        part.get();
                    }
                    LOGGER.info("[dh-rough-bench] biome lookups: {} us per searched column (4 lookups)",
                            perColumn(System.nanoTime() - start, columns, threads));
                }
            } finally {
                memo = true;
                pool.shutdown();
            }
        } catch (Throwable t) {
            LOGGER.warn("[dh-rough-bench] failed", t);
        }
    }

    /** CPU time per column: wall time times threads over columns, in microseconds. */
    private static String perColumn(long nanos, int columns, int threads) {
        return String.format("%.1f", nanos * (double) threads / columns / 1000.0);
    }
}
