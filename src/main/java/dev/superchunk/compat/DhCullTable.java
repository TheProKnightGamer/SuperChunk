package dev.superchunk.compat;

/**
 * Implemented on Distant Horizons' {@code FullDataSourceV2} (mixin.compat.MixinDhCullScope): while
 * the source merges an update, the opacity of each of its ids for the occlusion culler
 * (mixin.compat.MixinDhOcclusionCuller).
 */
public interface DhCullTable {
    /** Whether the source is inside {@code updateFromDataSource} on this thread. */
    boolean superchunk$merging();

    /** Translucent (opacity below 16) per id, built on the culler's first question; null before. */
    boolean[] superchunk$translucent();

    void superchunk$setTranslucent(boolean[] translucent);
}
