package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.com.ishland.c2me.opts.allocs.common.ObjectCachingUtils;
import dev.superchunk.worldgen.OreRuns;
import dev.superchunk.worldgen.OreTiming;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.BitSet;
import java.util.List;
import java.util.function.Function;

/**
 * SuperChunk: overhead-free {@code OreFeature.doPlace} — the single hottest feature
 * method (32.9% of the FEATURES subtree self-time; runs for every ore/blob attempt).
 *
 * <p>A statement-exact replica of vanilla with ONLY provably-pure overhead removed:
 * <ol>
 * <li><b>Method-ref hoist:</b> vanilla allocates {@code bulkAccess::getBlockState} per
 *     target-state test per candidate block (2.6% of ALL allocation pressure); one
 *     instance is hoisted — a stateless lambda over the same receiver.</li>
 * <li><b>Y-range clamp:</b> vanilla evaluates {@code !level.isOutsideBuildHeight(j2)}
 *     per candidate block AFTER the sphere test; all iterations it rejects are
 *     side-effect-free (pure FP + the failed test), so pre-clamping the y loop to
 *     {@code [minBuild, maxBuild)} skips exactly those iterations. Applied only when
 *     {@code level instanceof WorldGenRegion} (whose bounds getters are coupled to
 *     {@code isOutsideBuildHeight} by the vanilla interface defaults); other level
 *     types keep the verbatim per-block check.</li>
 * <li><b>Section memo:</b> {@code BulkSectionAccess.getSection} re-derives
 *     {@code getSectionIndex} + {@code SectionPos.asLong} per call; a 3-int
 *     (secX,secY,secZ) last-section memo returns the identical section object
 *     (sections are never swapped during placement — vanilla's own long-keyed
 *     last-section cache relies on the same invariant).</li>
 * <li><b>ensureCanWrite memo:</b> for a non-upgrading {@code WorldGenRegion} the
 *     result depends only on the (secX,secZ) column (verified against
 *     {@code WorldGenRegion.ensureCanWrite}); memoised per column. Upgrading regions
 *     (y-dependent) and non-region levels call through per block.</li>
 * <li><b>BitSet pooling:</b> C2ME's {@code MixinOreFeature} redirect can't fire inside
 *     a cancelled body, so its pooled-BitSet call is made directly (pool clears on
 *     acquire).</li>
 * </ol>
 *
 * <p><b>Bit-parity:</b> every statement, FP expression (order untouched), random draw
 * ({@code nextDouble} per distribution point; {@code RuleTest.test}/air-check draws
 * inside {@code canPlaceOre}) and iteration order is vanilla's, verbatim — including
 * the target-state loop (indexed over the same list, same order, same break).
 * Subclassed features that override {@code doPlace} never reach this code; mods
 * super-calling it get bit-identical behavior. Kill-switch:
 * {@code -Dsuperchunk.worldgen.oreFast=false}.
 *
 * <p>On top of that, three exact shortcuts ({@link OreRuns}, each with its own kill switch): {@code place}'s
 * column scan reads each chunk's heightmap directly; inside a {@link WorldGenRegion}, when every sphere
 * provably stays in its box, {@code doPlace} finds each row's ends instead of testing every block of the
 * bounding box and skips blocks an earlier sphere took a word at a time; and plain block or tag targets
 * remember their answer per block state.
 */
@Mixin(OreFeature.class)
public abstract class MixinOreFeatureFast {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.oreFast", "true"));

    /** Stand down when another mod hooks the replaced method ({@link dev.superchunk.worldgen.ForeignHooks}). */
    @Unique
    private static byte superchunk$hooks;

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("The ore placement replica (OreFeature.doPlace)",
                    "net.minecraft.world.level.levelgen.feature.OreFeature#doPlace");
            superchunk$hooks = state;
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    @Shadow
    protected abstract boolean doPlace(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                       double minX, double maxX, double minZ, double maxZ, double minY, double maxY,
                                       int x, int y, int z, int width, int height);

    @Shadow
    protected static boolean shouldSkipAirCheck(RandomSource random, float chance) {
        throw new AssertionError();
    }

    @Unique
    private static byte superchunk$placeHooks;

