package dev.superchunk.gpu.dfc;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects every distinct {@link DensityFunction.NoiseHolder} referenced by a
 * density-function AST, assigns each an integer id, and flattens the underlying
 * {@link NormalNoise} state into the combined buffers consumed by
 * {@code dfc_support.cl}'s {@code df_noise_value}.
 *
 * <p>The per-noise layout matches Stage 1's {@code normal_noise} harness exactly:
 * a NormalNoise is two {@link PerlinNoise} samplers ("first" then "second"),
 * each a sum of octaves; the two are concatenated first-then-second in the
 * octave / permutation buffers.
 *
 * <p>Noises are keyed by <b>identity</b>: the same {@code NoiseHolder} instance
 * referenced from multiple nodes reuses one id (and one upload). Distinct
 * holders backed by equal parameters are NOT deduplicated — correctness first;
 * the overworld graph has only a handful of distinct noises anyway.
 */
public final class NoiseRegistry {

    private final Map<DensityFunction.NoiseHolder, Integer> ids = new IdentityHashMap<>();
    private final List<DensityFunction.NoiseHolder> order = new ArrayList<>();

    // Accumulating flat buffers.
    private final IntArrayList desc = new IntArrayList();      // DESC_STRIDE per noise
    private final DoubleArrayList factors = new DoubleArrayList();    // FACTOR_STRIDE per noise
    private final IntArrayList perm = new IntArrayList();
    private final IntArrayList active = new IntArrayList();
    private final DoubleArrayList xo = new DoubleArrayList();
    private final DoubleArrayList yo = new DoubleArrayList();
    private final DoubleArrayList zo = new DoubleArrayList();
    private final DoubleArrayList amp = new DoubleArrayList();

    /**
     * Returns the id for {@code holder}, extracting + appending its state on first
     * encounter. Throws {@link UnsupportedDfNodeException} if the two sub-Perlins
     * have mismatched octave counts (never happens for vanilla noises, but the GPU
     * layout assumes equality).
     */
    public int register(DensityFunction.NoiseHolder holder) {
        Integer existing = ids.get(holder);
        if (existing != null) {
            return existing;
        }
        int id = order.size();
        ids.put(holder, id);
        order.add(holder);
        extract(holder);
        return id;
    }

    private void extract(DensityFunction.NoiseHolder holder) {
        NormalNoise nn = holder.noise();
        if (nn == null) {
            // Absent noise -> getValue returns 0.0; mark invalid, push empty state.
            pushDesc(0, perm.size(), active.size(), 0);
            factors.add(0.0);
            factors.add(0.0);
            factors.add(0.0);
            return;
        }

        PerlinNoise first = nn.first;
        PerlinNoise second = nn.second;
        double valueFactor = nn.valueFactor;

        PerlinState p1 = extractPerlin(first);
        PerlinState p2 = extractPerlin(second);
        if (p1.nOctaves != p2.nOctaves) {
            throw new UnsupportedDfNodeException(
                    "NormalNoise sub-Perlins differ in octave count (" + p1.nOctaves + " vs " + p2.nOctaves + ")");
        }
        int nOct = p1.nOctaves;

        int permBase = perm.size();
        int octaveBase = active.size();
        pushDesc(nOct, permBase, octaveBase, 1);

        // factors are shared by both sub-perlins (same NoiseParameters).
        factors.add(p1.lowestFreqInputFactor);
        factors.add(p1.lowestFreqValueFactor);
        factors.add(valueFactor);

        appendPerlin(p1);
        appendPerlin(p2);
    }

    // ----------------------------------------------------------------------
    // BlendedNoise ("old_blended_noise") table registration.
    // ----------------------------------------------------------------------

    /**
     * Where a registered {@link BlendedNoise}'s three legacy PerlinNoise samplers
     * live in the shared flat buffers: octave slots {@code [octaveBase, octaveBase
     * + 2*nLimit + nMain)} in {@code active/xo/yo/zo/amp} (minLimit, then maxLimit,
     * then main) and permutation ints {@code [permBase, permBase + (2*nLimit +
     * nMain)*256)} in {@code perm}. {@code nLimit}/{@code nMain} are the limit /
     * main octave counts (always 16/8 — enforced below).
     */
    public record BlendedSlot(int permBase, int octaveBase, int nLimit, int nMain) {
    }

    private final Map<BlendedNoise, BlendedSlot> blendedSlots = new IdentityHashMap<>();

