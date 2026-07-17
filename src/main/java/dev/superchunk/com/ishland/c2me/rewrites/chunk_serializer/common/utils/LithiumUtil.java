package dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.utils;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkTickScheduler;
import it.unimi.dsi.fastutil.longs.Long2ReferenceAVLTreeMap;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;

import java.lang.reflect.Field;
import java.util.Collection;

public class LithiumUtil {
    static final Field chunkTickScheduler$TickQueuesByTimeAndPriority;


    static {
        Class<?> chunkTickSchedulerClass = LevelChunkTicks.class;

        Field tickQueuesByTimeAndPriority = null;
        try {
            tickQueuesByTimeAndPriority = chunkTickSchedulerClass.getDeclaredField("tickQueuesByTimeAndPriority");
            tickQueuesByTimeAndPriority.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // pass
        }

        chunkTickScheduler$TickQueuesByTimeAndPriority = tickQueuesByTimeAndPriority;
    }

    public static final boolean IS_LITHIUM_TICK_QUEUE_ACTIVE = chunkTickScheduler$TickQueuesByTimeAndPriority != null;

    public static <T> Collection<Collection<ScheduledTick<T>>> getTickQueueCollection(IChunkTickScheduler<T> accessor) {
        try {
            //noinspection unchecked
            Long2ReferenceAVLTreeMap<Collection<ScheduledTick<T>>> tickQueuesByTimeAndPriority =
                    (Long2ReferenceAVLTreeMap<Collection<ScheduledTick<T>>>)
                            chunkTickScheduler$TickQueuesByTimeAndPriority.get(accessor);
            return tickQueuesByTimeAndPriority.values();
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
