package com.seibel.distanthorizons.api.interfaces.block;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public interface IDhApiBlockStateWrapper {
    boolean isAir();

    boolean isSolid();

    boolean isLiquid();

    int getOpacity();
}
