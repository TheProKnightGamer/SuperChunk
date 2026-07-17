package dev.superchunk.gpu;

import java.util.Locale;

/**
 * Desired floating-point precision for the GPU worldgen offload, selected via the
 * {@code desiredFp} config key (the three-way successor to the old boolean
 * {@code requireFp64}).
 *
 * <ul>
 *   <li>{@link #AUTO} — use double ({@code fp64}) when the selected device advertises
 *       {@code cl_khr_fp64}, otherwise fall back to the {@code fp32} (float) kernel
 *       path. The GPU always engages. NOTE: the fp32 path is NOT vanilla-accurate
 *       (~1e-5 structural error), so on a non-fp64 device this trades accuracy for
 *       coverage.</li>
 *   <li>{@link #FP64} — require double precision (the production-safe, vanilla-accurate
 *       path). The GPU only engages on an fp64-capable device; on a device without
 *       {@code cl_khr_fp64} the backend refuses and worldgen runs the bit-exact CPU
 *       path. This is the default and matches the old {@code requireFp64=true}.</li>
 *   <li>{@link #FP32} — force the {@code fp32} (float) kernel path even on an
 *       fp64-capable device (e.g. to benchmark fp32 throughput on a discrete GPU, or
 *       to match vanilla's float-precision {@code CubicSpline} math). NOT
 *       vanilla-accurate for the double-precision noise path — integration / perf
 *       testing only.</li>
 * </ul>
 */
public enum FpMode {
    AUTO,
    FP64,
    FP32;

    /**
     * Resolves whether the {@code fp32} (float) kernel path should be used, given
     * whether the chosen device advertises {@code cl_khr_fp64}. This is the single
     * precision decision — {@link OpenCLBackend} resolves it once at init and exposes
     * it via {@link OpenCLBackend#isFp32()} so the kernel compile and every parity
     * test agree.
     */
    public boolean useFp32(boolean deviceHasFp64) {
        return switch (this) {
            case FP32 -> true;
            case FP64 -> false; // engagement is gated separately; reaching here implies the device has fp64
            case AUTO -> !deviceHasFp64;
        };
    }

    /**
     * Whether device auto-selection should prefer an fp64-capable device. True for
     * {@link #AUTO} (fp64 is more accurate when available) and {@link #FP64} (required);
     * false for {@link #FP32} (precision is forced regardless of the device).
     */
    public boolean prefersFp64Device() {
        return this != FP32;
    }

    /**
     * Parses a {@code desiredFp} config value (case-insensitive). Accepts
     * {@code auto} / {@code fp64} / {@code fp32}, and — for backward compatibility
     * with the old boolean {@code requireFp64} key — {@code true} -> {@link #FP64}
     * and {@code false} -> {@link #AUTO} (the old "allow non-fp64 fp32 fallback"
     * semantics). Returns {@code def} on anything unrecognized or {@code null}.
     */
    public static FpMode parse(String s, FpMode def) {
        if (s == null) return def;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "auto" -> AUTO;
            case "fp64", "true" -> FP64;   // "true" = legacy requireFp64=true
            case "fp32" -> FP32;
            case "false" -> AUTO;          // "false" = legacy requireFp64=false (use fp64 if present, else fp32)
            default -> def;
        };
    }

    /** The canonical config token for this mode ({@code auto} / {@code fp64} / {@code fp32}). */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }
}
