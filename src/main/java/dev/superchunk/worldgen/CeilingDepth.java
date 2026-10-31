package dev.superchunk.worldgen;

import dev.superchunk.MixinTargetScan;
import dev.superchunk.mixin.ISurfaceNotSourceAccess;
import dev.superchunk.mixin.ISurfaceSequenceSourceAccess;
import dev.superchunk.mixin.ISurfaceStoneDepthAccess;
import dev.superchunk.mixin.ISurfaceTestRuleSourceAccess;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

import java.util.Set;

/**
 * How far below a block the surface rules can see. {@code buildSurface} passes each block's stone depth below
 * ({@code stoneDepthBelow}: the block and the solid blocks under it, down to the first air or fluid) to
 * {@code updateY}, and finds it by scanning to the bottom of the block's solid run, which for the run under the surface
 * is often tens of blocks. The only reader is a ceiling stone-depth check ({@code ON_CEILING}, {@code UNDER_CEILING}),
 * which asks whether that depth is at most {@code 1 + offset}, plus the column's surface depth if it adds it. So when
 * the depth is more than every ceiling check's limit, any value over the limits gives the same answers.
 * {@link #limit} is the largest limit for a column: the surface pass reads at most that many blocks under a block, and
 * passes the exact depth if the run ends within them, else {@code limit + 1}. The overworld's only ceiling check is
 * {@code ON_CEILING}, limit 1: one block under each.
 *
 * <p>Only for a tree made entirely of vanilla's rule and condition classes, none of them changed by another mod's
 * mixin, and no ceiling check with a secondary depth range (its limit depends on another noise). Kill switch
 * {@code -Dsuperchunk.worldgen.ceilingDepth=false}.
 */
public final class CeilingDepth {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.ceilingDepth", "true"));

    private static final String RULES = "net.minecraft.world.level.levelgen.SurfaceRules$";
    /** Rule and condition classes without children whose answers never read the stone depth below. */
    private static final Set<String> LEAVES = Set.of(
            RULES + "BlockRuleSource", RULES + "Bandlands", RULES + "BiomeConditionSource", RULES + "YConditionSource",
            RULES + "WaterConditionSource", RULES + "NoiseThresholdConditionSource", RULES + "VerticalGradientConditionSource",
            RULES + "Steep", RULES + "Hole", RULES + "AbovePreliminarySurface", RULES + "Temperature");
    /** Everything whose code the limits stand for: the classes above, their bound conditions and the context. */
    private static final String[] CLASSES = {
            RULES + "SequenceRuleSource", RULES + "SequenceRule", RULES + "TestRuleSource", RULES + "TestRule",
            RULES + "NotConditionSource", RULES + "NotCondition", RULES + "StoneDepthCheck",
            RULES + "StoneDepthCheck$1StoneDepthCondition", RULES + "BlockRuleSource", RULES + "StateRule", RULES + "Bandlands",
            RULES + "BiomeConditionSource", RULES + "BiomeConditionSource$1BiomeCondition", RULES + "YConditionSource",
            RULES + "YConditionSource$1YCondition", RULES + "WaterConditionSource", RULES + "WaterConditionSource$1WaterCondition",
            RULES + "NoiseThresholdConditionSource", RULES + "NoiseThresholdConditionSource$1NoiseThresholdCondition",
            RULES + "VerticalGradientConditionSource", RULES + "VerticalGradientConditionSource$1VerticalGradientCondition",
            RULES + "Steep", RULES + "Hole", RULES + "AbovePreliminarySurface", RULES + "Temperature", RULES + "Context",
            RULES + "Context$HoleCondition", RULES + "Context$SteepMaterialCondition", RULES + "Context$TemperatureHelperCondition",
            RULES + "Context$AbovePreliminarySurfaceCondition", RULES + "LazyCondition", RULES + "LazyXZCondition",
            RULES + "LazyYCondition"};

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SuperChunk-Surface");
    private static final Set<Integer> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * One tree's ceiling checks: {@code fixed} is the largest {@code 1 + offset} of those that don't add the surface
     * depth, {@code withSurface} of those that do ({@code Integer.MIN_VALUE} where there are none).
     */
    public record Limits(SurfaceRules.RuleSource rules, int fixed, int withSurface) {
        /** The largest limit in a column with this surface depth ({@code -1} when the tree has no ceiling check). */
        public int limit(int surfaceDepth) {
            int limit = Math.max(this.fixed, this.withSurface == Integer.MIN_VALUE ? Integer.MIN_VALUE : this.withSurface + surfaceDepth);
            return Math.max(limit, -1);
        }
    }

