package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;

@Mixin(RegionFileStorage.class)
public interface IRegionBasedStorage {

    @Invoker("getRegionFile")
    RegionFile invokeGetRegionFile(ChunkPos pos) throws IOException;

}
