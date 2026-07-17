package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IPOIUnloading;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PoiManager.class)
public abstract class MixinPointOfInterestStorage implements IPOIUnloading {

    @Shadow @Final private LongSet loadedChunks;

    @Override
    public boolean c2me$shouldUnloadPoi(ChunkPos pos) {
        return !this.loadedChunks.contains(pos.toLong());
    }
}
