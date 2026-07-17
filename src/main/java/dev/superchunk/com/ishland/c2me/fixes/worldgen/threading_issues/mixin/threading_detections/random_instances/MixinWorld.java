package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading_detections.random_instances;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.common.CheckedThreadLocalRandom;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Level.class)
public class MixinWorld {

    @Shadow @Final private Thread thread;

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private RandomSource redirectWorldRandomInit() {
//        new CheckedThreadLocalRandom(RandomSupport.generateUniqueSeed(), () -> new Thread()).nextInt();
        Class<?> clazz = this.getClass();
        while (clazz != null) {
            if (clazz.getName().equals("com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld")) { // stop-gap solution
                return RandomSource.create();
            }
            clazz = clazz.getSuperclass();
        }
        return new CheckedThreadLocalRandom(RandomSupport.generateUniqueSeed(), () -> this.thread);
    }

}
