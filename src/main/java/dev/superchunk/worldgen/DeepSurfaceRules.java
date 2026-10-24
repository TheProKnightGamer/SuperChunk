package dev.superchunk.worldgen;

import dev.superchunk.MixinTargetScan;
import dev.superchunk.mixin.ISurfaceGradientSourceAccess;
import dev.superchunk.mixin.ISurfaceNotSourceAccess;
import dev.superchunk.mixin.ISurfaceSequenceSourceAccess;
import dev.superchunk.mixin.ISurfaceTestRuleSourceAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The surface rules' answer below the preliminary surface, worked out once per rule tree. {@code buildSurface} runs the
 * rules for every stone block of a column, down to the bottom of the world, and below the preliminary surface most
 * trees decide by height alone: vanilla's overworld rules are a bedrock gradient at the floor, the surface rules behind
 * {@code abovePreliminarySurface()}, and a deepslate gradient between y 0 and 8. So for each height this works out
 * what the tree returns there when {@code abovePreliminarySurface()} is false: nothing ({@link #NONE}), one block state,
 * or {@link #UNKNOWN} when it depends on anything else. The conditions read are only that one and vertical gradients
 * outside their random band (and {@code not} of those); anything else is unknown. Where the answer is known,
 * {@code MixinSurfaceDeepRules} skips {@code updateY} and {@code tryApply} for the block and returns that answer.
 * Skipping {@code updateY} changes nothing the next block's rules can see: it sets every field they read, and the
 * conditions cached per height recompute because its counter moved on.
 *
 * <p>Off where another mod's mixin is on a rule class the answers stand for. Kill switch
 * {@code -Dsuperchunk.worldgen.deepSurfaceRules=false}; {@code .verify=true} runs the rules anyway and compares.
 */
public final class DeepSurfaceRules {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.deepSurfaceRules", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.deepSurfaceRules.verify");

    /** The rules return null at this height. */
    public static final Object NONE = marker("NONE");
    /** The answer at this height depends on more than the height. */
    public static final Object UNKNOWN = marker("UNKNOWN");

    private static final int FALSE = 0;
    private static final int TRUE = 1;
    private static final int MAYBE = 2;

    private static final String RULES = "net.minecraft.world.level.levelgen.SurfaceRules$";
    private static final String BLOCK_RULE_SOURCE = RULES + "BlockRuleSource";
    private static final String STATE_RULE = RULES + "StateRule";
    /** The rule and condition classes the answers stand for. */
    private static final String[] CLASSES = {
            RULES + "SequenceRuleSource", RULES + "SequenceRule", RULES + "TestRuleSource", RULES + "TestRule",
            BLOCK_RULE_SOURCE, STATE_RULE, RULES + "NotConditionSource", RULES + "NotCondition",
            RULES + "VerticalGradientConditionSource", RULES + "VerticalGradientConditionSource$1VerticalGradientCondition",
            RULES + "AbovePreliminarySurface", RULES + "Context$AbovePreliminarySurfaceCondition",
            RULES + "LazyYCondition", RULES + "LazyCondition"};

    private static final AtomicLong CHECKED = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SuperChunk-Surface");

    private DeepSurfaceRules() {
    }

    private static Object marker(String name) {
        return new Object() {
            @Override
            public String toString() {
                return name;
            }
        };
    }

    /** Implemented on {@code SurfaceRules.Context} by {@code MixinSurfaceContextDeep}: one chunk's table and state. */
    public interface ContextState {
        /** This chunk's {@link Table}, {@code null} until looked up. */
        Table superchunk$deepTable();

        void superchunk$setDeepTable(Table table);

        /** The height whose {@code updateY} was skipped and whose {@code tryApply} returns the table's answer. */
        int superchunk$skippedY();

        void superchunk$setSkippedY(int y);
    }

    /** The answers per height from {@code minY} ({@code null} answers: the shortcut is off for this tree). */
    public record Table(SurfaceRules.RuleSource rules, int minY, int depth, Object[] answers) {
        /** The known answer at {@code y} ({@link #NONE} or a block state), or {@link #UNKNOWN}. */
        public Object at(int y) {
            int i = y - this.minY;
            return this.answers != null && i >= 0 && i < this.answers.length ? this.answers[i] : UNKNOWN;
        }
    }

    // the last few trees this thread worked out
    private static final ThreadLocal<Table[]> RECENT = ThreadLocal.withInitial(() -> new Table[4]);

    /** The table for {@code rules} in this dimension; {@code context} is the chunk's rule context. */
    public static Table table(SurfaceRules.RuleSource rules, WorldGenerationContext generation, SurfaceRules.Context context) {
        int minY = generation.getMinGenY();
        int depth = generation.getGenDepth();
        Table[] recent = RECENT.get();
        for (Table table : recent) {
            if (table != null && table.rules == rules && table.minY == minY && table.depth == depth) {
                return table;
            }
        }
        Table table = new Table(rules, minY, depth, analyze(rules, generation, context, minY, depth));
        System.arraycopy(recent, 0, recent, 1, recent.length - 1);
        recent[0] = table;
        return table;
    }

    private static Object[] analyze(SurfaceRules.RuleSource rules, WorldGenerationContext generation, SurfaceRules.Context context,
                                    int minY, int depth) {
        // a class not scanned was never loaded, so it is not in the tree (the bound rules exist before the first block)
        String foreign = MixinTargetScan.foreignMixin(CLASSES);
        if (foreign != null && foreign.endsWith("was not scanned>")) {
            foreign = null;
        }
        if (foreign == null) {
            foreign = MixinTargetScan.foreignHook(RULES + "Context#updateY", RULES + "Context#getMinSurfaceLevel",
                    "net.minecraft.world.level.levelgen.SurfaceSystem#buildSurface");
        }
        if (foreign != null) {
            LOGGER.info("[SuperChunk] The deep surface-rule shortcut stands down: {} also changes the surface rules.", foreign);
            return null;
        }
        Object[] answers = new Object[depth];
        boolean any = false;
        for (int i = 0; i < depth; i++) {
            answers[i] = rule(rules, minY + i, generation, context, 0);
            any |= answers[i] != UNKNOWN;
        }
        if (any && LOGGED.add(System.identityHashCode(rules))) {
            LOGGER.info("[SuperChunk] Surface rules below the preliminary surface, by height: {}", describe(answers, minY));
        }
        return any ? answers : null;
    }

    private static final java.util.Set<Integer> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** {@code "-64..-60 unknown, -59..0 minecraft:deepslate, ..."}. */
    private static String describe(Object[] answers, int minY) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < answers.length; ) {
            int j = i;
            while (j + 1 < answers.length && answers[j + 1] == answers[i]) {
                j++;
            }
            Object answer = answers[i];
            String name = answer instanceof BlockState state
                    ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()
                    : answer == NONE ? "nothing" : "unknown";
            out.append(out.isEmpty() ? "" : ", ").append(minY + i).append("..").append(minY + j).append(' ').append(name);
            i = j + 1;
        }
        return out.toString();
    }

    private static Object rule(SurfaceRules.RuleSource source, int y, WorldGenerationContext generation, SurfaceRules.Context context,
                               int depth) {
        if (depth > 64) {
            return UNKNOWN;
        }
        if (source instanceof ISurfaceSequenceSourceAccess sequence) {
            // the first child that does not return null
            for (SurfaceRules.RuleSource child : sequence.superchunk$sequence()) {
                Object answer = rule(child, y, generation, context, depth + 1);
                if (answer != NONE) {
                    return answer;
                }
            }
            return NONE;
        }
        if (source instanceof ISurfaceTestRuleSourceAccess test) {
            int condition = condition(test.superchunk$ifTrue(), y, generation, depth + 1);
            if (condition == FALSE) {
                return NONE;
            }
            Object then = rule(test.superchunk$thenRun(), y, generation, context, depth + 1);
            return condition == TRUE || then == NONE ? then : UNKNOWN;
        }
        if (source.getClass().getName().equals(BLOCK_RULE_SOURCE)) {
            // apply returns the source's own StateRule, whose tryApply returns its state for any position
            SurfaceRules.SurfaceRule bound = source.apply(context);
            if (bound.getClass().getName().equals(STATE_RULE)) {
                BlockState state = bound.tryApply(0, y, 0);
                return state != null ? state : NONE;
            }
        }
        return UNKNOWN;
    }

    private static int condition(SurfaceRules.ConditionSource source, int y, WorldGenerationContext generation, int depth) {
        if (depth > 64) {
            return MAYBE;
        }
        if (source == SurfaceRules.abovePreliminarySurface()) {
            return FALSE; // the table is only used below the preliminary surface
        }
        if (source instanceof ISurfaceNotSourceAccess not) {
            int target = condition(not.superchunk$target(), y, generation, depth + 1);
            return target == MAYBE ? MAYBE : 1 - target;
        }
        if (source instanceof ISurfaceGradientSourceAccess gradient) {
            // VerticalGradientCondition.compute: true at and below, false at and above, a random draw between
            int trueAtAndBelow = gradient.superchunk$trueAtAndBelow().resolveY(generation);
            int falseAtAndAbove = gradient.superchunk$falseAtAndAbove().resolveY(generation);
            return y <= trueAtAndBelow ? TRUE : y >= falseAtAndAbove ? FALSE : MAYBE;
        }
        return MAYBE;
    }

    public static void verify(int x, int y, int z, Object answer, BlockState vanilla) {
        CHECKED.incrementAndGet();
        if ((answer == NONE ? null : answer) != vanilla && MISMATCHES.incrementAndGet() <= 20) {
            LOGGER.warn("[deep-surface-rules] VERIFY MISMATCH at {},{},{}: table {} vanilla {}", x, y, z, answer, vanilla);
        }
    }

    public static void report() {
        if (VERIFY) {
            long m = MISMATCHES.get();
            LOGGER.info("[deep-surface-rules] VERIFY: checked={} MISMATCHES={} -> {}", CHECKED.get(), m, m == 0 ? "PASS" : "FAIL");
        }
    }
}
