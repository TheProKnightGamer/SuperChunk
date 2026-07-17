@MixinConfigOption(
        description = "BlockEntity Inventories update their listeners when a comparator is placed near them",
        depends = {
                @MixinConfigDependency(dependencyPath = "mixin.util.block_entity_retrieval")
        }
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.inventory_comparator_tracking;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;