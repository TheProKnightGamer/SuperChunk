package dev.superchunk.mixin;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * SuperChunk: read-only access to the {@link LevelHeightAccessor} a {@link ChunkAccess} was built
 * with.
 *
 * <p>For a chunk being generated that field IS the level ({@code ServerLevel}) — vanilla, C2ME and
 * Distant Horizons all construct their {@code ProtoChunk}s with it. The public
 * {@code getHeightAccessorForGeneration()} is NOT a substitute: on a {@code ProtoChunk} it returns
 * the chunk itself (or the below-zero retrogen accessor), which answers height queries but says
 * nothing about which level the chunk belongs to.
 *
 * <p>Used by the Distant Horizons seam ({@link dev.superchunk.compat.DhWorldGenBridge}), which is
 * handed only a list of chunks and needs the level to resolve that dimension's noise router for the
 * climate prefetch. A chunk whose accessor isn't a level is simply not prefetched.
 *
 * <p>Forge 1.20.1 port note: {@code ChunkAccess.levelHeightAccessor} is
 * {@code protected final LevelHeightAccessor} on 1.20.1 exactly as on 1.21.1 (oracle-verified), so
 * the accessor ports 1:1. {@code MixinChunkAccessInlineHeights} already {@code @Shadow}s the same
 * field from a CLASS mixin on the same target; a second mixin reading it through {@code @Accessor}
 * is independent of that one.
 */
@Mixin(ChunkAccess.class)
public interface IChunkAccessLevelHeight {

    @Accessor("levelHeightAccessor")
    LevelHeightAccessor superchunk$getLevelHeightAccessor();
}
