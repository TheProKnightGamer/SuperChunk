package dev.superchunk.com.ishland.c2me.opts.allocs.mixin.surfacebuilder;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(SurfaceRules.SequenceRule.class)
public class MixinMaterialRulesSequenceBlockStateRule {

    @Shadow @Final private List<SurfaceRules.SurfaceRule> rules;
    @Unique
    private SurfaceRules.SurfaceRule[] rulesArray;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        this.rulesArray = this.rules.toArray(SurfaceRules.SurfaceRule[]::new);
    }

    /**
     * @author ishland
     * @reason use array for iteration
     */
    @Overwrite
    public @Nullable BlockState tryApply(int i, int j, int k) {
        // TODO [VanillaCopy]
        for(SurfaceRules.SurfaceRule blockStateRule : this.rulesArray) {
            BlockState blockState = blockStateRule.tryApply(i, j, k);
            if (blockState != null) {
                return blockState;
            }
        }

        return null;
    }

}
