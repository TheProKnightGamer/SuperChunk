package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import com.mojang.datafixers.util.Either;
import dev.superchunk.com.ishland.c2me.notickvd.common.IChunkHolder;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

/**
 * 1.20.1 port: {@code ChunkResult} → {@code Either} (plan §3.3). Also implements the
 * {@link IChunkHolder} duck (the original upstream-1.20.x C2ME pattern — vestigial on neo, load-
 * bearing here: notickvd {@code MixinServerChunkManager} consumes it). Both target bodies call
 * {@code this.getTickingChunk()} (verified 1.20.1 sources lines 150/163) — redirected so block/
 * light broadcasts reach border (no-tick) chunks.
 */
@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder implements IChunkHolder, dev.superchunk.com.ishland.c2me.notickvd.common.IPendingBroadcast {

    @Shadow private boolean hasChangedSections;

    @Shadow @org.spongepowered.asm.mixin.Final private java.util.BitSet blockChangedLightSectionFilter;

    @Shadow @org.spongepowered.asm.mixin.Final private java.util.BitSet skyChangedLightSectionFilter;

    @Override
    public boolean superchunk$hasPendingBroadcast() {
        return this.hasChangedSections || !this.skyChangedLightSectionFilter.isEmpty()
                || !this.blockChangedLightSectionFilter.isEmpty();
    }

    @Shadow public abstract CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> getFullChunkFuture();

    @Override
    public LevelChunk getAccessibleChunk() {
        return this.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).left().orElse(null);
    }

    @Redirect(method = {"blockChanged", "sectionLightChanged"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"),
            require = 2)
    private LevelChunk redirectWorldChunk(ChunkHolder chunkHolder) {
        return this.getAccessibleChunk();
    }

}
