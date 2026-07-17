@MixinConfigOption(description = "Allow grouping entity classes for faster entity access, e.g. boats and shulkers",
depends = @MixinConfigDependency(dependencyPath = "mixin.util.accessors"))
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.chunk.entity_class_groups;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;