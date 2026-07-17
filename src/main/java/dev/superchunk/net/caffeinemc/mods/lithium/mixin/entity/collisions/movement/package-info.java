@MixinConfigOption(
        description = "Entity movement uses optimized block access and optimized and delayed entity access." +
                " Additionally, the supporting block of entities that only move downwards is checked first. This can" +
                " profit from mixin.experimental.entity.block_caching.block_support, but it is not required.",
        depends = @MixinConfigDependency(dependencyPath = "mixin.util.chunk_access")
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.collisions.movement;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;