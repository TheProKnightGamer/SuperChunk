package com.seibel.distanthorizons.core.logging;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public class DhLogger {
    public void error(String str, Object... param) {
        throw new UnsupportedOperationException();
    }

    public void warn(String str, Object... param) {
        throw new UnsupportedOperationException();
    }
}
