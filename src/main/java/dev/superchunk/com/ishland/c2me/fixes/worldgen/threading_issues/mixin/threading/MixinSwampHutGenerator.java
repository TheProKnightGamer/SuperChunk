package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.asm.MakeVolatile;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SwampHutPiece.class)
public class MixinSwampHutGenerator {

    @MakeVolatile
    @Shadow private boolean spawnedWitch;

    @MakeVolatile
    @Shadow private boolean spawnedCat;

}
