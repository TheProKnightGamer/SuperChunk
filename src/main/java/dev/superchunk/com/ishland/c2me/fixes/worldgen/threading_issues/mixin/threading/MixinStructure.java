package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;

@Mixin(StructureTemplate.class)
public class MixinStructure {

    @Mutable
    @Shadow
    @Final
    private List<StructureTemplate.Palette> palettes;

    @Mutable
    @Shadow
    @Final
    private List<StructureTemplate.StructureEntityInfo> entityInfoList;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        this.palettes = Collections.synchronizedList(palettes);
        this.entityInfoList = Collections.synchronizedList(entityInfoList);
    }

}
