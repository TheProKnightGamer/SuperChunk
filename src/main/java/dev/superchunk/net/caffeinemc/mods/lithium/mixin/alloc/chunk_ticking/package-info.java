/**
 * This package includes a patch that reduces the memory usage of Chunk ticking. Instead of creating a new ArrayList for
 * every tick, the previous list is cleared and reused.
 */
@MixinConfigOption(description = "Reuse large chunk lists")
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.alloc.chunk_ticking;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;