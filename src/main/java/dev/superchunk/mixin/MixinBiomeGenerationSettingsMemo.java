package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.DecorationBiomes;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/**
 * Remembers {@code hasFeature}'s answers per feature object.
 *
 * <p>{@code hasFeature} is a {@code HashSet.contains} of a {@code PlacedFeature}, a record whose
 * hash walks its whole list of placement modifiers on every call. The biome filter asks it for
 * every position a feature is tried at, always with the same few hundred feature objects. The set
 * is built once and a {@code PlacedFeature} is immutable, so the answer for one object never
 * changes; it is kept in a small table indexed by the object's identity hash. An entry is one
 * immutable pair published in a single store, so a race between threads only costs a recomputation.
 * A fresh table that another thread replaces is just an empty table.
 *
 * <p>Kill switch {@code -Dsuperchunk.worldgen.hasFeatureMemo=false}.
 */
@Mixin(BiomeGenerationSettings.class)
public abstract class MixinBiomeGenerationSettingsMemo {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.hasFeatureMemo", "true"));
    @Unique
    private static final int SUPERCHUNK$SLOTS = 256; // power of two

    /** Answers by identity-hash slot, or null until the first question. */
    @Unique
    private volatile DecorationBiomes.Answer[] superchunk$answers;

    @WrapOperation(method = "hasFeature", at = @At(value = "INVOKE",
            target = "Ljava/util/Set;contains(Ljava/lang/Object;)Z"), require = 0)
    private boolean superchunk$remembered(Set<?> features, Object feature, Operation<Boolean> original) {
        if (!SUPERCHUNK$ENABLED || feature == null
                || (dev.superchunk.diag.StageAb.DECORATION_BIOMES && dev.superchunk.diag.StageAb.off())) {
            return original.call(features, feature);
        }
        DecorationBiomes.Answer[] answers = this.superchunk$answers;
        if (answers == null) {
            answers = new DecorationBiomes.Answer[SUPERCHUNK$SLOTS];
            this.superchunk$answers = answers;
        }
        final int slot = System.identityHashCode(feature) & (SUPERCHUNK$SLOTS - 1);
        final DecorationBiomes.Answer entry = answers[slot];
        if (entry != null && entry.feature == feature) {
            return entry.has;
        }
        final boolean has = original.call(features, feature);
        answers[slot] = new DecorationBiomes.Answer(feature, has);
        return has;
    }
}
