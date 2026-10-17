package dev.superchunk.com.ishland.c2me.base.common.util;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.eventbus.EventBus;
import net.minecraftforge.eventbus.ListenerList;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.IEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forge 1.20.1 port: the neo original probes NeoForge's per-bus {@code EventBus#getListenerList}.
 * Forge eventbus 6.0.5 stores listener lists statically per event class
 * ({@link EventListenerHelper#getListenerList(Class)}) indexed by the private {@code EventBus#busID}
 * — probe that field instead; any failure degrades to {@code false} (= optimizations disabled).
 */
public class HookCompatibility {
    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME HookCompatibility");

    private static final MethodHandle MH_EventBus$busID;

    static {
        MethodHandle mh_eventBus$busID = null;
        try {
            mh_eventBus$busID = MethodHandles.privateLookupIn(EventBus.class, MethodHandles.lookup()).findGetter(EventBus.class, "busID", int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.warn("Failed to access EventBus#busID", e);
        }
        MH_EventBus$busID = mh_eventBus$busID;
    }

    private static final AtomicBoolean ISSUED_WARNINGS = new AtomicBoolean(false);

    public static boolean isChunkSaveEventFree() {
        IEventBus eventBus = MinecraftForge.EVENT_BUS;
        if (eventBus instanceof EventBus eventBusImpl && MH_EventBus$busID != null) {
            IEventListener[] listeners;
            try {
                int busID = (int) MH_EventBus$busID.invokeExact(eventBusImpl);
                ListenerList listenerList = EventListenerHelper.getListenerList(ChunkDataEvent.Save.class);
                listeners = listenerList.getListeners(busID);
            } catch (Throwable e) {
                if (ISSUED_WARNINGS.compareAndSet(false, true)) {
                    LOGGER.warn("Certain optimizations may be disabled due to the inability to determine listeners of ChunkDataEvent.Save", e);
                }
                return false;
            }
            if (listeners == null) {
                if (ISSUED_WARNINGS.compareAndSet(false, true)) {
                    LOGGER.warn("Certain optimizations may be disabled due to the inability to determine listeners of ChunkDataEvent.Save", new NullPointerException("ListenerList#getListeners returned null"));
                }
                return false;
            }
            if (listeners.length > 0) {
                if (ISSUED_WARNINGS.compareAndSet(false, true)) {
                    LOGGER.info("Certain optimizations may be disabled because ChunkDataEvent.Save is used by: {}", Arrays.toString(listeners));
                }
                return false;
            } else {
                return true;
            }
        } else {
            if (ISSUED_WARNINGS.compareAndSet(false, true)) {
                LOGGER.warn("Certain optimizations may be disabled due to unexpected implementation of MinecraftForge.EVENT_BUS: {} ({})", eventBus, eventBus.getClass());
            }
            return false;
        }
    }

}
