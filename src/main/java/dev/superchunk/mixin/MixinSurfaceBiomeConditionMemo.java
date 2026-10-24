package dev.superchunk.mixin;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

/**
 * A surface rule's biome test remembers its answer for the last biome it saw. The rules ask it again for every block
 * of a column near the surface, mostly about the same biome, and each answer is a set lookup of the biome's key:
 * {@code Holder.is(Predicate)} tests the holder's fixed key against the condition's fixed biome set, so the same holder
 * gets the same answer. A bound condition belongs to one chunk's rule context on one thread. Stands down when another
 * mod hooks {@code Holder.Reference.is} or this condition; kill switch {@code -Dsuperchunk.worldgen.surfaceBiomeTestMemo=false}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$1BiomeCondition")
public abstract class MixinSurfaceBiomeConditionMemo {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.surfaceBiomeTestMemo", "true"));

    @Unique
    private static byte superchunk$hooks;

    @Unique
    private Holder<Biome> superchunk$lastBiome;
    @Unique
    private boolean superchunk$lastAnswer;

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            String foreign = dev.superchunk.MixinTargetScan.foreignMixin(
                    "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$1BiomeCondition");
            state = foreign != null ? (foreign.startsWith("<") ? dev.superchunk.worldgen.ForeignHooks.UNKNOWN : dev.superchunk.worldgen.ForeignHooks.HOOKED)
                    : dev.superchunk.worldgen.ForeignHooks.state("The surface biome test memo (Holder.is)",
                            "net.minecraft.core.Holder$Reference#is");
            superchunk$hooks = state;
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    @Redirect(method = "compute", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/Holder;is(Ljava/util/function/Predicate;)Z"))
    private boolean superchunk$memoIs(Holder<Biome> biome, Predicate<ResourceKey<Biome>> test) {
        if (biome == this.superchunk$lastBiome && biome != null) {
            return this.superchunk$lastAnswer;
        }
        boolean answer = biome.is(test);
        if (SUPERCHUNK$ENABLED && superchunk$unhooked()) {
            this.superchunk$lastBiome = biome;
            this.superchunk$lastAnswer = answer;
        }
        return answer;
    }
}
