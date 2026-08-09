package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.CLProgramCache;
import dev.superchunk.gpu.OpenCLBackend;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Self-test-only host glue for the FUSED multi-output density-function kernel.
 *
 * <p>Mirrors {@link GpuDensityFunction#compile} / {@code fillWholeGrid} but for
 * {@link OpenCLAstEmitter#emitMulti}'s {@code df_batch_lattice_multi}: it compiles
 * a list of AST roots into ONE program (shared {@link NoiseRegistry} -> cross-DF
 * CSE), uploads the shared noise state, and dispatches the multi-output lattice
 * kernel ONCE into an {@code M*n} output buffer where root {@code k}'s whole-grid
 * result lands at {@code out[k*n + gid]}.
 *
 * <p>This class is used ONLY by {@link GpuVanillaParityTest}'s fused-emit parity
 * check (boot-time, single-threaded). It deliberately uses the boot-time shared
 * command queue via {@link CLProgram#enqueue1D(long, long)} / {@code readBuffer},
 * exactly like the boot self-test, and is never wired into the live worldgen path.
 *
 * <p>Returns {@code null} on any failure (never throws), like
 * {@link GpuDensityFunction#compile}.
 */
final class GpuFusedProgram implements AutoCloseable {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final String NOISE_CL = "/superchunk/kernels/noise.cl";
    private static final String SUPPORT_CL = "/superchunk/kernels/dfc_support.cl";
    private static final String KERNEL_NAME = "df_batch_lattice_multi";

    private final CLProgram program;
    private final boolean fp32;
    private final int realBytes;
    private final int rootCount;
    private final long kernel;

    // Immutable noise-state device buffers (uploaded once, READ-ONLY).
    private final long noiseDescMem;
    private final long noiseFactorsMem;
    private final long nPermMem;
    private final long nActiveMem;
    private final long nXoMem;
    private final long nYoMem;
    private final long nZoMem;
    private final long nAmpMem;

    private GpuFusedProgram(CLProgram program, boolean fp32, int rootCount, long kernel,
                            long noiseDescMem, long noiseFactorsMem, long nPermMem, long nActiveMem,
                            long nXoMem, long nYoMem, long nZoMem, long nAmpMem) {
        this.program = program;
        this.fp32 = fp32;
        this.realBytes = fp32 ? Float.BYTES : Double.BYTES;
        this.rootCount = rootCount;
        this.kernel = kernel;
        this.noiseDescMem = noiseDescMem;
        this.noiseFactorsMem = noiseFactorsMem;
        this.nPermMem = nPermMem;
        this.nActiveMem = nActiveMem;
        this.nXoMem = nXoMem;
        this.nYoMem = nYoMem;
        this.nZoMem = nZoMem;
        this.nAmpMem = nAmpMem;
    }

    /** Number of fused roots M (each occupies an n-length slice of the output). */
    int rootCount() {
        return rootCount;
    }

    /**
     * Compiles {@code roots} into one fused {@code df_batch_lattice_multi} program.
     * Returns {@code null} on any failure (logged; never throws).
     */
    static GpuFusedProgram compile(List<AstNode> roots) {
        if (!OpenCLBackend.isAvailable()) {
            return null;
        }
        boolean fp32 = OpenCLBackend.isFp32();

        NoiseRegistry registry = new NoiseRegistry();
        OpenCLAstEmitter.MultiResult multi;
        try {
            multi = OpenCLAstEmitter.emitMulti(roots, registry);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fused-emit] emitMulti failed — cannot run fused parity.", t);
            return null;
        }

        String noiseSrc = CLProgram.loadKernelSource(NOISE_CL);
        String supportSrc = CLProgram.loadKernelSource(SUPPORT_CL);
        if (noiseSrc == null || supportSrc == null) {
            LOGGER.warn("[SuperChunk-GPU] [fused-emit] could not load kernel resources.");
            return null;
        }
        String fullSource = noiseSrc + "\n" + supportSrc + "\n" + multi.source();
        if (Boolean.getBoolean("superchunk.gpu.dumpKernel")) {
            LOGGER.info("[SuperChunk-GPU] === generated FUSED DF kernel (M={}) ===\n{}", multi.rootCount(), multi.source());
        }

        CLProgram program = CLProgramCache.getOrBuild(fullSource, fp32 ? "-DUSE_FP32" : "");
        if (program == null) {
            LOGGER.warn("[SuperChunk-GPU] [fused-emit] fused kernel build failed.");
            return null;
        }

        long descMem = NULL, factorsMem = NULL, permMem = NULL, activeMem = NULL,
                xoMem = NULL, yoMem = NULL, zoMem = NULL, ampMem = NULL, kernel = NULL;
        try {
            descMem = program.uploadInts(registry.descArray());
            factorsMem = program.uploadReals(fp32, registry.factorArray());
            permMem = program.uploadPerm(registry.permArray());
            activeMem = program.uploadInts(registry.activeArray());
            xoMem = program.uploadReals(fp32, registry.xoArray());
            yoMem = program.uploadReals(fp32, registry.yoArray());
            zoMem = program.uploadReals(fp32, registry.zoArray());
            ampMem = program.uploadReals(fp32, registry.ampArray());
            kernel = program.kernel(KERNEL_NAME);
            return new GpuFusedProgram(program, fp32, multi.rootCount(), kernel,
                    descMem, factorsMem, permMem, activeMem, xoMem, yoMem, zoMem, ampMem);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fused-emit] noise upload / kernel create failed.", t);
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseMem(descMem);
            CLProgram.releaseMem(factorsMem);
            CLProgram.releaseMem(permMem);
            CLProgram.releaseMem(activeMem);
            CLProgram.releaseMem(xoMem);
            CLProgram.releaseMem(yoMem);
            CLProgram.releaseMem(zoMem);
            CLProgram.releaseMem(ampMem);
            program.close();
            return null;
        }
    }

    /**
     * Dispatches {@code df_batch_lattice_multi} ONCE over the lattice
     * {@code (ox,oy,oz)+stride*(dim)} and reads back the {@code M*n} grids into
     * {@code out} (root {@code k} -> {@code out[k*n + i]}). {@code out.length} must
     * equal {@code M * dimX*dimY*dimZ}. Returns {@code true} on success.
     *
     * <p>Same gid->(xx,yy,zz) decode and same 9 lattice scalars as
     * {@link GpuDensityFunction}'s lattice path, so each n-length slice is
     * bit-identical to the corresponding single-root {@code df_batch_lattice}.
     */
    boolean fill(double[] out, int ox, int oy, int oz, int sx, int sy, int sz, int dimX, int dimY, int dimZ) {
        int n = dimX * dimY * dimZ;
        long total = (long) rootCount * n;
        if (out.length != total) {
            throw new IllegalArgumentException("out.length=" + out.length + " expected M*n=" + total);
        }
        if (n <= 0) {
            return true;
        }
        long outMem = NULL;
        try {
            outMem = program.createOutputBuffer(total * realBytes);
            int a = 0;
            program.setArgPointer(kernel, a++, noiseDescMem);
            program.setArgPointer(kernel, a++, noiseFactorsMem);
            program.setArgPointer(kernel, a++, nPermMem);
            program.setArgPointer(kernel, a++, nActiveMem);
            program.setArgPointer(kernel, a++, nXoMem);
            program.setArgPointer(kernel, a++, nYoMem);
            program.setArgPointer(kernel, a++, nZoMem);
            program.setArgPointer(kernel, a++, nAmpMem);
            program.setArgInt(kernel, a++, ox);
            program.setArgInt(kernel, a++, oy);
            program.setArgInt(kernel, a++, oz);
            program.setArgInt(kernel, a++, sx);   // strx
            program.setArgInt(kernel, a++, sy);   // stry
            program.setArgInt(kernel, a++, sz);   // strz
            program.setArgInt(kernel, a++, dimX); // dx
            program.setArgInt(kernel, a++, dimZ); // dz
            program.setArgPointer(kernel, a++, outMem);
            program.setArgInt(kernel, a++, n);

            // Global work size is the per-grid n (each work-item writes all M roots),
            // rounded up to a multiple of 64 (extra threads early-return on gid>=n).
            program.enqueue1D(kernel, CLProgram.roundUpGlobal(n));

            int totalInt = (int) total;
            if (fp32) {
                FloatBuffer fb = MemoryUtil.memAllocFloat(totalInt);
                try {
                    program.readBuffer(outMem, fb);
                    for (int i = 0; i < totalInt; i++) out[i] = fb.get(i);
                } finally {
                    MemoryUtil.memFree(fb);
                }
            } else {
                DoubleBuffer db = MemoryUtil.memAllocDouble(totalInt);
                try {
                    program.readBuffer(outMem, db);
                    for (int i = 0; i < totalInt; i++) out[i] = db.get(i);
                } finally {
                    MemoryUtil.memFree(db);
                }
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fused-emit] fused fill failed.", t);
            return false;
        } finally {
            CLProgram.releaseMem(outMem);
        }
    }

    @Override
    public void close() {
        CLProgram.releaseKernel(kernel);
        CLProgram.releaseMem(noiseDescMem);
        CLProgram.releaseMem(noiseFactorsMem);
        CLProgram.releaseMem(nPermMem);
        CLProgram.releaseMem(nActiveMem);
        CLProgram.releaseMem(nXoMem);
        CLProgram.releaseMem(nYoMem);
        CLProgram.releaseMem(nZoMem);
        CLProgram.releaseMem(nAmpMem);
        program.close();
    }
}
