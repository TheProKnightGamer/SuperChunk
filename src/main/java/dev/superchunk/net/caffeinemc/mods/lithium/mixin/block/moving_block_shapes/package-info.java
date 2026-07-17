/**
 * This package includes a patch that uses memoization to store offset collision shapes that are otherwise repeatedly
 * created when accessing the collision shape of moving blocks and moving pistons.
 */
@MixinConfigOption(description = "Moving blocks and retracting pistons avoid calculating their VoxelShapes by reusing previously created VoxelShapes.")
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.block.moving_block_shapes;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;
