@MixinConfigOption(
        description = "Avoids unnecessary raid bar updates and optimizes expensive leader banner operations",
        depends = @MixinConfigDependency(dependencyPath = "mixin.util.data_storage")
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.ai.raid;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;