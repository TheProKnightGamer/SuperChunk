package dev.superchunk.gpu;

import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Stage-0 end-to-end OpenCL smoke test: a float vector-add on the GPU.
 *
 * <p>Proves the full compute path (program build → kernel → buffer up/down →
 * NDRange dispatch → readback → verify) actually works on this machine's
 * OpenCL ICD. FP32 works even on devices without {@code cl_khr_fp64}, so a PASS
 * here is independent of double-precision support.
 */
public final class GpuSelfTest {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final int N = 1024;

    private static final String VADD_SOURCE =
            "__kernel void vadd(__global const float* a,"
                    + "__global const float* b,"
                    + "__global float* c){"
                    + "int i=get_global_id(0);"
                    + "c[i]=a[i]+b[i];"
                    + "}";

    private GpuSelfTest() {
    }

    /**
     * Runs the vector-add self-test. Never throws; logs PASS/FAIL with the
     * chosen device name/vendor/fp64. Returns true on PASS.
     */
    public static boolean run() {
        if (!OpenCLBackend.isAvailable()) {
            LOGGER.warn("[SuperChunk-GPU] Self-test skipped — backend unavailable.");
            return false;
        }

        OpenCLDevice dev = OpenCLBackend.device();
        long aMem = NULL, bMem = NULL, cMem = NULL, kernel = NULL;
        CLProgram program = null;
        try (MemoryStack stack = stackPush()) {
            program = CLProgram.build(VADD_SOURCE);
            if (program == null) {
                logFail(dev, "program build returned null");
                return false;
            }

            // Host inputs.
            FloatBuffer a = stack.mallocFloat(N);
            FloatBuffer b = stack.mallocFloat(N);
            FloatBuffer c = stack.mallocFloat(N);
            for (int i = 0; i < N; i++) {
                a.put(i, i * 1.0f);
                b.put(i, i * 2.0f + 1.0f);
            }

            aMem = program.createInputBuffer(a);
            bMem = program.createInputBuffer(b);
            cMem = program.createOutputBuffer((long) N * Float.BYTES);

            kernel = program.kernel("vadd");
            program.setArgPointer(kernel, 0, aMem);
            program.setArgPointer(kernel, 1, bMem);
            program.setArgPointer(kernel, 2, cMem);

            program.enqueue1D(kernel, N);
            program.readBuffer(cMem, c);

            // Verify c[i] == a[i] + b[i].
            int mismatches = 0;
            for (int i = 0; i < N; i++) {
                float expected = a.get(i) + b.get(i);
                if (Float.compare(c.get(i), expected) != 0) {
                    if (mismatches < 5) {
                        LOGGER.warn("[SuperChunk-GPU]   mismatch at {}: got {} expected {}",
                                i, c.get(i), expected);
                    }
                    mismatches++;
                }
            }

            if (mismatches == 0) {
                LOGGER.info("[SuperChunk-GPU] Self-test PASS — vadd of {} floats verified on: {} [{}] fp64={}",
                        N, dev.name(), dev.vendor(), dev.fp64() ? "yes" : "no");
                return true;
            } else {
                logFail(dev, mismatches + " / " + N + " elements wrong");
                return false;
            }
        } catch (Throwable t) {
            LOGGER.error("[SuperChunk-GPU] Self-test FAIL — exception on: {} [{}] fp64={}",
                    dev.name(), dev.vendor(), dev.fp64() ? "yes" : "no", t);
            return false;
        } finally {
            CLProgram.releaseMem(aMem);
            CLProgram.releaseMem(bMem);
            CLProgram.releaseMem(cMem);
            CLProgram.releaseKernel(kernel);
            if (program != null) {
                program.close();
            }
        }
    }

    private static void logFail(OpenCLDevice dev, String why) {
        LOGGER.error("[SuperChunk-GPU] Self-test FAIL ({}) on: {} [{}] fp64={}",
                why, dev.name(), dev.vendor(), dev.fp64() ? "yes" : "no");
    }
}
