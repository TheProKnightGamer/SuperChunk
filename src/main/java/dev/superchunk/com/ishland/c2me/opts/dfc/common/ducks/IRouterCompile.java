package dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks;

/**
 * On {@code RandomState}: compiles its router and climate sampler, replacing the vanilla ones.
 *
 * <p>{@code MixinNoiseConfig} does this when the constructor returns. With Bye?Pregen! installed,
 * {@code MixinNoiseConfigLate} does it instead, after Bye Pregen's own constructor hooks.
 */
public interface IRouterCompile {

    /** Runs once per {@code RandomState}, at the end of its constructor. */
    void superchunk$compileRouter();
}
