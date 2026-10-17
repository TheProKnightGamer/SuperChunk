package dev.superchunk.com.ishland.c2me.opts.allocs.mixin.surfacebuilder;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(SurfaceRules.SequenceRuleSource.class)
public class MixinMaterialRulesSequenceMaterialRule {

    private static final SurfaceRules.SurfaceRule EMPTY = new SurfaceRules.SequenceRule(List.of());

    @Shadow
    @Final
    private List<SurfaceRules.RuleSource> sequence;

    @Unique
    private SurfaceRules.RuleSource[] sequenceArray;

    @Unique
    private boolean isSingleOrNoElement;

    @Unique
    private SurfaceRules.RuleSource firstElement;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.sequenceArray = this.sequence.toArray(SurfaceRules.RuleSource[]::new);
        this.isSingleOrNoElement = this.sequenceArray.length <= 1;
        this.firstElement = this.sequenceArray.length == 0 ? null : this.sequenceArray[0];
    }

    /**
     * @author ishland
     * @reason optimize lookup
     */
    @Overwrite
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context materialRuleContext) {
        if (this.isSingleOrNoElement) {
            return this.firstElement != null ? this.firstElement.apply(materialRuleContext) : EMPTY;
        } else {
            @SuppressWarnings("UnstableApiUsage")
            ImmutableList.Builder<SurfaceRules.SurfaceRule> builder = ImmutableList.builderWithExpectedSize(this.sequenceArray.length);

            for (SurfaceRules.RuleSource materialRule : this.sequenceArray) {
                builder.add(materialRule.apply(materialRuleContext));
            }

            return new SurfaceRules.SequenceRule(builder.build());
        }
    }

}
