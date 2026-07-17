@MixinConfigOption(
        description = "Skips repeated checks whether the equipment of an entity changed. " +
        "Equipment updates are detected instead.",
        depends = @MixinConfigDependency(dependencyPath = "mixin.util.item_component_and_count_tracking")
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.equipment_tracking;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;