    @Unique
    private static boolean superchunk$placeUnhooked() {
        byte state = superchunk$placeHooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("The ore height check replica (OreFeature.place)",
                    "net.minecraft.world.level.levelgen.feature.OreFeature#place",
                    "net.minecraft.server.level.WorldGenRegion#getHeight");
            superchunk$placeHooks = state;
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    @Unique
    private static byte superchunk$targetHooks;

    /** The target memo stands in for {@code canPlaceOre} and the rule tests it calls. */
    @Unique
    private static boolean superchunk$targetsUnhooked() {
        byte state = superchunk$targetHooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("The ore target memo (OreFeature.canPlaceOre)",
                    "net.minecraft.world.level.levelgen.feature.OreFeature#canPlaceOre",
                    "net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest#test",
                    "net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest#test",
                    "net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest#test",
                    "net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest#test");
            superchunk$targetHooks = state;
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    @WrapMethod(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", require = 0)
    private boolean superchunk$fastPlace(FeaturePlaceContext<OreConfiguration> context, Operation<Boolean> original) {
        if (!OreTiming.ENABLED) {
            return superchunk$place(context, original);
        }
        int half = OreTiming.half(context.level());
        long start = OreTiming.now();
        boolean placed = superchunk$place(context, original);
        OreTiming.add(half, OreTiming.now() - start);
        return placed;
    }

    /** Vanilla's {@code place}, statement for statement, but for the column scan ({@link OreRuns#reaches}). */
    @Unique
    private boolean superchunk$place(FeaturePlaceContext<OreConfiguration> context, Operation<Boolean> original) {
        if (!SUPERCHUNK$ENABLED || context.level().getClass() != WorldGenRegion.class
                || (OreRuns.fast(context.level()) & OreRuns.HEIGHT_SCAN) == 0 || !superchunk$placeUnhooked()) {
            return original.call(context);
        }
        RandomSource randomsource = context.random();
        BlockPos blockpos = context.origin();
        WorldGenLevel worldgenlevel = context.level();
        OreConfiguration oreconfiguration = context.config();
        float f = randomsource.nextFloat() * (float) Math.PI;
        float f1 = (float) oreconfiguration.size / 8.0F;
        int i = Mth.ceil(((float) oreconfiguration.size / 16.0F * 2.0F + 1.0F) / 2.0F);
        double d0 = (double) blockpos.getX() + Math.sin((double) f) * (double) f1;
        double d1 = (double) blockpos.getX() - Math.sin((double) f) * (double) f1;
        double d2 = (double) blockpos.getZ() + Math.cos((double) f) * (double) f1;
        double d3 = (double) blockpos.getZ() - Math.cos((double) f) * (double) f1;
        double d4 = (double) (blockpos.getY() + randomsource.nextInt(3) - 2);
        double d5 = (double) (blockpos.getY() + randomsource.nextInt(3) - 2);
        int k = blockpos.getX() - Mth.ceil(f1) - i;
        int l = blockpos.getY() - 2 - i;
        int i1 = blockpos.getZ() - Mth.ceil(f1) - i;
        int j1 = 2 * (Mth.ceil(f1) + i);
        int k1 = 2 * (2 + i);

        boolean reaches = OreRuns.reaches((WorldGenRegion) worldgenlevel, l, k, i1, j1);
        if (OreRuns.VERIFY) {
            OreRuns.verifyReaches(worldgenlevel, l, k, i1, j1, reaches);
        }
        return reaches && this.doPlace(worldgenlevel, randomsource, oreconfiguration, d0, d1, d2, d3, d4, d5, k, l, i1, j1, k1);
    }

    // @WrapMethod rather than a HEAD-cancel @Inject: when the fast path fires the effect
    // is the same (other mods' injects into doPlace are bypassed — the documented replica
    // tradeoff), but wraps never cancel-fight other cancelling injectors, and if another
    // mod OVERWRITES doPlace, our disabled/fallthrough path composes around their version
    // instead of silently pre-empting it at HEAD.
    @WrapMethod(method = "doPlace", require = 0)
    private boolean superchunk$fastDoPlace(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                           double minX, double maxX, double minZ, double maxZ,
                                           double minY, double maxY,
                                           int x, int y, int z, int width, int height,
                                           Operation<Boolean> original) {
        if (!SUPERCHUNK$ENABLED || !superchunk$unhooked()) {
            return original.call(level, random, config, minX, maxX, minZ, maxZ, minY, maxY, x, y, z, width, height);
        }
        return superchunk$doPlace(level, random, config,
                minX, maxX, minZ, maxZ, minY, maxY, x, y, z, width, height, OreRuns.fast(level));
    }

    /** Targets whose test reads only the block state (no random draw), at most 32 of them. */
    @Unique
    private static boolean superchunk$plainTargets(OreConfiguration config) {
        List<OreConfiguration.TargetBlockState> targets = config.targetStates;
        if (targets.size() > 32) {
            return false;
        }
        for (int t = 0; t < targets.size(); t++) {
            Class<? extends RuleTest> type = targets.get(t).target.getClass();
            if (type != TagMatchTest.class && type != BlockMatchTest.class && type != BlockStateMatchTest.class
                    && type != AlwaysTrueTest.class) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link #superchunk$doPlace}'s block loop inside a {@link WorldGenRegion} whose spheres stay in the box
     * ({@link OreRuns#spheresFit}):
     * the same blocks in the same order with the same reads, writes and draws. Each row's blocks inside the sphere are
     * found as one run ({@link OreRuns#run}), and the bits of blocks already taken ({@link OreRuns#taken}) are indexed z
     * first, so a run is a range of bits and its new blocks are the clear ones, a word at a time. Vanilla's index is
     * unique inside the box, so a different one-to-one index gives the same answers.
     */
    @Unique
    private boolean superchunk$placeRuns(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                         double[] adouble, BlockPos.MutableBlockPos blockpos$mutableblockpos,
                                         int x, int y, int z, int width, int height, boolean memo) {
        int i = 0;
        int j = config.size;
        long[] taken = OreRuns.taken(width * height * width);
        int minBuild = level.getMinBuildHeight();
        int maxBuildExcl = level.getMaxBuildHeight();
        IntArrayList handedOn = OreRuns.VERIFY ? new IntArrayList() : null;
        try (BulkSectionAccess bulksectionaccess = new BulkSectionAccess(level)) {
            Function<BlockPos, BlockState> adjacentAccessor = bulksectionaccess::getBlockState;
            List<OreConfiguration.TargetBlockState> targets = config.targetStates;
            int targetCount = targets.size();
            float discardChance = config.discardChanceOnAirExposure;
            boolean canWriteMemoUsable = !((IWorldGenRegionAccess) level).superchunk$center().isUpgrading();
            int cwSecX = Integer.MIN_VALUE;
            int cwSecZ = Integer.MIN_VALUE;
            int secX = Integer.MIN_VALUE;
            int secY = Integer.MIN_VALUE;
            int secZ = Integer.MIN_VALUE;
            boolean secValid = false;
            LevelChunkSection memoSection = null;
            // the last two block states the targets were tested on, and which targets matched (bit t = target t)
            BlockState memoState0 = null;
            BlockState memoState1 = null;
            int memoMatch0 = 0;
            int memoMatch1 = 0;

            for (int j4 = 0; j4 < j; j4++) {
                double d9 = adouble[j4 * 4 + 3];
                if (!(d9 < 0.0)) {
                    double d11 = adouble[j4 * 4 + 0];
                    double d13 = adouble[j4 * 4 + 1];
                    double d15 = adouble[j4 * 4 + 2];
                    int k4 = Math.max(Mth.floor(d11 - d9), x);
                    int l = Math.max(Mth.floor(d13 - d9), y);
                    int i1 = Math.max(Mth.floor(d15 - d9), z);
                    int j1 = Math.max(Mth.floor(d11 + d9), k4);
                    int k1 = Math.max(Mth.floor(d13 + d9), l);
                    int l1 = Math.max(Mth.floor(d15 + d9), i1);
                    // vanilla's per-block height check rejects exactly the rows outside these (see the class javadoc)
                    int yLo = Math.max(l, minBuild);
                    int yHi = Math.min(k1, maxBuildExcl - 1);
                    double inv9 = 1.0 / d9;

                    for (int i2 = k4; i2 <= j1; i2++) {
                        double d5 = ((double) i2 + 0.5 - d11) / d9;
                        if (d5 * d5 < 1.0) {
                            long ys = OreRuns.run(d5 * d5, d13, d9, inv9, yLo, yHi);
                            for (int j2 = OreRuns.lo(ys), yEnd = OreRuns.hi(ys); j2 <= yEnd; j2++) {
                                double d6 = ((double) j2 + 0.5 - d13) / d9;
                                long zs = OreRuns.run(d5 * d5 + d6 * d6, d15, d9, inv9, i1, l1);
                                int zLo = OreRuns.lo(zs);
                                int zHi = OreRuns.hi(zs);
                                if (zLo > zHi) {
                                    continue;
                                }
                                int base = width * (i2 - x + width * (j2 - y)) - z;
                                int from = base + zLo;
                                int last = base + zHi;
                                for (int w = from >>> 6; w <= last >>> 6; w++) {
                                    long run = -1L;
                                    if (w == from >>> 6) {
                                        run &= -1L << from;
                                    }
                                    if (w == last >>> 6) {
                                        run &= -1L >>> ~last;
                                    }
                                    long fresh = run & ~taken[w];
                                    taken[w] |= run;
                                    for (; fresh != 0L; fresh &= fresh - 1L) {
                                        int k2 = (w << 6) + Long.numberOfTrailingZeros(fresh) - base;
                                        if (handedOn != null) {
                                            handedOn.add(i2);
                                            handedOn.add(j2);
                                            handedOn.add(k2);
                                        }
                                        blockpos$mutableblockpos.set(i2, j2, k2);
                                        boolean canWrite;
                                        int sx = i2 >> 4;
                                        int sz = k2 >> 4;
                                        if (canWriteMemoUsable && sx == cwSecX && sz == cwSecZ) {
                                            canWrite = true; // only positive results are memoized
                                        } else {
                                            canWrite = level.ensureCanWrite(blockpos$mutableblockpos);
                                            if (canWriteMemoUsable && canWrite) {
                                                cwSecX = sx;
                                                cwSecZ = sz;
                                            }
                                        }
                                        if (canWrite) {
                                            int sy = j2 >> 4;
                                            if (!(secValid && sx == secX && sy == secY && sz == secZ)) {
                                                memoSection = bulksectionaccess.getSection(blockpos$mutableblockpos);
                                                secX = sx;
                                                secY = sy;
                                                secZ = sz;
                                                secValid = true;
                                            }
                                            LevelChunkSection levelchunksection = memoSection;
                                            if (levelchunksection != null) {
                                                int i3 = SectionPos.sectionRelative(i2);
                                                int j3 = SectionPos.sectionRelative(j2);
                                                int k3 = SectionPos.sectionRelative(k2);
                                                BlockState blockstate = levelchunksection.getBlockState(i3, j3, k3);

                                                if (memo) {
                                                    // canPlaceOre per target: the test (no draw for these), then the air check
                                                    int match;
                                                    if (blockstate == memoState0) {
                                                        match = memoMatch0;
                                                    } else if (blockstate == memoState1) {
                                                        match = memoMatch1;
                                                    } else {
                                                        match = superchunk$match(targets, blockstate, random);
                                                        memoState1 = memoState0;
                                                        memoMatch1 = memoMatch0;
                                                        memoState0 = blockstate;
                                                        memoMatch0 = match;
                                                    }
                                                    if (OreRuns.VERIFY) {
                                                        OreRuns.verifyMemo(match, superchunk$match(targets, blockstate, random));
                                                    }
                                                    for (int t = 0; match != 0; t++, match >>>= 1) {
                                                        if ((match & 1) != 0
                                                                && (shouldSkipAirCheck(random, discardChance)
                                                                    || !Feature.isAdjacentToAir(adjacentAccessor, blockpos$mutableblockpos))) {
                                                            levelchunksection.setBlockState(i3, j3, k3, targets.get(t).state, false);
                                                            i++;
                                                            break;
                                                        }
                                                    }
                                                } else {
                                                    for (int t = 0; t < targetCount; t++) {
                                                        OreConfiguration.TargetBlockState targetState = targets.get(t);
                                                        if (OreFeature.canPlaceOre(
                                                                blockstate,
                                                                adjacentAccessor,
                                                                random,
                                                                config,
                                                                targetState,
                                                                blockpos$mutableblockpos)) {
                                                            levelchunksection.setBlockState(i3, j3, k3, targetState.state, false);
                                                            i++;
                                                            break;
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (handedOn != null) {
            OreRuns.verifyPlace(adouble, j, x, y, z, width, height, minBuild, maxBuildExcl, handedOn);
        }

        return i > 0;
    }

    /** Bit t set when target t's test passes on {@code state} ({@link #superchunk$plainTargets} draw nothing). */
    @Unique
    private static int superchunk$match(List<OreConfiguration.TargetBlockState> targets, BlockState state, RandomSource random) {
        int match = 0;
        for (int t = 0; t < targets.size(); t++) {
            if (targets.get(t).target.test(state, random)) {
                match |= 1 << t;
            }
        }
        return match;
    }

    @Unique
    private boolean superchunk$doPlace(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                       double minX, double maxX, double minZ, double maxZ,
                                       double minY, double maxY,
                                       int x, int y, int z, int width, int height, int fast) {
        int i = 0;
        BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
        int j = config.size;
        double[] adouble = new double[j * 4];

        for (int k = 0; k < j; k++) {
            float f = (float) k / (float) j;
            double d0 = Mth.lerp((double) f, minX, maxX);
            double d1 = Mth.lerp((double) f, minY, maxY);
            double d2 = Mth.lerp((double) f, minZ, maxZ);
            double d3 = random.nextDouble() * (double) j / 16.0;
            double d4 = ((double) (Mth.sin((float) Math.PI * f) + 1.0F) * d3 + 1.0) / 2.0;
            adouble[k * 4 + 0] = d0;
            adouble[k * 4 + 1] = d1;
            adouble[k * 4 + 2] = d2;
            adouble[k * 4 + 3] = d4;
        }

        for (int l3 = 0; l3 < j - 1; l3++) {
            if (!(adouble[l3 * 4 + 3] <= 0.0)) {
                for (int i4 = l3 + 1; i4 < j; i4++) {
                    if (!(adouble[i4 * 4 + 3] <= 0.0)) {
                        double d8 = adouble[l3 * 4 + 0] - adouble[i4 * 4 + 0];
                        double d10 = adouble[l3 * 4 + 1] - adouble[i4 * 4 + 1];
                        double d12 = adouble[l3 * 4 + 2] - adouble[i4 * 4 + 2];
                        double d14 = adouble[l3 * 4 + 3] - adouble[i4 * 4 + 3];
                        if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12) {
                            if (d14 > 0.0) {
                                adouble[i4 * 4 + 3] = -1.0;
                            } else {
                                adouble[l3 * 4 + 3] = -1.0;
                            }
                        }
                    }
                }
            }
        }

        if ((fast & OreRuns.RUNS) != 0 && level.getClass() == WorldGenRegion.class
                && OreRuns.spheresFit(adouble, j, x, y, z, width, height)) {
            return superchunk$placeRuns(level, random, config, adouble, blockpos$mutableblockpos, x, y, z, width, height,
                    (fast & OreRuns.TARGET_MEMO) != 0 && superchunk$plainTargets(config) && superchunk$targetsUnhooked());
        }
        BitSet bitset = ObjectCachingUtils.getCachedOrNewBitSet(width * height * width);

        try (BulkSectionAccess bulksectionaccess = new BulkSectionAccess(level)) {
            // ---- pure hoists (see class javadoc) ----
            Function<BlockPos, BlockState> adjacentAccessor = bulksectionaccess::getBlockState;
            List<OreConfiguration.TargetBlockState> targets = config.targetStates;
            int targetCount = targets.size();
            // exact-class (not instanceof): a modded WorldGenRegion subclass with altered
            // height semantics falls back to the verbatim per-block check (review 1.2)
            boolean fastBounds = level.getClass() == WorldGenRegion.class;
            int minBuild = level.getMinBuildHeight();
            int maxBuildExcl = level.getMaxBuildHeight();
            boolean canWriteMemoUsable = level.getClass() == WorldGenRegion.class
                    && !((IWorldGenRegionAccess) level).superchunk$center().isUpgrading();
            int cwSecX = Integer.MIN_VALUE;
            int cwSecZ = Integer.MIN_VALUE;
            int secX = Integer.MIN_VALUE;
            int secY = Integer.MIN_VALUE;
            int secZ = Integer.MIN_VALUE;
            boolean secValid = false;
            LevelChunkSection memoSection = null;

            for (int j4 = 0; j4 < j; j4++) {
                double d9 = adouble[j4 * 4 + 3];
                if (!(d9 < 0.0)) {
                    double d11 = adouble[j4 * 4 + 0];
                    double d13 = adouble[j4 * 4 + 1];
                    double d15 = adouble[j4 * 4 + 2];
                    int k4 = Math.max(Mth.floor(d11 - d9), x);
                    int l = Math.max(Mth.floor(d13 - d9), y);
                    int i1 = Math.max(Mth.floor(d15 - d9), z);
                    int j1 = Math.max(Mth.floor(d11 + d9), k4);
                    int k1 = Math.max(Mth.floor(d13 + d9), l);
                    int l1 = Math.max(Mth.floor(d15 + d9), i1);

                    for (int i2 = k4; i2 <= j1; i2++) {
                        double d5 = ((double) i2 + 0.5 - d11) / d9;
                        if (d5 * d5 < 1.0) {
                            int yLo = l;
                            int yHi = k1;
                            if (fastBounds) {
                                // skips only iterations vanilla's per-block height check
                                // would reject after pure FP — bit-identical
                                if (yLo < minBuild) {
                                    yLo = minBuild;
                                }
                                if (yHi >= maxBuildExcl) {
                                    yHi = maxBuildExcl - 1;
                                }
                            }
                            for (int j2 = yLo; j2 <= yHi; j2++) {
                                double d6 = ((double) j2 + 0.5 - d13) / d9;
                                if (d5 * d5 + d6 * d6 < 1.0) {
                                    for (int k2 = i1; k2 <= l1; k2++) {
                                        double d7 = ((double) k2 + 0.5 - d15) / d9;
                                        if (d5 * d5 + d6 * d6 + d7 * d7 < 1.0
                                                && (fastBounds || !level.isOutsideBuildHeight(j2))) {
                                            int l2 = i2 - x + (j2 - y) * width + (k2 - z) * width * height;
                                            if (!bitset.get(l2)) {
                                                bitset.set(l2);
                                                blockpos$mutableblockpos.set(i2, j2, k2);
                                                boolean canWrite;
                                                int sx = i2 >> 4;
                                                int sz = k2 >> 4;
                                                if (canWriteMemoUsable && sx == cwSecX && sz == cwSecZ) {
                                                    canWrite = true; // only positive results are memoized
                                                } else {
                                                    canWrite = level.ensureCanWrite(blockpos$mutableblockpos);
                                                    // Memoize success only: a rejected write must keep going
                                                    // through vanilla ensureCanWrite so its per-block
                                                    // far-chunk diagnostic still fires like vanilla's.
                                                    if (canWriteMemoUsable && canWrite) {
                                                        cwSecX = sx;
                                                        cwSecZ = sz;
                                                    }
                                                }
                                                if (canWrite) {
                                                    int sy = j2 >> 4;
                                                    if (!(secValid && sx == secX && sy == secY && sz == secZ)) {
                                                        memoSection = bulksectionaccess.getSection(blockpos$mutableblockpos);
                                                        secX = sx;
                                                        secY = sy;
                                                        secZ = sz;
                                                        secValid = true;
                                                    }
                                                    LevelChunkSection levelchunksection = memoSection;
                                                    if (levelchunksection != null) {
                                                        int i3 = SectionPos.sectionRelative(i2);
                                                        int j3 = SectionPos.sectionRelative(j2);
                                                        int k3 = SectionPos.sectionRelative(k2);
                                                        BlockState blockstate = levelchunksection.getBlockState(i3, j3, k3);

                                                        for (int t = 0; t < targetCount; t++) {
                                                            OreConfiguration.TargetBlockState targetState = targets.get(t);
                                                            if (OreFeature.canPlaceOre(
                                                                    blockstate,
                                                                    adjacentAccessor,
                                                                    random,
                                                                    config,
                                                                    targetState,
                                                                    blockpos$mutableblockpos)) {
                                                                levelchunksection.setBlockState(i3, j3, k3, targetState.state, false);
                                                                i++;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return i > 0;
    }
}
