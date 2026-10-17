package dev.superchunk.com.ishland.c2me.rewrites.chunkio.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunkio.common.C2MEStorageVanillaInterface;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.Path;

/**
 * Forge 1.20.1 port: the IOWorker ctor is {@code (Path, boolean, String)} (src1201 IOWorker:40) —
 * the NEW target descriptor + handler params changed accordingly (plan §1.4).
 */
@Mixin(ChunkStorage.class)
public class MixinVersionedChunkStorage {

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(Ljava/nio/file/Path;ZLjava/lang/String;)Lnet/minecraft/world/level/chunk/storage/IOWorker;"))
    private IOWorker redirectStorageIoWorker(Path path, boolean dsync, String name) {
        return new C2MEStorageVanillaInterface(path, dsync, name);
    }

}
