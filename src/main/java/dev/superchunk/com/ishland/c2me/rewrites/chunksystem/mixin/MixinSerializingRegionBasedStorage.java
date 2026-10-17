package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.Config;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IPOIUnloading;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Optional;

@Mixin(SectionStorage.class)
public abstract class MixinSerializingRegionBasedStorage<R> implements IPOIUnloading {

    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;

    @Shadow @Final private Long2ObjectMap<Optional<R>> storage;

    @Shadow public abstract void flush(ChunkPos pos);

    @Override
    public void c2me$unloadPoi(ChunkPos pos) {
        if (!Config.allowPOIUnloading) return;

        if (!this.c2me$shouldUnloadPoi(pos)) {
            return;
        }

        this.flush(pos);
        for (int i = this.levelHeightAccessor.getMinSection(); i <= this.levelHeightAccessor.getMaxSection(); i++) {
            this.storage.remove(SectionPos.asLong(pos.x, i, pos.z));
        }
    }

}
