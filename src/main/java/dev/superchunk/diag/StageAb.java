package dev.superchunk.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Test tool: an A/B of worldgen switches inside one server.
 *
 * <p>Two servers at once, or one after the other, cannot resolve a few percent on a shared
 * machine: the load drifts by more than that between legs. This turns the named switches off for
 * every other chunk (a checkerboard, so both halves get the same terrain mix) and adds up the
 * thread CPU time of the stage for each half. Both halves run interleaved on the same threads
 * under the same load.
 *
 * <p>{@code -Dsuperchunk.debug.stageAb=<switch>[,<switch>...]} names the switches (and
 * {@code -Dsuperchunk.debug.stageAb.stage=fill|features} the stage that is timed); each is a
 * constant here, read where the switch is tested ({@code StageAb.X && StageAb.off()}), so with the
 * property unset the JIT folds every test away. A report line is logged every 16,384 timed chunks:
 * count and mean CPU per chunk for each half, split by the stage's tag (for the fill: chunks with
 * structure terrain adaptation or without).
 */
public final class StageAb {

    private static final String SPEC = System.getProperty("superchunk.debug.stageAb");
    public static final boolean ENABLED = SPEC != null && !SPEC.isBlank();
    private static final Set<String> OFF = ENABLED
            ? new HashSet<>(Arrays.asList(SPEC.trim().split("\\s*,\\s*"))) : Set.of();

    /** {@code -Dsuperchunk.debug.stageAb.stage=features} times chunk decoration instead of the noise fill. */
    public static final boolean FEATURES = ENABLED
            && "features".equalsIgnoreCase(System.getProperty("superchunk.debug.stageAb.stage", "fill"));
    public static final boolean FILL = ENABLED && !FEATURES;

    /** The whole-cell fill ({@code WholeCells}). */
    public static final boolean WHOLE_CELLS = OFF.contains("wholeCells");
    /** Per-term beardifier reach ({@code MixinStructureWeightSampler}). */
    public static final boolean BEARD_REACH = OFF.contains("beardReach");
    /** The ore vein toggle only where a vein can be ({@code MixinOreVeinifierRange}). */
    public static final boolean VEIN_RANGE = OFF.contains("veinRange");
    /** The biome filter's area shortcut and the feature-list memo ({@code DecorationBiomes}). */
    public static final boolean DECORATION_BIOMES = OFF.contains("decorationBiomes");
    /** Palette resizes by distinct id ({@code MixinPalettedContainerDataCopy}). */
    public static final boolean PALETTE_COPY = OFF.contains("paletteCopy");

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-StageAb");
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    // [half][tag]: half 0 = switches on, 1 = switches off
    private static final LongAdder[][] COUNT = adders();
    private static final LongAdder[][] CPU = adders();
    private static final AtomicLong TIMED = new AtomicLong();

    private StageAb() {
    }

    private static final class State {
        boolean off;
        boolean timing;
        long cpu0;
    }

    private static LongAdder[][] adders() {
        LongAdder[][] a = new LongAdder[2][2];
        for (LongAdder[] row : a) {
            row[0] = new LongAdder();
            row[1] = new LongAdder();
        }
        return a;
    }

    /** Whether the stage running on this thread is in the "switches off" half. */
    public static boolean off() {
        return STATE.get().off;
    }

    /** Start of the stage for chunk (x, z) on this thread. */
    public static void begin(int chunkX, int chunkZ) {
        State s = STATE.get();
        s.off = ((chunkX + chunkZ) & 1) != 0;
        s.timing = true;
        s.cpu0 = THREADS.getCurrentThreadCpuTime();
    }

    /** End of the stage; {@code tagged} picks the report column. */
    public static void end(String stage, boolean tagged) {
        State s = STATE.get();
        if (!s.timing) {
            return;
        }
        long cpu = THREADS.getCurrentThreadCpuTime() - s.cpu0;
        int half = s.off ? 1 : 0;
        int tag = tagged ? 1 : 0;
        s.timing = false;
        s.off = false;
        COUNT[half][tag].increment();
        CPU[half][tag].add(cpu);
        if ((TIMED.incrementAndGet() & 16383) == 0) {
            report(stage);
        }
    }

    private static void report(String stage) {
        StringBuilder sb = new StringBuilder("[stage-ab] ").append(stage).append(" off=").append(OFF);
        for (int tag = 0; tag < 2; tag++) {
            long nOn = COUNT[0][tag].sum();
            long nOff = COUNT[1][tag].sum();
            if (nOn == 0 || nOff == 0) {
                continue;
            }
            double on = CPU[0][tag].sum() / 1000.0 / nOn;
            double off = CPU[1][tag].sum() / 1000.0 / nOff;
            sb.append(String.format(" | %s: on n=%d %.1f us, off n=%d %.1f us, on/off %.4f",
                    tag == 0 ? "plain" : "tagged", nOn, on, nOff, off, on / off));
        }
        long nOn = COUNT[0][0].sum() + COUNT[0][1].sum();
        long nOff = COUNT[1][0].sum() + COUNT[1][1].sum();
        if (nOn > 0 && nOff > 0) {
            double on = (CPU[0][0].sum() + CPU[0][1].sum()) / 1000.0 / nOn;
            double off = (CPU[1][0].sum() + CPU[1][1].sum()) / 1000.0 / nOff;
            sb.append(String.format(" | all: on %.1f us, off %.1f us, on/off %.4f", on, off, on / off));
        }
        LOGGER.info(sb.toString());
    }
}