    // the last few trees this thread looked at; a tree the shortcut is off for has withSurface MAX_VALUE
    private static final ThreadLocal<Limits[]> RECENT = ThreadLocal.withInitial(() -> new Limits[4]);

    private CeilingDepth() {
    }

    /** The limits for {@code rules}, or {@code null} to scan for the exact depth. */
    public static Limits of(SurfaceRules.RuleSource rules) {
        if (!ENABLED) {
            return null;
        }
        Limits[] recent = RECENT.get();
        for (Limits limits : recent) {
            if (limits != null && limits.rules == rules) {
                return limits.withSurface == Integer.MAX_VALUE ? null : limits;
            }
        }
        Limits limits = analyze(rules);
        System.arraycopy(recent, 0, recent, 1, recent.length - 1);
        recent[0] = limits != null ? limits : new Limits(rules, 0, Integer.MAX_VALUE);
        return limits;
    }

    private static Limits analyze(SurfaceRules.RuleSource rules) {
        // a class never loaded is not in the tree (its bound conditions exist before the first block)
        String foreign = MixinTargetScan.foreignMixinIfLoaded(CLASSES);
        if (foreign != null) {
            log(rules, "stands down: " + foreign + " also changes the surface rules");
            return null;
        }
        int[] limits = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        if (!walk(rules, limits, 0)) {
            log(rules, "stands down: the surface rules use a rule or condition type it does not know");
            return null;
        }
        Limits result = new Limits(rules, limits[0], limits[1]);
        if (result.fixed == Integer.MIN_VALUE && result.withSurface == Integer.MIN_VALUE) {
            log(rules, "is not read: the surface rules have no ceiling check");
        } else {
            String surface = result.withSurface + " + the surface depth";
            String limit = result.withSurface == Integer.MIN_VALUE ? String.valueOf(result.fixed)
                    : result.fixed == Integer.MIN_VALUE ? surface : "the larger of " + result.fixed + " and " + surface;
            log(rules, "reads at most " + limit + " blocks under each block");
        }
        return result;
    }

    private static void log(SurfaceRules.RuleSource rules, String what) {
        if (LOGGED.add(System.identityHashCode(rules))) {
            LOGGER.info("[SuperChunk] The surface pass's stone depth below {}.", what);
        }
    }

    /** True if every node under {@code node} is a known vanilla class; collects the ceiling checks' limits. */
    private static boolean walk(Object node, int[] limits, int depth) {
        if (depth > 256 || node == null) {
            return false;
        }
        if (node instanceof ISurfaceSequenceSourceAccess sequence) {
            for (SurfaceRules.RuleSource child : sequence.superchunk$sequence()) {
                if (!walk(child, limits, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        if (node instanceof ISurfaceTestRuleSourceAccess test) {
            return walk(test.superchunk$ifTrue(), limits, depth + 1) && walk(test.superchunk$thenRun(), limits, depth + 1);
        }
        if (node instanceof ISurfaceNotSourceAccess not) {
            return walk(not.superchunk$target(), limits, depth + 1);
        }
        if (node instanceof ISurfaceStoneDepthAccess check) {
            if (check.superchunk$surfaceType() == CaveSurface.CEILING) {
                if (check.superchunk$secondaryDepthRange() != 0) {
                    return false;
                }
                int i = check.superchunk$addSurfaceDepth() ? 1 : 0;
                limits[i] = Math.max(limits[i], 1 + check.superchunk$offset());
            }
            return true;
        }
        return LEAVES.contains(node.getClass().getName());
    }
}
