@MixinConfigOption(
        description = "BlockEntity ticking caches whether the BlockEntity can exist in the BlockState at the same location." +
                " This deviates from vanilla in the case of placing a hopper in a powered location, immediately updating the" +
                " cached BlockState (which is incorrect in vanilla). This most likely does not affect your gameplay, as this" +
                " deviation only affects hoppers, and in vanilla, hoppers never use the cached state information anyway.",
        depends = @MixinConfigDependency(dependencyPath = "mixin.world.block_entity_ticking")
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.minimal_nonvanilla.world.block_entity_ticking.support_cache;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;