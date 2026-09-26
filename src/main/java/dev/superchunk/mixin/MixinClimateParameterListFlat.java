package dev.superchunk.mixin;

import dev.superchunk.worldgen.FlatClimateIndex;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers {@code ParameterList.findValueIndex(TargetPoint)} — the default-metric biome lookup behind
 * every {@code MultiNoiseBiomeSource} — from a {@link FlatClimateIndex} of the list's own R-tree.
 * The result, and the thread's warm-start leaf left behind, are those of vanilla's search.
 *
 * <p>Only exact {@code ParameterList} instances qualify, so a subclass overriding the protected
 * metric overload keeps its behavior, and only while no other mod's mixin is applied to the
 * list or R-tree classes ({@link dev.superchunk.MixinTargetScan}). Lists whose tree is not
 * vanilla-shaped, and targets outside the exact integer range, take the original method.
 *
 * <p>Kill switch: {@code -Dsuperchunk.worldgen.flatClimateSearch=false}. Verify mode
 * ({@code -Dsuperchunk.worldgen.flatClimateSearch.verify=true}) replays every lookup through vanilla
 * from the same warm start and compares both the value and the warm-start leaf.
 */
@Mixin(Climate.ParameterList.class)
public abstract class MixinClimateParameterListFlat<T> {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.flatClimateSearch", "true"));
    @Unique
    private static final boolean SUPERCHUNK$VERIFY =
            Boolean.getBoolean("superchunk.worldgen.flatClimateSearch.verify");
    @Unique
    private static final Object SUPERCHUNK$UNSUPPORTED = new Object();
    @Unique
    private static final ThreadLocal<Boolean> SUPERCHUNK$BYPASS = new ThreadLocal<>();
    @Unique
    private static final java.util.concurrent.atomic.AtomicBoolean SUPERCHUNK$REFUSAL_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** {@code null} until first use, then a {@link FlatClimateIndex} or {@link #SUPERCHUNK$UNSUPPORTED}. */
    @Unique
    private volatile Object superchunk$flat;

    @Unique
    private synchronized Object superchunk$buildFlat() {
        Object flat = this.superchunk$flat;
        if (flat == null) {
            // Another mod's mixin in the search classes would be bypassed by the replica.
            String foreign = dev.superchunk.MixinTargetScan.foreignMixin(
                    "net.minecraft.world.level.biome.Climate$ParameterList",
                    "net.minecraft.world.level.biome.Climate$RTree",
                    "net.minecraft.world.level.biome.Climate$RTree$Node",
                    "net.minecraft.world.level.biome.Climate$RTree$SubTree",
                    "net.minecraft.world.level.biome.Climate$RTree$Leaf",
                    // inlined by the flat search: toParameterArray and Parameter.distance
                    "net.minecraft.world.level.biome.Climate$TargetPoint",
                    "net.minecraft.world.level.biome.Climate$Parameter");
            FlatClimateIndex built = foreign == null ? FlatClimateIndex.build((Climate.ParameterList<?>) (Object) this) : null;
            flat = built == null ? SUPERCHUNK$UNSUPPORTED : built;
            this.superchunk$flat = flat;
            if (built == null && !SUPERCHUNK$REFUSAL_LOGGED.getAndSet(true)) {
                org.slf4j.LoggerFactory.getLogger("SuperChunk-ClimateSearch").info(
                        "Flat climate search not used for a biome parameter list: {}",
                        foreign != null ? "the climate search is also modified by " + foreign : FlatClimateIndex.lastRefusal());
            }
        }
        return flat;
    }

    @Inject(method = "findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;",
            at = @At("HEAD"), cancellable = true, require = 0)
    @SuppressWarnings("unchecked")
    private void superchunk$flatSearch(Climate.TargetPoint target, CallbackInfoReturnable<T> cir) {
        if (!SUPERCHUNK$ENABLED || (Object) this.getClass() != Climate.ParameterList.class
                || (SUPERCHUNK$VERIFY && SUPERCHUNK$BYPASS.get() != null)) {
            return;
        }
        Object flat = this.superchunk$flat;
        if (flat == null) {
            flat = this.superchunk$buildFlat();
        }
        if (flat == SUPERCHUNK$UNSUPPORTED) {
            return;
        }
        FlatClimateIndex index = (FlatClimateIndex) flat;
        if (SUPERCHUNK$VERIFY) {
            cir.setReturnValue((T) this.superchunk$verify(index, target));
            return;
        }
        Object value = index.search(target);
        if (value != null) {
            cir.setReturnValue((T) value);
        }
    }

    /** Runs both searches from the same warm start; any disagreement returns vanilla's answer. */
    @Unique
    private Object superchunk$verify(FlatClimateIndex index, Climate.TargetPoint target) {
        Object warm = index.warmStart();
        Object flatValue = index.search(target);
        Object flatLeaf = index.warmStart();
        index.setWarmStart(warm);
        Object vanillaValue;
        SUPERCHUNK$BYPASS.set(Boolean.TRUE);
        try {
            vanillaValue = ((Climate.ParameterList<?>) (Object) this).findValueIndex(target);
        } finally {
            SUPERCHUNK$BYPASS.remove();
        }
        if (flatValue == null) {
            return vanillaValue; // outside the exact range: vanilla ran, nothing to compare
        }
        FlatClimateIndex.recordVerify(target, flatValue, vanillaValue, flatLeaf == index.warmStart());
        return vanillaValue;
    }
}
