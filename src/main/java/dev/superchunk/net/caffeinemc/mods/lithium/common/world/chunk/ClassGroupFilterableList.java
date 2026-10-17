package dev.superchunk.net.caffeinemc.mods.lithium.common.world.chunk;

import dev.superchunk.net.caffeinemc.mods.lithium.common.entity.EntityClassGroup;

import java.util.Collection;

public interface ClassGroupFilterableList<T> {
    Collection<T> lithium$getAllOfGroupType(EntityClassGroup type);

}