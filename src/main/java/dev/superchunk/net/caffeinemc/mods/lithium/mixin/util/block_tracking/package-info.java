@MixinConfigOption(
        description = "Chunk sections count certain blocks inside them and provide a method to quickly check whether a" +
                " chunk contains any of these blocks. Furthermore, chunk sections can notify registered listeners about" +
                " certain blocks being placed or broken.",
        depends = {
                @MixinConfigDependency(dependencyPath = "mixin.util.data_storage"),
                @MixinConfigDependency(dependencyPath = "mixin.util.chunk_status_tracking")
        },
        enabled = false // TODO handle data pack tag changes etc. Just injecting into Bootstrap is not enough.
)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.block_tracking;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;