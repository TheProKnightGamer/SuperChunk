package dev.superchunk.gpu;

/**
 * An enumerated OpenCL device plus the capabilities SuperChunk cares about.
 *
 * @param platform        native {@code cl_platform_id} pointer
 * @param device          native {@code cl_device_id} pointer
 * @param name            {@code CL_DEVICE_NAME}
 * @param vendor          {@code CL_DEVICE_VENDOR}
 * @param openclVersion   {@code CL_DEVICE_VERSION} (e.g. "OpenCL 3.0 NEO")
 * @param maxComputeUnits {@code CL_DEVICE_MAX_COMPUTE_UNITS}
 * @param globalMemBytes  {@code CL_DEVICE_GLOBAL_MEM_SIZE}
 * @param fp64            whether the device supports 64-bit doubles
 *                        (CL_DEVICE_DOUBLE_FP_CONFIG != 0 OR the
 *                        {@code cl_khr_fp64} extension is present)
 * @param crDivideFp32    whether {@code CL_DEVICE_SINGLE_FP_CONFIG} advertises
 *                        {@code CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT} (core OpenCL 1.2,
 *                        bit {@code 1<<7}) — i.e. the device supports the
 *                        {@code -cl-fp32-correctly-rounded-divide-sqrt} build flag that
 *                        forces IEEE-correctly-rounded fp32 divide/sqrt (spline-drift lever)
 */
public record OpenCLDevice(
        long platform,
        long device,
        String name,
        String vendor,
        String openclVersion,
        int maxComputeUnits,
        long globalMemBytes,
        boolean fp64,
        boolean crDivideFp32
) {
    /** Human-readable one-line caps summary for logging. */
    public String describe() {
        return String.format(
                "%s [%s] | %s | CU=%d | mem=%.1f GiB | fp64=%s | crDivideFp32=%s",
                name, vendor, openclVersion, maxComputeUnits,
                globalMemBytes / (1024.0 * 1024.0 * 1024.0),
                fp64 ? "yes" : "no",
                crDivideFp32 ? "yes" : "no");
    }
}
