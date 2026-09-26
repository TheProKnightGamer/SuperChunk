package dev.superchunk.com.ishland.c2me.opts.scheduling.common;

/** Implemented on {@code PersistentEntitySectionManager} by {@code shutdown.MixinPersistentEntitySectionManager}. */
public interface ITryFlushable {

    /** One non-blocking pass of {@code saveAll}; {@code true} once every chunk's entities are saved or unloaded. */
    boolean c2me$tryFlush();
}
