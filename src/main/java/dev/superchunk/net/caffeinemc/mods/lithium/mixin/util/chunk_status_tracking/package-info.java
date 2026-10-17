@MixinConfigOption(
        description = "Allows reacting to changes of the load status of chunks.",
        depends = @MixinConfigDependency(dependencyPath = "mixin.util.accessors")

)
package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.chunk_status_tracking;

import dev.superchunk.net.caffeinemc.gradle.MixinConfigDependency;
import dev.superchunk.net.caffeinemc.gradle.MixinConfigOption;
