@MixinConfigOption(description = "Use the block listening system to cache the entity suffocation check.",
depends = @MixinConfigDependency(dependencyPath = "mixin.util.block_tracking"))
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.experimental.entity.block_caching.suffocation;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;