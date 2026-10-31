package com.bawnorton.mixinsquared.api;

import java.util.List;

/** Test stand-in for MixinSquared's canceller interface (same name and method). */
public interface MixinCanceller {
    boolean shouldCancel(List<String> targetClassNames, String mixinClassName);
}
