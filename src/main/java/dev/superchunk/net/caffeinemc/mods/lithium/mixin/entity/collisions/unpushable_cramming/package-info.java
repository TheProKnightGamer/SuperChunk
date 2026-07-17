@MixinConfigOption(
        description = "In chunks with many mobs in ladders a separate list of pushable entities for cramming tests is used",
        depends = {
                @MixinConfigDependency(dependencyPath = "mixin.chunk.entity_class_groups")
        }
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.collisions.unpushable_cramming;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;