package dev.superchunk.com.ishland.c2me.opts.scheduling.common;

import net.minecraft.world.level.LightLayer;

public interface DuckChunkHolder {

    void c2me$queueLightSectionDirty(LightLayer lightType, int sectionY);

    boolean c2me$shouldScheduleUndirty();

    void c2me$undirtyLight();

}
