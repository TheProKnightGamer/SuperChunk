package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.entity.ai.behavior.ShufflingList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ShufflingList.WeightedEntry.class)
public interface IWeightedListEntry {

    @Invoker("getRandWeight")
    double invokeGetShuffledOrder();

    @Invoker("setRandom")
    void invokeSetShuffledOrder(float random);

}
