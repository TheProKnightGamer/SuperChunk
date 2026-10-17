package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.asm.MakeVolatile;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(OceanMonumentPieces.RoomDefinition.class)
public class MixinOceanMonumentGeneratorPieceSetting {

    @MakeVolatile
    @Shadow boolean claimed;

    @MakeVolatile
    @Shadow boolean isSource;

    @MakeVolatile
    @Shadow private int scanIndex;

}
