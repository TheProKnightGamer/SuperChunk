package dev.superchunk.worldgen;

/**
 * Load-once gate for the {@link PlainWorldgenRandom} substitution performed by
 * {@code dev.superchunk.mixin.MixinPlainWorldgenRandomSites}.
 *
 * <p>Kept in a plain (non-mixin) class rather than as a {@code @Unique private static final} field on
 * the mixin: that mixin targets three classes at once ({@code NoiseBasedChunkGenerator},
 * {@code ChunkGenerator}, {@code RandomSpreadStructurePlacement}), and a {@code @Unique} static-final
 * field with an initializer must be injected into each target's {@code <clinit>}. On a target that
 * has no static initializer of its own (e.g. {@code RandomSpreadStructurePlacement}) and is ALSO
 * transformed by another mod's mixin (observed with Radium, a Lithium fork that mixes into the same
 * class), the field injection can be dropped while the reference remains — a boot-fatal
 * {@code NoSuchFieldError}. Reading the flag from this ordinary class avoids injecting any field into
 * the targets, so the wrap applies cleanly regardless of who else transforms them. Behaviour is
 * identical: same system property, same default.
 */
public final class PlainWorldgenRandomGate {

    /** {@code -Dsuperchunk.worldgen.plainWorldgenRandom} (default true). */
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.plainWorldgenRandom", "true"));

    private PlainWorldgenRandomGate() {
    }
}
