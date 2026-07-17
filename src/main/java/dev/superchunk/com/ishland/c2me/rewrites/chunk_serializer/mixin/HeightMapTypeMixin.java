package dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.HeightMapTypeAccessor;
import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.NbtWriter;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

@Mixin(Heightmap.Types.class)
public class HeightMapTypeMixin implements HeightMapTypeAccessor {
    private byte[] nameBytes;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void postInit(String enum$name, int enum$ordinal, String serializationKey, Heightmap.Usage usage, Predicate<?> isOpaque, CallbackInfo ci) {
        this.nameBytes = NbtWriter.getStringBytes(serializationKey);
    }

    @Override
    public byte[] getNameBytes() {
        return this.nameBytes;
    }

}
