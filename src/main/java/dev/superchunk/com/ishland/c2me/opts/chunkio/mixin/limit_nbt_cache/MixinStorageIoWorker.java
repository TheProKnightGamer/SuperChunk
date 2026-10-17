package dev.superchunk.com.ishland.c2me.opts.chunkio.mixin.limit_nbt_cache;

import dev.superchunk.com.ishland.c2me.opts.chunkio.common.Config;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.Map;

@Mixin(value = IOWorker.class, priority = 990)
public abstract class MixinStorageIoWorker {

    @Shadow @Final private Map<ChunkPos, IOWorker.PendingStore> pendingWrites;

    @Shadow protected abstract void runStore(ChunkPos pos, IOWorker.PendingStore result);

    @Shadow protected abstract void storePendingChunk();

    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "storePendingChunk", at = @At("HEAD"))
    private void preTask(CallbackInfo ci) {
        checkHardLimit();
    }

    @Inject(method = "runStore", at = @At("HEAD"))
    private void onWriteResult(CallbackInfo ci) {
        if (!this.pendingWrites.isEmpty()) {
            checkHardLimit();
            if (this.pendingWrites.size() >= Config.chunkDataCacheSoftLimit) {
                int writeFrequency = Math.min(1, (this.pendingWrites.size() - (int) Config.chunkDataCacheSoftLimit) / 16);
                for (int i = 0; i < writeFrequency; i++) {
                    writeResult0();
                }
            }
        }
    }

    @Unique
    private void checkHardLimit() {
        if (this.pendingWrites.size() >= Config.chunkDataCacheLimit) {
            LOGGER.warn("Chunk data cache size exceeded hard limit ({} >= {}), forcing writes to disk (you can increase chunkDataCacheLimit in c2me.toml)", this.pendingWrites.size(), Config.chunkDataCacheLimit);
            while (this.pendingWrites.size() >= Config.chunkDataCacheSoftLimit * 0.75) { // using chunkDataCacheSoftLimit is intentional
                writeResult0();
            }
        }
    }

    @Unique
    private void writeResult0() {
        // TODO [VanillaCopy] storePendingChunk
        Iterator<Map.Entry<ChunkPos, IOWorker.PendingStore>> iterator = this.pendingWrites.entrySet().iterator();
        if (iterator.hasNext()) {
            Map.Entry<ChunkPos, IOWorker.PendingStore> entry = iterator.next();
            iterator.remove();
            this.runStore(entry.getKey(), entry.getValue());
        }
    }

}
