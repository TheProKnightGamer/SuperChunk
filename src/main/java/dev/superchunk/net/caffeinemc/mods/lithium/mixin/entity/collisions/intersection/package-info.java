@MixinConfigOption(
        description = "Uses faster block access for block collisions and delayed entity access with grouped boat/shulker for entity collisions when available",
        depends = {
                @MixinConfigDependency(dependencyPath = "mixin.util.chunk_access")
        }
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.collisions.intersection;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;