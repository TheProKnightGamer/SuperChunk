package com.seibel.distanthorizons.core.config;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public class Config {
    public static class Common {
        public static class LodBuilding {
            public static ConfigEntry<EDhApiWorldCompressionMode> worldCompression;
        }
    }
}
