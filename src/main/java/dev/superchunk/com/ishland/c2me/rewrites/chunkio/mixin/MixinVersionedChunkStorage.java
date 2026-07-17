package dev.superchunk.com.ishland.c2me.rewrites.chunkio.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunkio.common.C2MEStorageVanillaInterface;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.Path;

@Mixin(ChunkStorage.class)
public class MixinVersionedChunkStorage {

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Z)Lnet/minecraft/world/level/chunk/storage/IOWorker;"))
    private IOWorker redirectStorageIoWorker(RegionStorageInfo arg, Path path, boolean bl) {
        return new C2MEStorageVanillaInterface(arg, path, bl);
    }

}
