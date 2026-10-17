package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.nio.ByteBuffer;

@Mixin(RegionFile.class)
public interface IRegionFile {

    @Invoker("write")
    void invokeWriteChunk(ChunkPos pos, ByteBuffer byteBuffer) throws IOException;

    @Accessor("version")
    RegionFileVersion getCompressionFormat();

}
