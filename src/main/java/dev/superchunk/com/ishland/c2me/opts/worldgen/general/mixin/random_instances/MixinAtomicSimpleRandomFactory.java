package dev.superchunk.com.ishland.c2me.opts.worldgen.general.mixin.random_instances;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LegacyRandomSource.LegacyPositionalRandomFactory.class)
public abstract class MixinAtomicSimpleRandomFactory implements PositionalRandomFactory {

    @Shadow @Final private long seed;

    /**
     * @author ishland
     * @reason non-atomic
     */
    @Overwrite
    @Override
    public RandomSource at(int x, int y, int z) { // TODO [VanillaCopy]
        long l = Mth.getSeed(x, y, z);
        long m = l ^ this.seed;
        return new SingleThreadedRandomSource(m);
    }

    /**
     * @author ishland
     * @reason non-atomic
     */
    @Overwrite
    @Override
    public RandomSource fromHashOf(String string) { // TODO [VanillaCopy]
        int i = string.hashCode();
        return new SingleThreadedRandomSource((long)i ^ this.seed);
    }

}
