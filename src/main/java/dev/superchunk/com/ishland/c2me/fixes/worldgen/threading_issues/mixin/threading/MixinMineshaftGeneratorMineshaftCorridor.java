package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.asm.MakeVolatile;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(MineshaftPieces.MineShaftCorridor.class)
public class MixinMineshaftGeneratorMineshaftCorridor {

    @MakeVolatile
    @Shadow private boolean hasPlacedSpider;

}
