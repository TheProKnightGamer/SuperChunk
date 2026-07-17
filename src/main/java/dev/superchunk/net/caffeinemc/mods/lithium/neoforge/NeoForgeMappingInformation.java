package dev.superchunk.net.caffeinemc.mods.lithium.neoforge;

import dev.superchunk.net.caffeinemc.mods.lithium.common.services.PlatformMappingInformation;

public class NeoForgeMappingInformation  implements PlatformMappingInformation {

    @Override
    public String mapMethodName(String fromMappings, String clazz, String method, String argsDescriptor, String mojmap) {
        return mojmap;
    }
}