    /**
     * Registers a vanilla {@link BlendedNoise}'s three legacy {@link PerlinNoise}
     * samplers (minLimit octaves -15..0, maxLimit -15..0, main -7..0), appending
     * their per-octave state contiguously to the SAME flat buffers used by the
     * NormalNoise entries — {@code df_blended_noise} in {@code dfc_support.cl}
     * indexes them with the returned raw offsets, so no descriptor entry is used
     * and the NormalNoise id space is untouched. Identity-deduplicated, like
     * {@link #register}.
     *
     * <p>Throws {@link UnsupportedDfNodeException} (→ clean per-DF CPU fallback)
     * if the octave counts are not the vanilla 16/16/8: {@code BlendedNoise
     * .compute()} HARDCODES its loop bounds to 8/16, so any other shape (impossible
     * via any vanilla construction path, but cheap to refuse) cannot be reproduced
     * by the count-parameterized kernel loop.
     */
    public BlendedSlot registerBlended(BlendedNoise noise) {
        BlendedSlot existing = blendedSlots.get(noise);
        if (existing != null) {
            return existing;
        }
        // AT-widened field reads (accesstransformer.cfg), same pattern as extractPerlin.
        PerlinState min = extractPerlin(noise.minLimitNoise);
        PerlinState max = extractPerlin(noise.maxLimitNoise);
        PerlinState main = extractPerlin(noise.mainNoise);
        if (min.nOctaves != 16 || max.nOctaves != 16 || main.nOctaves != 8) {
            throw new UnsupportedDfNodeException("BlendedNoise octave counts "
                    + min.nOctaves + "/" + max.nOctaves + "/" + main.nOctaves
                    + " != vanilla 16/16/8 (compute() hardcodes those loop bounds)");
        }
        int permBase = perm.size();
        int octaveBase = active.size();
        appendPerlin(min);
        appendPerlin(max);
        appendPerlin(main);
        BlendedSlot slot = new BlendedSlot(permBase, octaveBase, min.nOctaves, main.nOctaves);
        blendedSlots.put(noise, slot);
        return slot;
    }

    private void pushDesc(int nOctaves, int permBase, int octaveBase, int valid) {
        desc.add(nOctaves);
        desc.add(permBase);
        desc.add(octaveBase);
        desc.add(valid);
        desc.add(0);
        desc.add(0);
        desc.add(0);
        desc.add(0);
    }

    private void appendPerlin(PerlinState s) {
        for (int o = 0; o < s.nOctaves; o++) {
            active.add(s.active[o]);
            xo.add(s.xo[o]);
            yo.add(s.yo[o]);
            zo.add(s.zo[o]);
            amp.add(s.amplitudes[o]);
            int base = o * 256;
            for (int i = 0; i < 256; i++) {
                perm.add(s.perm[base + i]);
            }
        }
    }

    public int[] descArray() {
        return desc.toIntArray();
    }

    public double[] factorArray() {
        return factors.toDoubleArray();
    }

    public int[] permArray() {
        return perm.toIntArray();
    }

    public int[] activeArray() {
        return active.toIntArray();
    }

    public double[] xoArray() {
        return xo.toDoubleArray();
    }

    public double[] yoArray() {
        return yo.toDoubleArray();
    }

    public double[] zoArray() {
        return zo.toDoubleArray();
    }

    public double[] ampArray() {
        return amp.toDoubleArray();
    }

    // ----------------------------------------------------------------------
    // PerlinNoise state extraction (mirrors GpuNoiseParityTest.extractPerlin).
    // Reads AT-widened vanilla fields (the Stage-1 AT block already widens them).
    // ----------------------------------------------------------------------

    private static final class PerlinState {
        int nOctaves;
        int[] perm;          // nOctaves * 256
        int[] active;        // nOctaves
        double[] xo, yo, zo; // nOctaves
        double[] amplitudes; // nOctaves
        double lowestFreqInputFactor;
        double lowestFreqValueFactor;
    }

    private static PerlinState extractPerlin(PerlinNoise noise) {
        ImprovedNoise[] levels = noise.noiseLevels;
        DoubleList amps = noise.amplitudes;
        int n = levels.length;

        PerlinState s = new PerlinState();
        s.nOctaves = n;
        s.perm = new int[n * 256];
        s.active = new int[n];
        s.xo = new double[n];
        s.yo = new double[n];
        s.zo = new double[n];
        s.amplitudes = new double[n];
        s.lowestFreqInputFactor = noise.lowestFreqInputFactor;
        s.lowestFreqValueFactor = noise.lowestFreqValueFactor;

        for (int o = 0; o < n; o++) {
            s.amplitudes[o] = amps.getDouble(o);
            ImprovedNoise lvl = levels[o];
            if (lvl != null) {
                s.active[o] = 1;
                s.xo[o] = lvl.xo;
                s.yo[o] = lvl.yo;
                s.zo[o] = lvl.zo;
                byte[] p = lvl.p;
                int base = o * 256;
                for (int i = 0; i < 256; i++) {
                    s.perm[base + i] = p[i] & 0xFF;
                }
            }
        }
        return s;
    }
}
