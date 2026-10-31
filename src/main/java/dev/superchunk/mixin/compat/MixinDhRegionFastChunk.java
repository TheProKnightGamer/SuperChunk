package dev.superchunk.mixin.compat;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Distant Horizons' world-gen region answers a chunk of its own group without its lock.
 *
 * <p>DH generates each group of chunks through a {@code DhLitWorldGenRegion}, a
 * {@code WorldGenRegion} whose {@code getChunk(x, z)} takes a {@code ReentrantLock}, then calls
 * {@code getChunk(x, z, EMPTY)}, which takes it again. Every block read and write of feature
 * placement goes through it, so on a DH pregen the two lock/unlock pairs were about 8% of DH's
 * world-gen CPU. The lock guards the region's fallback map of chunks outside the group, which DH
 * fills from its generator on first use. A chunk of the group itself comes from a list built
 * before the region and never changed, and each region is used by the one DH thread that made it.
 *
 * <p>So a chunk of the group is returned here as DH's own path would return it (status check
 * included; a {@code LevelChunk} wrapped in an {@code ImposterProtoChunk}), without the lock.
 * Every other request takes DH's locked path unchanged.
 *
 * <p>Applied only when the DH methods this relies on hash to the version it was checked against
 * ({@link dev.superchunk.compat.DhFastPaths}). Kill switch: {@code -Dsuperchunk.dh.fastPaths=false}.
 */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.mimicObject.DhLitWorldGenRegion_neoforge",
        remap = false)
public abstract class MixinDhRegionFastChunk {

    @Shadow
    @Final
    private ReentrantLock getChunkLock;

    @Shadow
    public abstract boolean dhHasChunk(int x, int z);

    @Shadow
    private ChunkAccess dhGetChunk(int x, int z) {
        throw new AssertionError();
    }

    @Shadow
    public abstract ChunkAccess getChunk(int chunkX, int chunkZ, ChunkStatus chunkStatus);

    /**
     * @author SuperChunk
     * @reason Skip the region lock for chunks of the group (see the class comment).
     */
    @Overwrite
    public ChunkAccess getChunk(int chunkX, int chunkZ) {
        if (this.dhHasChunk(chunkX, chunkZ)) {
            ChunkAccess chunk = this.dhGetChunk(chunkX, chunkZ);
            if (chunk != null && chunk.getPersistedStatus().isOrAfter(ChunkStatus.EMPTY)) {
                return chunk instanceof LevelChunk levelChunk ? new ImposterProtoChunk(levelChunk, false) : chunk;
            }
        }
        this.getChunkLock.lock();
        try {
            return this.getChunk(chunkX, chunkZ, ChunkStatus.EMPTY);
        } finally {
            this.getChunkLock.unlock();
        }
    }
}
