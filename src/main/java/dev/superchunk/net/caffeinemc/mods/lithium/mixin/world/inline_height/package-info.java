@MixinConfigOption(
        description = "Reduces indirection by inlining world height access methods",
        enabled = false //TODO find out why this crashes the render thread
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.world.inline_height;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;