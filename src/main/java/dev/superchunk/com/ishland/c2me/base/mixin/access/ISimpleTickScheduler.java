package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SerializableTickContainer;
import net.minecraft.world.ticks.SavedTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ProtoChunkTicks.class)
public interface ISimpleTickScheduler<T> extends SerializableTickContainer<T> {
    @Accessor("ticks")
    List<SavedTick<T>> getScheduledTicks();
}
