package dev.superchunk.com.ishland.c2me.base.common.util;

import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class HookCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME HookCompatibility");

    private static final MethodHandle MH_EventBus$getListenerList;
    private static final MethodHandle MH_ListenerList$getListenersPriority;

    static {
        {
            MethodHandle mh_eventBus$getListenerList = null;
            try {
                mh_eventBus$getListenerList = MethodHandles.privateLookupIn(EventBus.class, MethodHandles.lookup()).findVirtual(EventBus.class, "getListenerList", MethodType.methodType(ListenerList.class, Class.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                LOGGER.warn("Failed to access EventBus#getListenerList", e);
            }
            MH_EventBus$getListenerList = mh_eventBus$getListenerList;
        }
        {
            MethodHandle mh_ListenerList$getListeners = null;
            try {
                mh_ListenerList$getListeners = MethodHandles.privateLookupIn(ListenerList.class, MethodHandles.lookup()).findVirtual(ListenerList.class, "getListeners", MethodType.methodType(ArrayList.class, EventPriority.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                LOGGER.warn("Failed to access ListenerList#getListeners", e);
            }
            MH_ListenerList$getListenersPriority = mh_ListenerList$getListeners;
        }
    }

    private static final AtomicBoolean ISSUED_WARNINGS = new AtomicBoolean(false);

    public static boolean isChunkSaveEventFree() {
        IEventBus eventBus = NeoForge.EVENT_BUS;
        if (eventBus instanceof EventBus eventBusImpl) {
            ListenerList listenerList;
            EventListener[] listeners = null;
            try {
                listenerList = (ListenerList) MH_EventBus$getListenerList.invokeExact(eventBusImpl, ChunkDataEvent.Save.class);
                listeners = listenerList.getListeners();
            } catch (Throwable e) {
                if (ISSUED_WARNINGS.compareAndSet(false, true)) {
                    LOGGER.warn("Certain optimizations may be disabled due to the inability to determine listeners of ChunkDataEvent.Save", e);
                }
                return false;
            }
            if (listeners == null) {
                if (ISSUED_WARNINGS.compareAndSet(false, true)) {
                    LOGGER.warn("Certain optimizations may be disabled due to the inability to determine listeners of ChunkDataEvent.Save", new NullPointerException("EventBus#getListenerList returned null"));
                }
                return false;
            }
            if (listeners.length > 0) {
                if (ISSUED_WARNINGS.compareAndSet(false, true)) {
                    EventListener[] rawListeners = tryGetRawListeners(listenerList);
                    LOGGER.warn("Certain optimizations may be disabled because ChunkDataEvent.Save is used by: {}", rawListeners != null ? Arrays.toString(rawListeners) : Arrays.toString(listeners));
                }
                return false;
            } else {
                return true;
            }
        } else {
            if (ISSUED_WARNINGS.compareAndSet(false, true)) {
                LOGGER.warn("Certain optimizations may be disabled due to unexpected implementation of NeoForge.EVENT_BUS: {} ({})", eventBus, eventBus.getClass());
            }
            return false;
        }
    }

    private static EventListener[] tryGetRawListeners(ListenerList listenerList) {
        try {
            ArrayList<EventListener> ret = new ArrayList<>();
            for (EventPriority priority : EventPriority.values()) {
                ret.addAll((ArrayList<EventListener>) MH_ListenerList$getListenersPriority.invokeExact(listenerList, priority));
            }
            return ret.toArray(EventListener[]::new);
        } catch (Throwable t) {
            LOGGER.warn("Failed to fetch raw listener list", t);
            return null;
        }
    }

}
