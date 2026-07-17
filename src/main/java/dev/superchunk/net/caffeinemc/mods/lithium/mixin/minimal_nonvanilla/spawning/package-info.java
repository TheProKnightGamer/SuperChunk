@MixinConfigOption(
        description = "Optimizations to spawning conditions. Reorders the iteration over entities to match the chunks " +
                "and chunk sections, reducing the number of cache misses. Might differ slightly from vanilla due to " +
                "floating point associativity differences when summing the spawning potential of density controlled " +
                "spawns, e.g. skeleton, ghast, enderman and strider spawns in certain nether biomes.")
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.spawning;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;