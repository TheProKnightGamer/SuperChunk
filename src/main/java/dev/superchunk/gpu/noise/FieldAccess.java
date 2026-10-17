package dev.superchunk.gpu.noise;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/**
 * Centralized reads of vanilla noise internal state for the parity harness.
 *
 * <p>Every field touched here is widened to {@code public} by additive entries in
 * {@code META-INF/accesstransformer.cfg} (the "SuperChunk GPU backend (Stage 1)"
 * block). Keeping the accesses in one place documents exactly which AT lines this
 * Stage depends on, and makes it obvious that nothing here mutates vanilla state.
 *
 * <p>If a future Minecraft remap renames a field, only this class needs updating.
 */
final class FieldAccess {
    private FieldAccess() {
    }

    // --- ImprovedNoise ---
    // AT: public net.minecraft.world.level.levelgen.synth.ImprovedNoise p
    // (xo/yo/zo are already public in vanilla.)
    static byte[] improvedPerm(ImprovedNoise noise) {
        return noise.p;
    }

    // --- PerlinNoise ---
    // AT: public ... PerlinNoise noiseLevels / amplitudes /
    //     lowestFreqInputFactor / lowestFreqValueFactor
    static ImprovedNoise[] perlinNoiseLevels(PerlinNoise noise) {
        return noise.noiseLevels;
    }

    static DoubleList perlinAmplitudes(PerlinNoise noise) {
        return noise.amplitudes;
    }

    static double perlinLowestFreqInputFactor(PerlinNoise noise) {
        return noise.lowestFreqInputFactor;
    }

    static double perlinLowestFreqValueFactor(PerlinNoise noise) {
        return noise.lowestFreqValueFactor;
    }

    // --- NormalNoise ---
    // AT: public ... NormalNoise first / second / valueFactor
    static PerlinNoise normalFirst(NormalNoise noise) {
        return noise.first;
    }

    static PerlinNoise normalSecond(NormalNoise noise) {
        return noise.second;
    }

    static double normalValueFactor(NormalNoise noise) {
        return noise.valueFactor;
    }
}
