package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.FeatureRegionReads;
import net.minecraft.Util;
import net.minecraft.server.level.WorldGenRegion;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Supplier;

/**
 * Summarizes {@code ensureCanWrite}'s per-block "Detected setBlock in a far chunk" error
 * ({@link FeatureRegionReads}). The write is still refused; only the logging changes.
 */
@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegionFarWrites {

    @Shadow
    @Nullable
    private Supplier<String> currentlyGenerating;

    @WrapOperation(method = "ensureCanWrite", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/Util;logAndPauseIfInIde(Ljava/lang/String;)V"), require = 0)
    private void superchunk$summarizeFarWrite(String message, Operation<Void> original) {
        Supplier<String> feature = this.currentlyGenerating;
        if (FeatureRegionReads.farWrite(feature == null ? null : feature.get(), message)) {
            original.call(message);
        }
    }
}
