/**
 * This package includes patches that reduce the memory usage and performance impact of Enum usages. The defensive copy
 * in Enum values() function is avoided by reusing the came copy that is stored in a static final field.
 */
@MixinConfigOption(description = "Avoid `Enum#values()` array copy in frequently called code")
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.alloc.enum_values;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;