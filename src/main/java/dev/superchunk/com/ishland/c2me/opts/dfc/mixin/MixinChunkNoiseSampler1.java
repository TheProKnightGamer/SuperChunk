package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IArrayCacheCapable;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.ICoordinatesFilling;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// Yarn ChunkNoiseSampler$1 -> Mojmap NoiseChunk$1 (the sliceFillingContextProvider).
// Yarn field_36595 -> this$0 (NoiseChunk).
@Mixin(targets = "net/minecraft/world/level/levelgen/NoiseChunk$1")
public class MixinChunkNoiseSampler1 implements IArrayCacheCapable, ICoordinatesFilling {
    @Shadow
    @Final
    NoiseChunk this$0;

    @Override
    public ArrayCache c2me$getArrayCache() {
        return ((IArrayCacheCapable) this.this$0).c2me$getArrayCache();
    }

    @Override
    public void c2me$fillCoordinates(int[] x, int[] y, int[] z) {
        for (int i = 0; i < ((IChunkNoiseSampler) this.this$0).getVerticalCellCount() + 1; i++) {
            x[i] = ((IChunkNoiseSampler) this.this$0).getStartBlockX() + ((IChunkNoiseSampler) this.this$0).getCellBlockX();
            y[i] = (i + ((IChunkNoiseSampler) this.this$0).getMinimumCellY()) * ((IChunkNoiseSampler) this.this$0).getVerticalCellBlockCount();
            z[i] = ((IChunkNoiseSampler) this.this$0).getStartBlockZ() + ((IChunkNoiseSampler) this.this$0).getCellBlockZ();
        }
    }
}
