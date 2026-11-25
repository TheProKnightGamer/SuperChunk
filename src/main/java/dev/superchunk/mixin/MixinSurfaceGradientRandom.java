package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.worldgen.FirstPositionalFloat;
import dev.superchunk.worldgen.ForeignHooks;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vertical gradients discard a positional RNG after exactly one nextFloat. Keep the
 * original bounds and probability calculation, then substitute that one float for
 * recognized vanilla factories. Custom factories/subclasses and unavailable seed
 * accessors retain the original call. Disable with surfaceGradientRandom=false.
 *
 * <p>A wrap, not an overwrite: an overwrite at our priority makes every other mod's INVOKE hook in
 * {@code compute} fail ("cannot inject into ... merged by"), which broke every chunk at the surface
 * stage next to Collections of Optimizations' {@code MixinVerticalGradientRandom}. When another mod
 * hooks {@code compute} the original method (with their hook) runs instead.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource$1VerticalGradientCondition")
public abstract class MixinSurfaceGradientRandom {
    @Unique
    private static final boolean SUPERCHUNK$ENABLED = Boolean.parseBoolean(
            System.getProperty("superchunk.worldgen.surfaceGradientRandom", "true"));

    // Capture by argument type: synthetic field names differ between NeoForge's
    // development recompile and the production Minecraft jar.
    @Unique private SurfaceRules.Context superchunk$context;
    @Unique private PositionalRandomFactory superchunk$randomFactory;
    @Unique private int superchunk$lower;
    @Unique private int superchunk$upper;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void superchunk$captureArguments(CallbackInfo ci,
                                             @Local(argsOnly = true) SurfaceRules.Context context,
                                             @Local(argsOnly = true) PositionalRandomFactory factory,
                                             @Local(argsOnly = true, ordinal = 0) int lower,
                                             @Local(argsOnly = true, ordinal = 1) int upper) {
        this.superchunk$context = context;
        this.superchunk$randomFactory = factory;
        this.superchunk$lower = lower;
        this.superchunk$upper = upper;
    }

    /** Stand down when another mod hooks compute ({@link ForeignHooks}). */
    @Unique
    private static byte superchunk$hooks;

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == ForeignHooks.UNKNOWN) {
            state = ForeignHooks.state("The surface-gradient shortcut (VerticalGradientCondition.compute)",
                    "net.minecraft.world.level.levelgen.SurfaceRules$VerticalGradientConditionSource$1VerticalGradientCondition#compute");
            superchunk$hooks = state;
        }
        return state == ForeignHooks.CLEAR;
    }

    @WrapMethod(method = "compute")
    private boolean superchunk$compute(Operation<Boolean> original) {
        if (!superchunk$unhooked()) {
            return original.call();
        }
        ISurfaceRulesContextAccess coordinates = (ISurfaceRulesContextAccess) (Object) this.superchunk$context;
        return FirstPositionalFloat.testGradient(this.superchunk$randomFactory,
                coordinates.superchunk$blockX(), coordinates.superchunk$blockY(), coordinates.superchunk$blockZ(),
                this.superchunk$lower, this.superchunk$upper, SUPERCHUNK$ENABLED);
    }
}
