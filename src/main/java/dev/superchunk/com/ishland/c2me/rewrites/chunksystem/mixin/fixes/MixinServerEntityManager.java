package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.fixes;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PersistentEntitySectionManager.class)
public abstract class MixinServerEntityManager {

    @Mutable
    @Shadow @Final private LongSet chunksToUnload;

    @Shadow protected abstract boolean processChunkUnload(long chunkPos);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void replacePendingUnloads(CallbackInfo ci) {
        this.chunksToUnload = new LongLinkedOpenHashSet(this.chunksToUnload);
    }

    /**
     * @author ishland
     * @reason use alternative method for unloading
     */
    @Overwrite
    private void processUnloads() {
        LongSet pendingUnloads = this.chunksToUnload;
        if (!(pendingUnloads instanceof LongLinkedOpenHashSet)) {
            // set is replaced by someone else, replace it again
            pendingUnloads = this.chunksToUnload = new LongLinkedOpenHashSet(pendingUnloads);
        }

        // apparently vanilla also have a `this.trackingStatuses.get(pos) != EntityTrackingStatus.HIDDEN` check?????
        // removed here because chunks in pendingUnloads should always satisfy that constraint
        // if it does not, you have other serious problems
        LongLinkedOpenHashSet linkedOpenHashSet = (LongLinkedOpenHashSet) pendingUnloads;
        while (!linkedOpenHashSet.isEmpty()) {
            long pos = linkedOpenHashSet.removeFirstLong();
            this.processChunkUnload(pos);
        }
    }

}
