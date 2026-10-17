package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructureStart.class)
public interface IStructureStart {
    @Accessor("structure")
    Structure getStructure();

    @Accessor("pieceContainer")
    PiecesContainer getChildren();

    @Accessor("references")
    int getReferences();
}
