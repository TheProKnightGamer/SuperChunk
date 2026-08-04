package dev.superchunk.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code LevelRenderer.viewArea} (private) so {@link dev.superchunk.client.GrayChunkPlaceholders}
 * can enumerate the client's render sections to find loaded-but-not-yet-meshed chunks.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererViewAreaAccessor {
    @Accessor("viewArea")
    ViewArea superchunk$viewArea();
}
