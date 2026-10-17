@MixinConfigOption(description = "Use the block listening system to skip block touching (like cactus touching).",
depends = @MixinConfigDependency(dependencyPath = "mixin.util.block_tracking"))
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.experimental.entity.block_caching.block_touching;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;