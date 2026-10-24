package dev.superchunk.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.worldgen.DeepSurfaceRules;
import dev.superchunk.worldgen.SurfaceTiming;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@link DeepSurfaceRules}: below the preliminary surface, a block whose rule answer the table knows skips
 * {@code updateY}, and its {@code tryApply} returns the table's answer; vanilla then writes it as before.
 *
 * <p>{@code @Redirect} rather than {@code @WrapOperation}: these run for every block of every column, and a wrapped
 * call passes its arguments to the original as a boxed array, which cost more than the rules it skipped.
 */
@Mixin(SurfaceSystem.class)
public abstract class MixinSurfaceDeepRules {

    @Redirect(method = "buildSurface", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;updateY(IIIIII)V"))
    private void superchunk$deepUpdateY(SurfaceRules.Context context, int stoneDepthAbove, int stoneDepthBelow, int waterHeight,
                                        int blockX, int blockY, int blockZ,
                                        @Local(argsOnly = true) WorldGenerationContext generation,
                                        @Local(argsOnly = true) SurfaceRules.RuleSource rules) {
        DeepSurfaceRules.ContextState state = (DeepSurfaceRules.ContextState) (Object) context;
        ISurfaceRulesContextAccess access = (ISurfaceRulesContextAccess) (Object) context;
        if (DeepSurfaceRules.ENABLED && (SurfaceTiming.off() & SurfaceTiming.DEEP_RULES) == 0) {
            DeepSurfaceRules.Table table = state.superchunk$deepTable();
            if (table == null) {
                table = DeepSurfaceRules.table(rules, generation, context);
                state.superchunk$setDeepTable(table);
            }
            if (table.at(blockY) != DeepSurfaceRules.UNKNOWN && blockY < access.superchunk$getMinSurfaceLevel()) {
                state.superchunk$setSkippedY(blockY);
                if (DeepSurfaceRules.VERIFY) {
                    access.superchunk$updateY(stoneDepthAbove, stoneDepthBelow, waterHeight, blockX, blockY, blockZ);
                }
                return;
            }
        }
        state.superchunk$setSkippedY(Integer.MIN_VALUE);
        access.superchunk$updateY(stoneDepthAbove, stoneDepthBelow, waterHeight, blockX, blockY, blockZ);
    }

    @Redirect(method = "buildSurface", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;tryApply(III)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState superchunk$deepTryApply(SurfaceRules.SurfaceRule rule, int blockX, int blockY, int blockZ,
                                               @Local SurfaceRules.Context context) {
        DeepSurfaceRules.ContextState state = (DeepSurfaceRules.ContextState) (Object) context;
        if (state.superchunk$skippedY() != blockY) {
            return rule.tryApply(blockX, blockY, blockZ);
        }
        Object answer = state.superchunk$deepTable().at(blockY);
        if (DeepSurfaceRules.VERIFY) {
            BlockState vanilla = rule.tryApply(blockX, blockY, blockZ);
            DeepSurfaceRules.verify(blockX, blockY, blockZ, answer, vanilla);
            return vanilla;
        }
        return answer == DeepSurfaceRules.NONE ? null : (BlockState) answer;
    }
}
