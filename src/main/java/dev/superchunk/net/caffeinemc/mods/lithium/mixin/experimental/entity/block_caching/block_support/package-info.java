@MixinConfigOption(description = "Use the block listening system to skip supporting block search (used for honey block pushing, velocity modifiers like soulsand, etc)",
        depends = @MixinConfigDependency(dependencyPath = "mixin.util.block_tracking"))
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.experimental.entity.block_caching.block_support;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;