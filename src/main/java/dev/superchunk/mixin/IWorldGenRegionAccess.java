package dev.superchunk.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for {@link WorldGenRegion}'s private center chunk (used by {@code MixinOreFeatureFast}). */
@Mixin(WorldGenRegion.class)
public interface IWorldGenRegionAccess {

    @Accessor("center")
    ChunkAccess superchunk$center();
}
