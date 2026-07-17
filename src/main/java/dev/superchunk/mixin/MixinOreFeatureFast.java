package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.com.ishland.c2me.opts.allocs.common.ObjectCachingUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
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
 */
@Mixin(OreFeature.class)
public abstract class MixinOreFeatureFast {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.oreFast", "true"));

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
        if (!SUPERCHUNK$ENABLED) {
            return original.call(level, random, config, minX, maxX, minZ, maxZ, minY, maxY, x, y, z, width, height);
        }
        return superchunk$doPlace(level, random, config,
                minX, maxX, minZ, maxZ, minY, maxY, x, y, z, width, height);
    }

    @Unique
    private boolean superchunk$doPlace(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                       double minX, double maxX, double minZ, double maxZ,
                                       double minY, double maxY,
                                       int x, int y, int z, int width, int height) {
        int i = 0;
        BitSet bitset = ObjectCachingUtils.getCachedOrNewBitSet(width * height * width);
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
