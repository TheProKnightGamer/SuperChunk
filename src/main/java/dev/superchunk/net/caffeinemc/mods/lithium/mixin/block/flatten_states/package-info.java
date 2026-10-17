/**
 * This package includes a patch that stores information about fluid states directly in the FluidState object to improve
 * the performance of accessing whether the FluidState is empty.
 */
@MixinConfigOption(description = "FluidStates store directly whether they are empty")
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.block.flatten_states;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;