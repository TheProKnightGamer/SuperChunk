/**
 * This package includes a patch that optimizes the redstone wire power level calculation by avoiding duplicate accesses
 * of the blocks around the redstone wire.
 */
@MixinConfigOption(description = "Redstone wire power calculations avoid duplicate block accesses")
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.block.redstone_wire;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;
