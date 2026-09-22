package dev.superchunk.mixin;

import dev.superchunk.worldgen.BiomeFiddleMath;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Avoids signed remainder and its negative-value correction in all 24 biome jitter samples. */
@Mixin(BiomeManager.class)
public abstract class MixinBiomeManagerFiddle {
    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.biomeFiddle", "true"));

    @Redirect(method = "getFiddle", at = @At(value = "INVOKE", target = "Ljava/lang/Math;floorMod(JI)I"), require = 0)
    private static int superchunk$powerOfTwoModulus(long value, int divisor) {
        return SUPERCHUNK$ENABLED && divisor == 1024
                ? BiomeFiddleMath.floorMod1024(value) : Math.floorMod(value, divisor);
    }
}
