package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.BiomeFiddleCache;
import dev.superchunk.worldgen.BiomeQuartCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Memoizes {@code BiomeManager.getBiome}'s quart-resolution lookup, for <b>worldgen
 * BiomeManagers only</b>.
 *
 * <p>{@code getBiome} runs an 8-candidate fiddle to pick ONE quart cell and then calls
 * {@code noiseBiomeSource.getNoiseBiome(qx, qy, qz)}; that second half is what is cached. A JFR
 * profile of the current operating point attributes <b>10.5% of all worldgen CPU</b> to it, all of
 * it from this one call site — vanilla memoizes the biome per BLOCK, so surface rules and feature
 * biome-filters re-resolve a value that is constant across each 4×4×4 quart cell.
 *
 * <p>The table itself, its generation scheme and the correctness argument live in
 * {@link BiomeQuartCache}. The gate here is the safety-critical half: only a
 * {@link WorldGenRegion}'s manager qualifies, because only there is {@code getNoiseBiome} pure for
 * the manager's lifetime. {@code ServerLevel}'s manager is long-lived and {@code /fillbiome} can
 * rewrite biomes under it, so it always takes the original call.
 *
 * <p>Kill switch: {@code -Dsuperchunk.worldgen.biomeQuartCache=false}.
 * Hit-rate logging: {@code -Dsuperchunk.worldgen.biomeQuartCache.metrics=true}.
 * <b>Verify mode</b> ({@code -Dsuperchunk.worldgen.biomeQuartCache.verify=true}): every HIT also
 * runs the original lookup and compares identities, so a whole pregen becomes an end-to-end proof
 * that the memo never answers differently from the code it replaces. Slower than having no cache at
 * all — a gate, not a mode to run.
 *
 * <p>The same worldgen managers also memoize the first half of {@code getBiome}: the three jitter
 * offsets of each of the 8 candidate lattice points ({@link BiomeFiddleCache}). Those are pure
 * functions of the seed and lattice point, so every other manager uses an allocation-free copy of
 * {@code getFiddledDistance} instead. Before either is used, a one-time self-check compares the copy
 * with the actual target method on sampled inputs; if another mod changed it, both stand down and
 * the original is always called. Kill switch: {@code -Dsuperchunk.worldgen.biomeFiddleCache=false};
 * verify mode: {@code -Dsuperchunk.worldgen.biomeFiddleCache.verify=true}.
 *
 * <p>When SuperChunk's are the only mixins applied to {@code BiomeManager} (as recorded by
 * {@link dev.superchunk.MixinTargetScan} after all mixins are applied), a worldgen manager
 * answers {@code getBiome} from {@link BiomeFiddleCache#nearestCorner} and the quart memo directly:
 * one cell probe per block instead of eight lattice probes, and no boxed miss calls. The fast path
 * repeats vanilla's arithmetic and corner order exactly. If any other mod mixes into
 * {@code BiomeManager}, the vanilla method (with the per-corner hooks above) always runs.
 * Kill switch: {@code -Dsuperchunk.worldgen.biomeCellPath=false}.
 */
@Mixin(BiomeManager.class)
public abstract class MixinBiomeManagerQuartCache {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.biomeQuartCache", "true"));

    @Unique
    private static final boolean SUPERCHUNK$VERIFY =
            Boolean.getBoolean("superchunk.worldgen.biomeQuartCache.verify");

    @Unique
    private static final boolean SUPERCHUNK$FIDDLE_CACHE =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.biomeFiddleCache", "true"));

    @Unique
    private static final boolean SUPERCHUNK$FIDDLE_VERIFY =
            Boolean.getBoolean("superchunk.worldgen.biomeFiddleCache.verify");

    @Unique
    private static final boolean SUPERCHUNK$CELL_PATH = SUPERCHUNK$FIDDLE_CACHE
            && !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.biomeCellPath", "true"));

    /** 0 = not yet checked, 1 = only SuperChunk mixins target BiomeManager, -1 = another mod's do. */
    @Unique
    private static volatile int superchunk$soleMixins;

    /** 0 = not yet checked, 1 = the copy matches the target method, -1 = it does not. */
    @Unique
    private static volatile int superchunk$fiddleCopy;

    @Shadow
    @Final
    private BiomeManager.NoiseBiomeSource noiseBiomeSource;

    @Shadow
    @Final
    private long biomeZoomSeed;

    /** 0 = not yet decided, 1 = caching, -1 = pass-through (non-worldgen manager). */
    @Unique
    private volatile int superchunk$mode;
    @Unique
    private BiomeQuartCache superchunk$cache;
    @Unique
    private BiomeFiddleCache superchunk$fiddles;
    @Unique
    private long superchunk$generation;
    /**
     * The thread the table was bound on. A {@code WorldGenRegion} is driven by a single worker, so
     * binding once keeps the hot path free of ThreadLocal lookups — but the assumption is CHECKED
     * rather than assumed: another thread falls through to the original call instead of racing on
     * arrays it does not own, which could otherwise hand back a biome from a different position.
     */
    @Unique
    private Thread superchunk$owner;

    /** Publish the owner, table and ticket together before enabling the fast path. */
    @Unique
    private synchronized void superchunk$initializeCache() {
        if (superchunk$mode != 0) {
            return;
        }
        if ((SUPERCHUNK$ENABLED || SUPERCHUNK$FIDDLE_CACHE) && this.noiseBiomeSource instanceof WorldGenRegion) {
            if (SUPERCHUNK$ENABLED) {
                superchunk$cache = BiomeQuartCache.local();
                superchunk$generation = superchunk$cache.claimGeneration();
            }
            if (SUPERCHUNK$FIDDLE_CACHE) {
                superchunk$fiddles = BiomeFiddleCache.local();
            }
            superchunk$owner = Thread.currentThread();
            superchunk$mode = 1;
        } else {
            superchunk$mode = -1;
        }
    }

    @WrapMethod(method = "getBiome", require = 0)
    private Holder<Biome> superchunk$cellPathBiome(BlockPos pos, Operation<Holder<Biome>> original) {
        if (!SUPERCHUNK$CELL_PATH || superchunk$fiddleCopy <= 0) {
            return original.call(pos); // the per-corner hook runs the copy self-check first
        }
        int mode = superchunk$mode;
        if (mode == 0) {
            superchunk$initializeCache();
            mode = superchunk$mode;
        }
        BiomeFiddleCache fiddles = superchunk$fiddles;
        if (mode < 0 || fiddles == null || superchunk$owner != Thread.currentThread()
                || !superchunk$onlySuperChunkMixins()) {
            return original.call(pos);
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        int corner = fiddles.nearestCorner(this.biomeZoomSeed, x, y, z);
        if (corner < 0) {
            return original.call(pos);
        }
        Holder<Biome> biome = superchunk$quartDirect(
                ((x - 2) >> 2) + (corner >> 2), ((y - 2) >> 2) + ((corner >> 1) & 1), ((z - 2) >> 2) + (corner & 1));
        if (SUPERCHUNK$FIDDLE_VERIFY) {
            Holder<Biome> vanilla = original.call(pos);
            BiomeFiddleCache.recordCellVerify(x, y, z, biome, vanilla);
            return vanilla;
        }
        return biome;
    }

    /** The quart memo of {@link #superchunk$cacheQuartBiome}, resolving misses on the source itself. */
    @Unique
    @SuppressWarnings("unchecked")
    private Holder<Biome> superchunk$quartDirect(int x, int y, int z) {
        BiomeQuartCache cache = superchunk$cache;
        if (cache == null) {
            return this.noiseBiomeSource.getNoiseBiome(x, y, z);
        }
        if (!cache.isCurrentGeneration(superchunk$generation)) {
            superchunk$generation = cache.claimGeneration();
        }
        long generation = superchunk$generation;
        long encoded = BiomeQuartCache.encode(generation, x, y, z);
        if (encoded == BiomeQuartCache.UNCACHEABLE) {
            return this.noiseBiomeSource.getNoiseBiome(x, y, z);
        }
        Object cached = cache.get(encoded);
        if (cached != null) {
            if (SUPERCHUNK$VERIFY) {
                Holder<Biome> fresh = this.noiseBiomeSource.getNoiseBiome(x, y, z);
                if (fresh != cached) {
                    BiomeQuartCache.recordMismatch(x, y, z, cached, fresh);
                    return fresh;
                }
            }
            return (Holder<Biome>) cached;
        }
        Holder<Biome> value = this.noiseBiomeSource.getNoiseBiome(x, y, z);
        if (value != null && cache.isCurrentGeneration(generation)) {
            cache.put(encoded, value);
        }
        return value;
    }

    /**
     * Whether every mixin applied to {@code BiomeManager} is SuperChunk's own. Taking over the
     * whole method would skip another mod's injections, so any foreign mixin keeps vanilla's body.
     */
    @Unique
    private static boolean superchunk$onlySuperChunkMixins() {
        int sole = superchunk$soleMixins;
        return sole == 0 ? superchunk$checkSoleMixins() > 0 : sole > 0;
    }

    /** Decides (once, logging once) whether SuperChunk's are the only mixins on BiomeManager. */
    @Unique
    private static synchronized int superchunk$checkSoleMixins() {
        int sole = superchunk$soleMixins;
        if (sole == 0) {
            // Mixin's own applied-mixin sets are recorded on the mixin side, not the target, so the
            // finished class is scanned by a highest-priority probe instead (MixinProbeBiomeLookup).
            String foreign = dev.superchunk.MixinTargetScan.foreignMixin("net.minecraft.world.level.biome.BiomeManager");
            sole = foreign == null ? 1 : -1;
            superchunk$soleMixins = sole;
            org.slf4j.LoggerFactory.getLogger("SuperChunk-BiomeCache").info(foreign == null
                    ? "Biome cell path enabled for worldgen BiomeManagers."
                    : "Biome cell path disabled: BiomeManager is also modified by {}; using the per-corner cache.", foreign);
        }
        return sole;
    }

    @SuppressWarnings("unchecked")
    @WrapOperation(
            method = "getBiome",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/BiomeManager$NoiseBiomeSource;"
                            + "getNoiseBiome(III)Lnet/minecraft/core/Holder;"),
            require = 0) // another mod's getBiome overwrite may not make this call: stand down, don't crash
    private Holder<Biome> superchunk$cacheQuartBiome(BiomeManager.NoiseBiomeSource source,
                                                     int x, int y, int z,
                                                     Operation<Holder<Biome>> original) {
        int mode = superchunk$mode;
        if (mode == 0) {
            superchunk$initializeCache();
            mode = superchunk$mode;
        }
        if (mode < 0 || superchunk$cache == null || superchunk$owner != Thread.currentThread()) {
            return original.call(source, x, y, z);
        }
        if (!superchunk$cache.isCurrentGeneration(superchunk$generation)) {
            superchunk$generation = superchunk$cache.claimGeneration();
        }
        long generation = superchunk$generation;
        long encoded = BiomeQuartCache.encode(generation, x, y, z);
        if (encoded == BiomeQuartCache.UNCACHEABLE) {
            return original.call(source, x, y, z);
        }
        Object cached = superchunk$cache.get(encoded);
        if (cached != null) {
            if (SUPERCHUNK$VERIFY) {
                Holder<Biome> fresh = original.call(source, x, y, z);
                if (fresh != cached) {
                    dev.superchunk.worldgen.BiomeQuartCache.recordMismatch(x, y, z, cached, fresh);
                    return fresh;
                }
            }
            return (Holder<Biome>) cached;
        }
        Holder<Biome> value = original.call(source, x, y, z);
        // A null answer is not cached: it would be indistinguishable from a miss.
        // A modded source can nest other managers' lookups and wrap this table while
        // resolving a miss. Do not insert the outer result under a now-reused key.
        if (value != null && superchunk$cache.isCurrentGeneration(generation)) {
            superchunk$cache.put(encoded, value);
        }
        return value;
    }

    @WrapOperation(
            method = "getBiome",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/BiomeManager;getFiddledDistance(JIIIDDD)D"),
            require = 0)
    private double superchunk$cachedFiddle(long seed, int x, int y, int z, double xNoise, double yNoise,
                                           double zNoise, Operation<Double> original) {
        int copy = superchunk$fiddleCopy;
        if (!SUPERCHUNK$FIDDLE_CACHE || copy < 0 || (copy == 0 && !superchunk$checkFiddleCopy(original))) {
            return original.call(seed, x, y, z, xNoise, yNoise, zNoise);
        }
        int mode = superchunk$mode;
        if (mode == 0) {
            superchunk$initializeCache();
            mode = superchunk$mode;
        }
        BiomeFiddleCache fiddles = superchunk$fiddles;
        if (mode > 0 && fiddles != null && superchunk$owner == Thread.currentThread()) {
            double distance = fiddles.distance(seed, x, y, z, xNoise, yNoise, zNoise);
            if (SUPERCHUNK$FIDDLE_VERIFY) {
                double actual = original.call(seed, x, y, z, xNoise, yNoise, zNoise);
                BiomeFiddleCache.recordVerify(x, y, z, distance, actual);
                return actual;
            }
            return distance;
        }
        return BiomeFiddleCache.fiddledDistance(seed, x, y, z, xNoise, yNoise, zNoise);
    }

    /** Compares the copy with the actual (possibly modded) target on sampled inputs, once. */
    @Unique
    private static boolean superchunk$checkFiddleCopy(Operation<Double> original) {
        java.util.SplittableRandom random = new java.util.SplittableRandom(0xf1dd1eL);
        boolean same = true;
        for (int i = 0; i < 256 && same; i++) {
            long seed = i < 4 ? new long[] {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}[i] : random.nextLong();
            int x = random.nextInt(-8_000_000, 8_000_000);
            int y = random.nextInt(-600, 600);
            int z = random.nextInt(-8_000_000, 8_000_000);
            double xn = random.nextInt(4) / 4.0 - random.nextInt(2);
            double yn = random.nextInt(4) / 4.0 - random.nextInt(2);
            double zn = random.nextInt(4) / 4.0 - random.nextInt(2);
            double actual = original.call(seed, x, y, z, xn, yn, zn);
            double copy = BiomeFiddleCache.fiddledDistance(seed, x, y, z, xn, yn, zn);
            same = Double.doubleToRawLongBits(actual) == Double.doubleToRawLongBits(copy);
        }
        superchunk$fiddleCopy = same ? 1 : -1;
        if (!same) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-BiomeCache").warn(
                    "BiomeManager.getFiddledDistance differs from vanilla (changed by another mod?); "
                            + "SuperChunk's biome fiddle cache is disabled and the original method is used.");
        }
        return same;
    }
}
