package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.task_scheduling;

import dev.superchunk.com.ishland.c2me.opts.scheduling.common.DuckChunkHolder;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder implements DuckChunkHolder {

    @Shadow public abstract void sectionLightChanged(LightLayer lightType, int y);

    @Shadow @Final private LevelLightEngine lightEngine;
    private AtomicIntegerArray[] c2me$dirtyLightSections;
    private final AtomicBoolean c2me$scheduledLightUndirty = new AtomicBoolean(false);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(ChunkPos pos, int level, LevelHeightAccessor world, LevelLightEngine lightingProvider, ChunkHolder.LevelChangeListener levelUpdateListener, ChunkHolder.PlayerProvider playersWatchingChunkProvider, CallbackInfo ci) {
        c2me$dirtyLightSections = new AtomicIntegerArray[LightLayer.values().length];
        for (int i = 0; i < c2me$dirtyLightSections.length; i++) {
            c2me$dirtyLightSections[i] = new AtomicIntegerArray(this.lightEngine.getMaxLightSection() - this.lightEngine.getMinLightSection() + 1);
        }
    }

    @Override
    public void c2me$queueLightSectionDirty(LightLayer lightType, int sectionY) {
        if (sectionY >= this.lightEngine.getMinLightSection() && sectionY <= this.lightEngine.getMaxLightSection())
            this.c2me$dirtyLightSections[lightType.ordinal()].set(sectionY - this.lightEngine.getMinLightSection(), 1);
    }

    @Override
    public boolean c2me$shouldScheduleUndirty() {
        return this.c2me$scheduledLightUndirty.compareAndSet(false, true);
    }

    @Override
    public void c2me$undirtyLight() {
        if (!this.c2me$scheduledLightUndirty.compareAndSet(true, false)) {
            return;
        }
        AtomicIntegerArray[] me$dirtyLightSections = this.c2me$dirtyLightSections;
        final int bottomY = this.lightEngine.getMinLightSection();
        for (int __i = 0, me$dirtyLightSectionsLength = me$dirtyLightSections.length; __i < me$dirtyLightSectionsLength; __i++) {
            AtomicIntegerArray section = me$dirtyLightSections[__i];
            LightLayer lightType = LightLayer.values()[__i];
            for (int j = 0; j < section.length(); j++) {
                if (section.compareAndSet(j, 1, 0)) {
                    this.sectionLightChanged(lightType, j + bottomY);
                }
            }
        }

    }

}
