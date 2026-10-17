package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization;

import com.mojang.serialization.DynamicOps;
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

/**
 * Forge 1.20.1 (plan §1.2 row 18, RENAMED): 1.20.1's readColumn is the generic
 * {@code private <T> void readColumn(ChunkPos, DynamicOps<T>, @Nullable T)} (src1201
 * SectionStorage:135) — the shadow takes the generic form; the call site passes a
 * {@code RegistryOps<Tag>} (still a {@code DynamicOps<Tag>}) + the CompoundTag.
 */
@Mixin(SectionStorage.class)
public abstract class MixinSerializingRegionBasedStorage implements SerializingRegionBasedStorageExtension {

    @Shadow @Final private RegistryAccess registryAccess;

    @Shadow protected abstract <T> void readColumn(ChunkPos chunkPos, DynamicOps<T> ops, @Nullable T tag);

    @Override
    public void update(ChunkPos pos, CompoundTag tag) {
        this.readColumn(pos, (DynamicOps<Tag>) RegistryOps.create(NbtOps.INSTANCE, this.registryAccess), (Tag) tag);
    }

}
