package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading.math;

import com.mojang.math.OctahedralGroup;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OctahedralGroup.class)
public abstract class MixinDirectionTransformation {

    @Shadow public abstract Direction rotate(Direction direction);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        rotate(Direction.UP); // force load mapping to prevent further issues
    }

}
