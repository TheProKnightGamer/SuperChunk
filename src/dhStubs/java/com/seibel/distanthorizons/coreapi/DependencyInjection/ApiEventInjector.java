package com.seibel.distanthorizons.coreapi.DependencyInjection;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public class ApiEventInjector {
    public static final ApiEventInjector INSTANCE = new ApiEventInjector();

    private ApiEventInjector() {
    }

    public <T, U> boolean fireAllEvents(Class<U> abstractEventClass, T eventInput) {
        throw new UnsupportedOperationException();
    }
}
