package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.accessors;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * SuperChunk (Forge 1.20.1): {@code VoxelShape.getCoords} is protected (public from 1.21).
 * Reached through an invoker instead of an access transformer, for the reason in
 * {@link PoiSectionAccessor}.
 */
@Mixin(VoxelShape.class)
public interface VoxelShapeAccessor {
    @Invoker("getCoords")
    DoubleList lithium$getCoords(Direction.Axis axis);
}
