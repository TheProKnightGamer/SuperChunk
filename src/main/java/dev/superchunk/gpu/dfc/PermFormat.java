package dev.superchunk.gpu.dfc;

import java.util.Locale;

/**
 * How the Perlin permutation tables are encoded in device memory.
 *
 * <p>{@code perm} is the hot gather of the whole GPU worldgen path: vanilla's
 * {@code ImprovedNoise.sampleAndLerp} reads 14 entries per octave, every octave of every
 * NormalNoise of every density function, at effectively random indices. A warp's 32 lanes
 * therefore scatter across one octave's table, so the table's <b>byte size</b> — not its
 * element count — sets how many 32-byte sectors each load touches.
 *
 * <p>Vanilla stores the table as {@code byte[256]} and reads {@code p[i & 0xFF] & 0xFF};
 * every encoding here carries exactly those 0..255 values, so all of them are
 * <b>bit-identical</b>. Only the memory layout differs:
 *
 * <ul>
 *   <li>{@link #INT} — one {@code int} per entry, 1024 B/octave, 14 gathers per sample.
 *       The original layout (a straight widening of vanilla's bytes).</li>
 *   <li>{@link #U8} — one {@code uchar} per entry, 256 B/octave, 14 gathers per sample.
 *       Same instruction count, a quarter of the sector traffic.</li>
 *   <li>{@link #PAIR} — {@code uchar2{p(i), p(i+1)}} per entry, 512 B/octave, <b>7</b>
 *       gathers per sample. All 14 of sampleAndLerp's lookups are (n, n+1) pairs — see
 *       {@code perm_pair} in noise.cl — so one 2-byte load serves two of them.</li>
 * </ul>
 *
 * <p><b>MEASURED — and the result is the interesting part</b> (2026-08-06,
 * {@code tools/noisebench.c}, RTX 3070, interleaved, 627k lattice points at 256 octave-evals
 * each): <b>U8 −0.0%, PAIR −2.1%</b>. Quartering the sector traffic and halving the gather
 * count are both worth essentially nothing — which is the direct evidence that these kernels
 * are NOT gather-bound, contrary to what rounds 4-8 assumed. Building the same code with
 * {@code real = float} is worth <b>16.8×</b>: the wall is fp64 op count on a card whose fp64
 * rate is 1/64 of fp32. That is why round 10's real win came from removing fp64 operations in
 * noise.cl (the {@code perlin_wrap} fast path, {@code mc_floor_frac}, wrap hoisting,
 * {@code improved_noise_octave}) rather than from touching this table.
 *
 * <p>Kept anyway: all three encodings are bit-identical, the default path is unchanged, and on a
 * device with a healthier fp64 ratio — where the gather could actually become the binder — PAIR
 * is one flag away.
 *
 * <p>Selected with {@code -Dsuperchunk.gpu.permFormat=int|u8|pair}. The build flag is
 * folded into every program's option string by {@code CLProgram.effectiveOptions}, so the
 * in-memory and on-disk program caches key on it and can never serve a binary built for a
 * different encoding.
 */
public enum PermFormat {
    /** {@code __global const int*} — 4 B/entry. */
    INT("", 4),
    /** {@code __global const uchar*} — 1 B/entry. */
    U8("-DSC_PERM_U8", 1),
    /** {@code __global const uchar2*} — 2 B/entry, {p(i), p(i+1)}. */
    PAIR("-DSC_PERM_PAIR", 2);

    /** Entries per octave; fixed by vanilla's {@code ImprovedNoise.p[256]}. */
    public static final int ENTRIES_PER_OCTAVE = 256;

    private final String buildFlag;
    private final int bytesPerEntry;

    PermFormat(String buildFlag, int bytesPerEntry) {
        this.buildFlag = buildFlag;
        this.bytesPerEntry = bytesPerEntry;
    }

    /** The {@code -D} that makes noise.cl's {@code perm_ptr} match this encoding ("" for INT). */
    public String buildFlag() {
        return buildFlag;
    }

    public int bytesPerEntry() {
        return bytesPerEntry;
    }

    private static final PermFormat ACTIVE = resolve();

    private static PermFormat resolve() {
        String v = System.getProperty("superchunk.gpu.permFormat", "int");
        try {
            return valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INT;   // unknown value -> the original layout, never a crash
        }
    }

    /** The encoding this JVM uploads and builds with. */
    public static PermFormat active() {
        return ACTIVE;
    }

    /**
     * Encodes {@code perm} (0..255 values, {@link #ENTRIES_PER_OCTAVE} per octave) into the
     * device byte image for this format, little-endian for {@link #INT} to match the host's
     * {@code IntBuffer} upload.
     *
     * <p>{@link #PAIR}'s second component wraps <i>within the octave</i>: entry 255 of an
     * octave pairs with entry 0 of the SAME octave, because vanilla's index arithmetic is
     * {@code (i + 1) & 0xFF} on a per-octave table.
     *
     * @throws IllegalArgumentException if {@code perm.length} is not a whole number of octaves
     */
    public byte[] encode(int[] perm) {
        if (perm.length % ENTRIES_PER_OCTAVE != 0) {
            throw new IllegalArgumentException("perm length " + perm.length
                    + " is not a multiple of " + ENTRIES_PER_OCTAVE);
        }
        byte[] out = new byte[perm.length * bytesPerEntry];
        switch (this) {
            case U8 -> {
                for (int i = 0; i < perm.length; i++) {
                    out[i] = (byte) perm[i];
                }
            }
            case PAIR -> {
                for (int base = 0; base < perm.length; base += ENTRIES_PER_OCTAVE) {
                    for (int i = 0; i < ENTRIES_PER_OCTAVE; i++) {
                        int o = (base + i) * 2;
                        out[o] = (byte) perm[base + i];
                        out[o + 1] = (byte) perm[base + ((i + 1) & (ENTRIES_PER_OCTAVE - 1))];
                    }
                }
            }
            default -> {
                for (int i = 0; i < perm.length; i++) {
                    int v = perm[i], o = i * 4;
                    out[o] = (byte) v;
                    out[o + 1] = (byte) (v >>> 8);
                    out[o + 2] = (byte) (v >>> 16);
                    out[o + 3] = (byte) (v >>> 24);
                }
            }
        }
        return out;
    }
}
