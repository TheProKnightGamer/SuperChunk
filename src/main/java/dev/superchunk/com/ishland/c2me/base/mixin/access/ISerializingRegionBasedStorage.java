package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface ISerializingRegionBasedStorage {

    @Accessor("simpleRegionStorage")
    SimpleRegionStorage getStorageAccess();

}
