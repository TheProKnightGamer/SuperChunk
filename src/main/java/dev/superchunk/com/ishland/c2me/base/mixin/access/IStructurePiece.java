package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructurePiece.class)
public interface IStructurePiece {

    @Accessor("type")
    StructurePieceType getType();

    @Accessor("boundingBox")
    BoundingBox getBoundingBox();

    @Accessor("orientation")
    Direction getFacing();

    @Accessor("mirror")
    Mirror getMirror();

    @Accessor("rotation")
    Rotation getRotation();

    @Accessor("genDepth")
    int getChainLength();
}
