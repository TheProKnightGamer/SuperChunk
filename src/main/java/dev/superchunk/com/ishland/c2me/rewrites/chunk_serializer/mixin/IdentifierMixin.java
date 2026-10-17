package dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.NbtWriter;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.utils.StringBytesConvertible;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// TODO: make config option?
@Mixin(ResourceLocation.class)
public class IdentifierMixin implements StringBytesConvertible {
    @Unique
    private byte[] serializedStringBytes;

    @Override
    public byte[] getStringBytes() {
        return this.serializedStringBytes != null ? this.serializedStringBytes :
                (this.serializedStringBytes = NbtWriter.getAsciiStringBytes(this.toString()));
    }
}
