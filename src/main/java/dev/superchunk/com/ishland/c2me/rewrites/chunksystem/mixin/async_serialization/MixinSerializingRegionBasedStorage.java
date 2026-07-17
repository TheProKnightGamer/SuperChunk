package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio.SerializingRegionBasedStorageExtension;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SectionStorage.class)
public abstract class MixinSerializingRegionBasedStorage implements SerializingRegionBasedStorageExtension {

    @Shadow @Final private RegistryAccess registryAccess;

    @Shadow protected abstract void readColumn(ChunkPos chunkPos, RegistryOps<Tag> ops, @Nullable CompoundTag tag);

    @Override
    public void update(ChunkPos pos, CompoundTag tag) {
        this.readColumn(pos, RegistryOps.create(NbtOps.INSTANCE, this.registryAccess), tag);
    }

}
