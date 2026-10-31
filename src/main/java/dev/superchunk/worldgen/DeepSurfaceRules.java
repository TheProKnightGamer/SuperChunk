package dev.superchunk.worldgen;

import dev.superchunk.MixinTargetScan;
import dev.superchunk.mixin.ISurfaceGradientSourceAccess;
import dev.superchunk.mixin.ISurfaceNotSourceAccess;
import dev.superchunk.mixin.ISurfaceSequenceSourceAccess;
import dev.superchunk.mixin.ISurfaceTestRuleSourceAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The surface rules' answer below the preliminary surface, worked out once per rule tree. {@code buildSurface} runs the
 * rules for every stone block of a column, down to the bottom of the world, and below the preliminary surface most
 * trees decide by height alone: vanilla's overworld rules are a bedrock gradient at the floor, the surface rules behind
 * {@code abovePreliminarySurface()}, and a deepslate gradient between y 0 and 8. So for each height this works out
 * what the tree returns there when {@code abovePreliminarySurface()} is false: nothing ({@link #NONE}), one block state,
 * a {@link Gradient} inside a vertical gradient's random band, or {@link #UNKNOWN} when it depends on anything else. The
 * conditions read are only that one and vertical gradients (and {@code not} of those); anything else is unknown. A
 * gradient's draw depends only on the block's position ({@link #resolve}). Where the answer is known,
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

    private static final Object FALSE = marker("FALSE");
    private static final Object TRUE = marker("TRUE");
    private static final Object MAYBE = marker("MAYBE");
    // nested gradients one answer may hold
    private static final int MAX_GRADIENTS = 8;
    private static final boolean FIRST_FLOAT = Boolean.parseBoolean(
            System.getProperty("superchunk.worldgen.surfaceGradientRandom", "true"));

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

        /** This chunk's gradient random factories, set with the table. */
        Randoms superchunk$deepRandoms();

        void superchunk$setDeepRandoms(Randoms randoms);

        /** The height whose {@code updateY} was skipped and whose {@code tryApply} returns the table's answer. */
        int superchunk$skippedY();

        void superchunk$setSkippedY(int y);
    }

    /**
     * The answers per height from {@code minY} ({@code null} answers: the shortcut is off for this tree).
     * {@code minUnknown} is the lowest height whose answer is unknown ({@code Integer.MAX_VALUE} if none), and
     * {@code states} every block state an answer can give.
     */
    public record Table(SurfaceRules.RuleSource rules, int minY, int depth, Object[] answers, int minUnknown, BlockState[] states) {
        /** The known answer at {@code y} ({@link #NONE}, a block state or a {@link Gradient}), or {@link #UNKNOWN}. */
        public Object at(int y) {
            int i = y - this.minY;
            return this.answers != null && i >= 0 && i < this.answers.length ? this.answers[i] : UNKNOWN;
        }
    }

    /**
     * Inside a vertical gradient's random band: {@code ifTrue} where {@code VerticalGradientCondition} holds for the block,
     * else {@code ifFalse} (each {@link #NONE}, a block state or another gradient).
     */
    public record Gradient(ResourceLocation randomName, int trueAtAndBelow, int falseAtAndAbove, Object ifTrue, Object ifFalse) {
    }

    // a gradient condition inside its band, or not of one
    private record GradientTest(ResourceLocation randomName, int trueAtAndBelow, int falseAtAndAbove, boolean negated) {
    }

    /** One chunk's gradient random factories ({@code RandomState.getOrCreateRandomFactory}, looked up once per name). */
    public static final class Randoms {
        private final RandomState randomState;
        private ResourceLocation name;
        private PositionalRandomFactory factory;

        public Randoms(RandomState randomState) {
            this.randomState = randomState;
        }

        PositionalRandomFactory factory(ResourceLocation name) {
            if (!name.equals(this.name)) {
                this.factory = this.randomState.getOrCreateRandomFactory(name);
                this.name = name;
            }
            return this.factory;
        }
    }

    /**
     * A known answer for the block at x, y, z: {@link #NONE} or a block state. A gradient draws what
     * {@code VerticalGradientCondition.compute} draws there.
     */
    public static Object resolve(Object answer, int x, int y, int z, Randoms randoms) {
        while (answer instanceof Gradient g) {
            // MixinSurfaceGradientRandom's test: vanilla's, without allocating the random source it draws one float from
            boolean holds = FirstPositionalFloat.testGradient(randoms.factory(g.randomName), x, y, z, g.trueAtAndBelow,
                    g.falseAtAndAbove, FIRST_FLOAT);
            answer = holds ? g.ifTrue : g.ifFalse;
        }
        return answer;
    }

    /** True if {@code answer} gives a block state wherever it is resolved (no {@link #NONE} in any branch). */
    public static boolean alwaysState(Object answer) {
        return answer instanceof Gradient g ? alwaysState(g.ifTrue) && alwaysState(g.ifFalse) : answer instanceof BlockState;
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
        Object[] answers = analyze(rules, generation, context, minY, depth);
        int minUnknown = Integer.MAX_VALUE;
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; answers != null && i < depth; i++) {
            if (answers[i] == UNKNOWN) {
                minUnknown = Math.min(minUnknown, minY + i);
            }
            collectStates(answers[i], states);
        }
        Table table = new Table(rules, minY, depth, answers, minUnknown, states.toArray(new BlockState[0]));
        System.arraycopy(recent, 0, recent, 1, recent.length - 1);
        recent[0] = table;
        return table;
    }

    private static Object[] analyze(SurfaceRules.RuleSource rules, WorldGenerationContext generation, SurfaceRules.Context context,
                                    int minY, int depth) {
        // a class not scanned was never loaded, so it is not in the tree (the bound rules exist before the first block)
        String foreign = MixinTargetScan.foreignMixinIfLoaded(CLASSES);
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

    private static void collectStates(Object answer, List<BlockState> states) {
        if (answer instanceof Gradient g) {
            collectStates(g.ifTrue, states);
            collectStates(g.ifFalse, states);
        } else if (answer instanceof BlockState state && !states.contains(state)) {
            states.add(state);
        }
    }

    /** {@code "-64..-60 unknown, -59..0 minecraft:deepslate, ..."}. */
    private static String describe(Object[] answers, int minY) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < answers.length; ) {
            int j = i;
            while (j + 1 < answers.length && java.util.Objects.equals(answers[j + 1], answers[i])) {
                j++;
            }
            String name = name(answers[i]);
            out.append(out.isEmpty() ? "" : ", ").append(minY + i).append("..").append(minY + j).append(' ').append(name);
            i = j + 1;
        }
        return out.toString();
    }

    private static String name(Object answer) {
        if (answer instanceof Gradient g) {
            return g.randomName + " ? " + name(g.ifTrue) + " : " + name(g.ifFalse);
        }
        return answer instanceof BlockState state
                ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()
                : answer == NONE ? "nothing" : "unknown";
    }

    private static Object rule(SurfaceRules.RuleSource source, int y, WorldGenerationContext generation, SurfaceRules.Context context,
                               int depth) {
        if (depth > 64) {
            return UNKNOWN;
        }
        if (source instanceof ISurfaceSequenceSourceAccess sequence) {
            // the first child that does not return null: children up to the first that always answers, folded from the end
            List<Object> answers = new ArrayList<>();
            for (SurfaceRules.RuleSource child : sequence.superchunk$sequence()) {
                Object answer = rule(child, y, generation, context, depth + 1);
                answers.add(answer);
                if (answer == UNKNOWN || answer instanceof BlockState) {
                    break;
                }
            }
            Object rest = NONE;
            for (int i = answers.size() - 1; i >= 0 && rest != UNKNOWN; i--) {
                rest = then(answers.get(i), rest, 0);
            }
            return rest;
        }
        if (source instanceof ISurfaceTestRuleSourceAccess test) {
            Object condition = condition(test.superchunk$ifTrue(), y, generation, depth + 1);
            if (condition == FALSE) {
                return NONE;
            }
            Object then = rule(test.superchunk$thenRun(), y, generation, context, depth + 1);
            if (condition == TRUE || then == NONE || then == UNKNOWN) {
                return then;
            }
            if (condition instanceof GradientTest g) {
                return gradient(g, g.negated ? NONE : then, g.negated ? then : NONE);
            }
            return UNKNOWN;
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

    /** {@code answer}, and where it gives nothing, {@code rest}: a sequence's child followed by the children after it. */
    private static Object then(Object answer, Object rest, int nested) {
        if (answer == NONE) {
            return rest;
        }
        if (answer instanceof Gradient g) {
            if (nested > MAX_GRADIENTS) {
                return UNKNOWN;
            }
            return gradient(new GradientTest(g.randomName, g.trueAtAndBelow, g.falseAtAndAbove, false),
                    then(g.ifTrue, rest, nested + 1), then(g.ifFalse, rest, nested + 1));
        }
        return answer; // a block state, or unknown
    }

    private static Object gradient(GradientTest test, Object ifTrue, Object ifFalse) {
        if (ifTrue == UNKNOWN || ifFalse == UNKNOWN) {
            return UNKNOWN;
        }
        if (ifTrue.equals(ifFalse)) {
            return ifTrue;
        }
        return new Gradient(test.randomName, test.trueAtAndBelow, test.falseAtAndAbove, ifTrue, ifFalse);
    }

    /** {@link #TRUE}, {@link #FALSE}, {@link #MAYBE} or a {@link GradientTest}. */
    private static Object condition(SurfaceRules.ConditionSource source, int y, WorldGenerationContext generation, int depth) {
        if (depth > 64) {
            return MAYBE;
        }
        if (source == SurfaceRules.abovePreliminarySurface()) {
            return FALSE; // the table is only used below the preliminary surface
        }
        if (source instanceof ISurfaceNotSourceAccess not) {
            Object target = condition(not.superchunk$target(), y, generation, depth + 1);
            if (target instanceof GradientTest g) {
                return new GradientTest(g.randomName, g.trueAtAndBelow, g.falseAtAndAbove, !g.negated);
            }
            return target == TRUE ? FALSE : target == FALSE ? TRUE : MAYBE;
        }
        if (source instanceof ISurfaceGradientSourceAccess gradient) {
            // VerticalGradientCondition.compute: true at and below, false at and above, a random draw between
            int trueAtAndBelow = gradient.superchunk$trueAtAndBelow().resolveY(generation);
            int falseAtAndAbove = gradient.superchunk$falseAtAndAbove().resolveY(generation);
            return y <= trueAtAndBelow ? TRUE : y >= falseAtAndAbove ? FALSE
                    : new GradientTest(gradient.superchunk$randomName(), trueAtAndBelow, falseAtAndAbove, false);
        }
        return MAYBE;
    }

    /** {@code answer} is the resolved one ({@link #NONE} or a block state). */
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
