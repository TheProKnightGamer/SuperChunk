@MixinConfigOption(
        description = "Block updates skip notifying mobs that won't react to the block update anyways",
        depends = @MixinConfigDependency(dependencyPath = "mixin.util.data_storage")
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.entity.inactive_navigations;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;