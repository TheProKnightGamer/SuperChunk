package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.idle_tasks.autosave.disable_vanilla_mid_tick_autosave;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkMap.class)
public class MixinThreadedAnvilChunkStorage {

    @Unique
    private static final Long2ObjectLinkedOpenHashMap<ChunkHolder> anEmptyChunkHoldersMap = new Long2ObjectLinkedOpenHashMap<>();

    @Redirect(method = "processUnloads", at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ChunkMap;visibleChunkMap:Lit/unimi/dsi/fastutil/longs/Long2ObjectLinkedOpenHashMap;"))
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> stopAutoSaveInUnloading(ChunkMap instance) {
        return anEmptyChunkHoldersMap; // prevent autosave from happening in unloading stage
    }

}
