@MixinConfigOption(description = "Use block listening system to allow skipping stuff in entity code",
depends = @MixinConfigDependency(dependencyPath = "mixin.util.block_tracking"))
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.experimental.entity.block_caching;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;