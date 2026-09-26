package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.shutdown;

import dev.superchunk.com.ishland.c2me.opts.scheduling.common.ITryFlushable;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Consumer;

/**
 * Upstream C2ME {@code shutdown.MixinServerEntityManager}, ported to Mojang names (it had been left
 * out of the port): one pass of vanilla's {@code saveAll} loop body, for
 * {@link MixinServerLevel} to call between pumps of the main-thread queue.
 */
@Mixin(PersistentEntitySectionManager.class)
public abstract class MixinPersistentEntitySectionManager<T extends EntityAccess> implements ITryFlushable {

    @Shadow @Final private EntityPersistentStorage<T> permanentStorage;

    @Shadow @Final private Long2ObjectMap<Visibility> chunkVisibility;

    @Shadow protected abstract LongSet getAllChunksToSave();

    @Shadow protected abstract void processPendingLoads();

    @Shadow protected abstract boolean processChunkUnload(long chunkPos);

    @Shadow protected abstract boolean storeChunkSections(long chunkPos, Consumer<T> action);

    @Override
    public boolean c2me$tryFlush() {
        LongSet chunks = this.getAllChunksToSave();
        if (!chunks.isEmpty()) {
            this.permanentStorage.flush(false);
            this.processPendingLoads();
            chunks.removeIf(pos -> this.chunkVisibility.get(pos) == Visibility.HIDDEN
                    ? this.processChunkUnload(pos)
                    : this.storeChunkSections(pos, entity -> {
                    }));
        }
        this.permanentStorage.flush(true);
        return chunks.isEmpty();
    }
}
