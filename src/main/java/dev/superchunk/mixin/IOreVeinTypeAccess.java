package dev.superchunk.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The height range of an ore vein type ({@code OreVeinifier.VeinType}), for {@code MixinOreVeinifierRange}. */
@Mixin(targets = "net.minecraft.world.level.levelgen.OreVeinifier$VeinType")
public interface IOreVeinTypeAccess {
    @Accessor("minY")
    int superchunk$minY();

    @Accessor("maxY")
    int superchunk$maxY();
}
