package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.asm.MakeVolatile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Mojmap port of upstream C2ME's MixinWoodlandMansionGeneratorGenerationPiece (dropped in
 * the original merge): makes {@code PlacementData}'s (yarn {@code GenerationPiece}) fields
 * volatile for cross-thread visibility during parallel mansion assembly. String target:
 * the mojmap inner class is package-private.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionPieces$PlacementData")
public class MixinWoodlandMansionGeneratorGenerationPiece {

    @MakeVolatile
    @Shadow public Rotation rotation;

    @MakeVolatile
    @Shadow public BlockPos position;

    @MakeVolatile
    @Shadow public String wallType;

}
