package dev.superchunk.com.ishland.c2me.rewrites.chunkio.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunkio.common.C2MEStorageVanillaInterface;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.Path;

/**
 * Forge 1.20.1 REPLACEMENT for the neo {@code MixinChunkPosKeyedStorage}: 1.20.1 has no
 * {@code SimpleRegionStorage} (1.20.5+) — {@code SectionStorage} constructs its IOWorker
 * directly ({@code this.worker = new IOWorker(path, sync, path.getFileName().toString())},
 * src1201 SectionStorage:57), so the same NEW-redirect lands here instead (plan §1.4).
 */
@Mixin(SectionStorage.class)
public class MixinSerializingRegionBasedStorageIo {

    @Redirect(method = "<init>", at = @At(value = "NEW", target = "(Ljava/nio/file/Path;ZLjava/lang/String;)Lnet/minecraft/world/level/chunk/storage/IOWorker;"))
    private IOWorker redirectStorageIoWorker(Path path, boolean dsync, String name) {
        return new C2MEStorageVanillaInterface(path, dsync, name);
    }

}
