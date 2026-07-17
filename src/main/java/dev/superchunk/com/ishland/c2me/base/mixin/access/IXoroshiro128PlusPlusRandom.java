package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(XoroshiroRandomSource.class)
public interface IXoroshiro128PlusPlusRandom {

    @Accessor("randomNumberGenerator")
    Xoroshiro128PlusPlus getImplementation();

}
