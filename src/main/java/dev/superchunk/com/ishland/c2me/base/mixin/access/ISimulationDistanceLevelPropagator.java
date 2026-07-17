package dev.superchunk.com.ishland.c2me.base.mixin.access;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.TickingTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TickingTracker.class)
public interface ISimulationDistanceLevelPropagator {

    @Accessor("chunks")
    Long2ByteMap getLevels();

}
