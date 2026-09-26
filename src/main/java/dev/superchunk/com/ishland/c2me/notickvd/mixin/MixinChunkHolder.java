package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder implements dev.superchunk.com.ishland.c2me.notickvd.common.IPendingBroadcast {

    @Shadow private boolean hasChangedSections;

    @Shadow @org.spongepowered.asm.mixin.Final private java.util.BitSet blockChangedLightSectionFilter;

    @Shadow @org.spongepowered.asm.mixin.Final private java.util.BitSet skyChangedLightSectionFilter;

    @Override
    public boolean superchunk$hasPendingBroadcast() {
        return this.hasChangedSections || !this.skyChangedLightSectionFilter.isEmpty()
                || !this.blockChangedLightSectionFilter.isEmpty();
    }

    @Shadow public abstract CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture();

    @Redirect(method = {"blockChanged", "sectionLightChanged"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"), require = 2)
    private LevelChunk redirectWorldChunk(ChunkHolder chunkHolder) {
        return this.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
    }

}
