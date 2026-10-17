package dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.mixin;

import dev.superchunk.com.ishland.c2me.base.common.theinterface.IDirectStorage;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IVersionedChunkStorage;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.ChunkDataSerializer;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.NbtWriter;
import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.file.Path;

/**
 * Forge 1.20.1 port: the @Overwrite body is re-derived from the FORGE-PATCHED 1.20.1
 * {@code ChunkMap.save(ChunkAccess)} (src1201 ChunkMap:808-840) with only the
 * {@code ChunkSerializer.write} + {@code this.write} pair replaced by the gc-free writer.
 *
 * <p>KNOWN, DOCUMENTED DROP: the Forge patch posts {@code ChunkDataEvent.Save} with the
 * serialized CompoundTag (src1201 ChunkMap:830). The gc-free path never materializes a
 * CompoundTag, so the event CANNOT be fired faithfully here — mirrored from the neo build,
 * announced once per boot by {@code ModuleEntryPoint.init()}, and revertible via the module
 * kill switches ({@code ioSystem.gcFreeChunkSerializer} / {@code -Dsuperchunk.chunkSerializer.enable=false}).
 */
@Mixin(value = ChunkMap.class, priority = 1099)
public abstract class MixinThreadedAnvilChunkStorage extends ChunkStorage {
    @Final
    @Shadow
    private static Logger LOGGER;

    @Final
    @Shadow
    private PoiManager poiManager;

    @Final
    @Shadow
    public ServerLevel level;

    public MixinThreadedAnvilChunkStorage(Path path, DataFixer dataFixer, boolean bl) {
        super(path, dataFixer, bl);
    }

    @Shadow
    private native boolean isExistingChunkFull(ChunkPos chunkPos);

    @Shadow
    private native byte markPosition(ChunkPos chunkPos, ChunkStatus.ChunkType chunkType);


    /**
     * @author Kroppeb
     * @reason Reduces allocations
     */
    @Overwrite()
    private boolean save(ChunkAccess chunk) {
        // [VanillaCopy] Forge-patched 1.20.1 ChunkMap.save
        this.poiManager.flush(chunk.getPos());
        if (!chunk.isUnsaved()) {
            return false;
        }

        chunk.setUnsaved(false);
        ChunkPos chunkPos = chunk.getPos();

        try {
            ChunkStatus chunkStatus = chunk.getStatus();
            if (chunkStatus.getChunkType() != ChunkStatus.ChunkType.LEVELCHUNK) {
                if (this.isExistingChunkFull(chunkPos)) {
                    return false;
                }

                if (chunkStatus == ChunkStatus.EMPTY && chunk.getAllStarts().values().stream().noneMatch(StructureStart::isValid)) {
                    return false;
                }
            }

            this.level.getProfiler().incrementCounter("chunkSave");

            //region start replaced code
            // CompoundTag nbtCompound = ChunkSerializer.write(this.level, chunk);
            // MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(...)); // DROPPED — see class javadoc
            // this.write(chunkPos, nbtCompound);
            NbtWriter nbtWriter = new NbtWriter();
            try {
                nbtWriter.start(Tag.TAG_COMPOUND);
                ChunkDataSerializer.write(this.level, chunk, nbtWriter);
                nbtWriter.finishCompound();

                ((IDirectStorage) ((IVersionedChunkStorage) this).getWorker()).setRawChunkData(chunkPos, nbtWriter.toByteArray());
            } finally {
                // toByteArray() copied to the heap, so freeing the off-heap buffer here is safe
                // even on the exception path (e.g. when caught by the outer handler below).
                nbtWriter.release();
            }

            //endregion end replaced code

            this.markPosition(chunkPos, chunkStatus.getChunkType());
            return true;
        } catch (Exception var5) {
            LOGGER.error("Failed to save chunk {},{}", chunkPos.x, chunkPos.z, var5);
            return false;
        }
    }
}
