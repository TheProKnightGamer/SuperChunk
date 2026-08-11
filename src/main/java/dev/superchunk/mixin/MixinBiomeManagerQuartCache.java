package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.BiomeQuartCache;
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
 */
@Mixin(BiomeManager.class)
public abstract class MixinBiomeManagerQuartCache {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.biomeQuartCache", "true"));

    @Unique
    private static final boolean SUPERCHUNK$VERIFY =
            Boolean.getBoolean("superchunk.worldgen.biomeQuartCache.verify");

    @Shadow
    @Final
    private BiomeManager.NoiseBiomeSource noiseBiomeSource;

    /** 0 = not yet decided, 1 = caching, -1 = pass-through (non-worldgen manager). */
    @Unique
    private int superchunk$mode;
    @Unique
    private BiomeQuartCache superchunk$cache;
    @Unique
    private int superchunk$generation;
    /**
     * The thread the table was bound on. A {@code WorldGenRegion} is driven by a single worker, so
     * binding once keeps the hot path free of ThreadLocal lookups — but the assumption is CHECKED
     * rather than assumed: another thread falls through to the original call instead of racing on
     * arrays it does not own, which could otherwise hand back a biome from a different position.
     */
    @Unique
    private Thread superchunk$owner;

    @SuppressWarnings("unchecked")
    @WrapOperation(
            method = "getBiome",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/BiomeManager$NoiseBiomeSource;"
                            + "getNoiseBiome(III)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> superchunk$cacheQuartBiome(BiomeManager.NoiseBiomeSource source,
                                                     int x, int y, int z,
                                                     Operation<Holder<Biome>> original) {
        if (superchunk$mode == 0) {
            superchunk$mode = (SUPERCHUNK$ENABLED && this.noiseBiomeSource instanceof WorldGenRegion) ? 1 : -1;
            if (superchunk$mode == 1) {
                // A WorldGenRegion is driven by one worker thread, so binding that thread's table
                // and a private generation once is safe and keeps the hot path free of ThreadLocal
                // lookups.
                superchunk$cache = BiomeQuartCache.local();
                superchunk$generation = superchunk$cache.claimGeneration();
                superchunk$owner = Thread.currentThread();
            }
        }
        if (superchunk$mode < 0 || superchunk$owner != Thread.currentThread()) {
            return original.call(source, x, y, z);
        }
        long encoded = BiomeQuartCache.encode(superchunk$generation, x, y, z);
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
        if (value != null) {
            superchunk$cache.put(encoded, value);
        }
        return value;
    }
}
