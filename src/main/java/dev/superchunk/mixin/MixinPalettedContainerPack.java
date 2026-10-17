package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.chunkio.FastPalettePacking;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PaletteResize;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Avoids full-section allocation for uniform saves and reuses bounded scratch for mixed saves. */
@Mixin(PalettedContainer.class)
public abstract class MixinPalettedContainerPack<T> {
    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.io.fastPalettePack", "true"));

    @Shadow public volatile PalettedContainer.Data<T> data;
    @Shadow @Final private PaletteResize<T> dummyPaletteResize;
    @Shadow public abstract void acquire();
    @Shadow public abstract void release();

    @WrapMethod(method = "pack", require = 0)
    private PalettedContainerRO.PackedData<T> superchunk$pack(IdMap<T> registry,
            PalettedContainer.Strategy strategy, Operation<PalettedContainerRO.PackedData<T>> original) {
        if (!SUPERCHUNK$ENABLED || !FastPalettePacking.supports(this.data, strategy)) {
            return original.call(registry, strategy);
        }
        PalettedContainerRO.PackedData<T> packed;
        this.acquire();
        try {
            packed = FastPalettePacking.pack(this.data, registry, strategy, this.dummyPaletteResize);
        } finally {
            this.release();
        }
        return packed != null ? packed : original.call(registry, strategy);
    }
}
