package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface ISerializingRegionBasedStorage {

    // 1.20.1: SectionStorage owns its IOWorker directly ("worker" field);
    // SimpleRegionStorage is 1.20.5+ (port plan §1.7).
    @Accessor("worker")
    IOWorker getStorageAccess();

}
