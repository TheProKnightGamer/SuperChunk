package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.fixes;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongPredicate;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PersistentEntitySectionManager.class)
public abstract class MixinServerEntityManager {

    @Mutable
    @Shadow @Final private LongSet chunksToUnload;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void replacePendingUnloads(CallbackInfo ci) {
        this.chunksToUnload = new LongLinkedOpenHashSet(this.chunksToUnload);
    }

    /**
     * Upstream C2ME overwrites {@code processUnloads}; this wraps its one call instead, so the method stays vanilla's
     * for other mods. Mixin refuses another mod's injector into a method overwritten at a higher priority (C2ME's is
     * 1100), and Valkyrien Skies' {@code @ModifyArg} on this {@code removeIf} crashed the server at world load (#27).
     * Same unloading as upstream: every pending chunk in order, each removed whatever the unload returns. It goes
     * through vanilla's predicate, so a wrapper another mod puts on it still runs; the predicate's visibility check
     * passes for every chunk in the set ({@code updateChunkStatus} removes a chunk that stops being hidden). The
     * call takes fastutil's {@code LongPredicate} in the loader's patched class, which is the one that runs (the
     * vanilla jar's takes {@code java.util.function}'s).
     */
    @WrapOperation(method = "processUnloads", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongSet;removeIf(Lit/unimi/dsi/fastutil/longs/LongPredicate;)Z"))
    private boolean unloadInOrder(LongSet set, LongPredicate unload, Operation<Boolean> original) {
        LongSet pendingUnloads = this.chunksToUnload;
        if (set != pendingUnloads) {
            return original.call(set, unload);
        }
        if (!(pendingUnloads instanceof LongLinkedOpenHashSet)) {
            // set is replaced by someone else, replace it again
            pendingUnloads = this.chunksToUnload = new LongLinkedOpenHashSet(pendingUnloads);
        }

        LongLinkedOpenHashSet linkedOpenHashSet = (LongLinkedOpenHashSet) pendingUnloads;
        boolean any = !linkedOpenHashSet.isEmpty();
        while (!linkedOpenHashSet.isEmpty()) {
            long pos = linkedOpenHashSet.removeFirstLong();
            unload.test(pos);
        }
        return any;
    }

}
