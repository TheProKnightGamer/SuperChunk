@MixinConfigOption(
        description = "System to notify subscribers of certain entity sections about position changes of certain entity types.",
        depends = {
                @MixinConfigDependency(dependencyPath = "mixin.util.entity_section_position"),
                @MixinConfigDependency(dependencyPath = "mixin.util.data_storage")
        }
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.entity_movement_tracking;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;