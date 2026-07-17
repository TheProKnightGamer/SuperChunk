package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LegacyRandomSource.LegacyPositionalRandomFactory.class)
public interface IAtomicSimpleRandomDeriver {

    @Accessor
    long getSeed();

}
