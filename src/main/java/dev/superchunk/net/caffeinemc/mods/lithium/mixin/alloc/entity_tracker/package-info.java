/**
 * This package includes a patch that reduces the memory usage and performance impact of Entity trackers by replacing the
 * IdentityHashSet with a hashset from the fastutil library.
 */
@MixinConfigOption(description = "Entity trackers use a fastutil set for storing players instead of an IdentityHashSet")
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.alloc.entity_tracker;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;