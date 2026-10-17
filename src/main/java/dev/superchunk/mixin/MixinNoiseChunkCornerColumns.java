package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.gpu.dfc.OnDeviceInterp;
import dev.superchunk.worldgen.BlockFillHooks;
import dev.superchunk.worldgen.CornerColumns;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Shares cell-corner columns between neighbouring chunks ({@link CornerColumns}): {@code fillSlice}
 * copies a column another chunk of the same {@code RandomState} already computed for an
 * interpolator, and publishes the ones it computes.
 */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunkCornerColumns {

    @Shadow @Final List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow @Final private int cellHeight;
    @Shadow @Final private int cellWidth;
    @Shadow @Final private int cellCountY;
    @Shadow @Final private int cellNoiseMinY;
    @Shadow private int cellStartBlockX;
    @Shadow private int cellStartBlockZ;

    @Shadow public abstract Blender getBlender();

    @Unique private CornerColumns superchunk$corners;
    /** 0 unknown, 1 shared, -1 not for this chunk. */
    @Unique private byte superchunk$cornersState;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void superchunk$bindCorners(CallbackInfo ci, @Local(argsOnly = true) RandomState random) {
        if (CornerColumns.ENABLED && (Object) random instanceof CornerColumns.Owner owner) {
            this.superchunk$corners = owner.superchunk$cornerColumns();
        }
    }

    @WrapOperation(method = "fillSlice", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"),
            require = 0)
    private void superchunk$sharedColumn(NoiseChunk.NoiseInterpolator interpolator, double[] column,
                                         DensityFunction.ContextProvider context, Operation<Void> original) {
        CornerColumns table = this.superchunk$corners;
        if (table == null || !superchunk$sharing(table)) {
            original.call(interpolator, column, context);
            return;
        }
        int index = this.interpolators.indexOf(interpolator);
        long key = CornerColumns.key(index, this.cellStartBlockX, this.cellStartBlockZ);
        double[] shared = key < 0 ? null : table.get(key, column.length);
        if (shared == null) {
            original.call(interpolator, column, context);
            if (key >= 0) {
                table.put(key, column);
            }
            return;
        }
        if (CornerColumns.VERIFY) {
            original.call(interpolator, column, context);
            CornerColumns.verify(shared, column, index, this.cellStartBlockX, this.cellStartBlockZ);
            return;
        }
        System.arraycopy(shared, 0, column, 0, column.length);
    }

    /** The per-chunk conditions, decided at the first column (foreign hooks need loaded classes). */
    @Unique
    private boolean superchunk$sharing(CornerColumns table) {
        byte state = this.superchunk$cornersState;
        if (state == 0) {
            boolean ok = this.getBlender() == Blender.empty()
                    && !OnDeviceInterp.ENABLED
                    && this.interpolators.size() <= 64
                    && BlockFillHooks.unhooked()
                    && table.accepts(this.cellHeight, this.cellNoiseMinY, this.cellCountY, this.cellWidth);
            state = ok ? (byte) 1 : (byte) -1;
            this.superchunk$cornersState = state;
        }
        return state > 0;
    }
}
