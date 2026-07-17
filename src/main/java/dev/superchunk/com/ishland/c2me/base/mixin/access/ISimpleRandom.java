package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SingleThreadedRandomSource.class)
public interface ISimpleRandom {

    @Accessor
    long getSeed();

    @Invoker("setSeed")
    void invokeSetSeed(long seed);

}
