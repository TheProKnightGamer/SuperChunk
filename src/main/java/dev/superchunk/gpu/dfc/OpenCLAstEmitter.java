package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.McToAst;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.AddNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MaxNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MaxShortNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MinNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MinShortNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.binary.MulNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.CacheLikeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.ConstantNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.DelegateNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.RangeChoiceNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.RootNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.misc.YClampedGradientNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.BlendedNoiseNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTNoiseNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTShiftANode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTShiftBNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTShiftNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.DFTWeirdScaledSamplerNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.noise.ShiftedNoiseNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.spline.SplineAstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.SubCompiledDensityFunction;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.AbsNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.CubeNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.NegMulNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.SquareNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.unary.SqueezeNode;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a C2ME density-function {@link AstNode} tree and emits OpenCL C that
 * computes the density at one {@code (x,y,z)}. Mirrors {@code BytecodeGen} /
 * each node's {@code evalSingle} op-for-op, so at fp64 the result is bit-for-bit
 * identical and at fp32 structurally identical (Stage-1 discipline).
 *
 * <p>Output structure (a single String):
 * <pre>
 *   real n0(int x,int y,int z, DF_NOISE_ARGS) { ... return ...; }
 *   real n1(...) { ... }
 *   ...
 *   __kernel void df_batch(... noise buffers ..., sx, sy, sz, out, n) { ... }
 * </pre>
 * which the host prepends with {@code noise.cl + dfc_support.cl}.
 *
 * <p>One emit-handler per supported AstNode subtype. Unsupported subtypes throw
 * {@link UnsupportedDfNodeException} so that density function falls back to CPU.
 *
 * <p>Supported: Constant, Add, Mul, Min/MinShort, Max/MaxShort, Abs, Square,
 * Cube, NegMul (half/quarter_negative), Squeeze, YClampedGradient, RangeChoice,
 * DFTNoise, ShiftedNoise, DFTShift/A/B, DFTWeirdScaledSampler, Spline,
 * BlendedNoise (vanilla "old_blended_noise" via {@code df_blended_noise}), Root.
 * Unsupported: DelegateNode, CacheLikeNode, and any unrecognised subtype.
 */
public final class OpenCLAstEmitter {

    /** The OpenCL parameter list bundling all noise-state buffers, by name. */
    public static final String NOISE_PARAMS =
            "__global const int* noiseDesc, __global const real* noiseFactors, "
                    // perm_ptr: the permutation-table encoding (int / uchar / uchar2) is
                    // chosen by PermFormat and typedef'd in noise.cl, which is always
                    // prepended ahead of this signature.
                    + "perm_ptr nPerm, __global const int* nActive, "
                    + "__global const real* nXo, __global const real* nYo, "
                    + "__global const real* nZo, __global const real* nAmp";
    /** The matching argument list to forward those buffers in a call. */
    public static final String NOISE_ARGS =
            "noiseDesc, noiseFactors, nPerm, nActive, nXo, nYo, nZo, nAmp";

    private final NoiseRegistry noiseRegistry;
    /**
     * Per-emit symbol prefix. Empty ("") for a standalone single-DF / fused / cross-chunk
     * program (output is byte-identical to the un-tagged emitter). For the MERGED-program
     * path ({@code GpuDensityFunction} kernel merge), each DF gets a unique tag (e.g.
     * {@code "d3_"}) so its generated function/constant/kernel names cannot collide with
     * the other DFs sharing the one program: {@code n<i>} -> {@code <tag>n<i>},
     * {@code fa<i>} -> {@code <tag>fa<i>}, {@code df_batch} -> {@code <tag>df_batch}, etc.
     */
    private final String tag;
    /**
     * Cold-compile lever: when true, the per-function 8-pointer noise-state boilerplate
     * ({@link #NOISE_PARAMS} / {@link #NOISE_ARGS}) threaded through EVERY generated
     * node/helper/spline function is collapsed into a single by-value {@code NoiseState _ns}
     * struct, shrinking the generated program TEXT (the proven driver of the super-linear
     * {@code clBuildProgram} cost). Gated by {@code -Dsuperchunk.gpu.bundleNoiseState}
     * (default FALSE). When false, {@link #treeParams}/{@link #treeArgs} are exactly
     * {@link #NOISE_PARAMS}/{@link #NOISE_ARGS} so the emitted program is byte-identical to
     * the un-bundled default. Captured once per emitter so every emit path is consistent.
     */
    private final boolean bundleNoise;
    /**
     * The noise-state portion of a generated tree function's SIGNATURE. Un-bundled:
     * {@link #NOISE_PARAMS} (the 8 flat pointers). Bundled: {@code "NoiseState _ns"}.
     * (Kernel entrypoints always use the flat {@link #NOISE_PARAMS} — OpenCL kernel-arg
     * structs cannot hold pointers — and pack {@code _ns} once at kernel entry.)
     */
    private final String treeParams;
    /**
     * The noise-state portion of a CALL to a generated tree function or {@code df_noise_value}.
     * Un-bundled: {@link #NOISE_ARGS} (the 8 pointer names). Bundled: {@code "_ns"}.
     */
    private final String treeArgs;
    /**
     * The noise-sampling entry point name. Un-bundled: {@code "df_noise_value"} (the flat
     * 8-pointer function in dfc_support.cl). Bundled: {@code "df_noise_value_ns"} (the emitted
     * wrapper that unpacks {@code _ns} and forwards to the UNTOUCHED {@code df_noise_value}).
     */
    private final String noiseValueFn;
    /**
     * The BlendedNoise sampling entry point name, threaded per-mode exactly like
     * {@link #noiseValueFn}: flat {@code "df_blended_noise"} (dfc_support.cl), bundled
     * {@code "df_blended_noise_ns"} (preamble wrapper unpacking {@code _ns}), decide
     * {@code "sc_dec_blended_noise"} (emitDecide wrapper dropping {@code _g}). Every
     * variant forwards to the ONE flat {@code df_blended_noise} — same values by
     * construction. Called as {@code fn(treeArgs, permBase, octBase, nLimit, nMain,
     * xzMult, yMult, xzFactor, yFactor, smear, x, y, z)}.
     */
    private final String blendedNoiseFn;
    private final StringBuilder functions = new StringBuilder();
    private final Map<AstNode, String> nodeFns = new IdentityHashMap<>();
    private final Map<CubicSpline<?, ?>, String> splineFns = new IdentityHashMap<>();
    /**
     * Memoizes {@link McToAst#toAst(DensityFunction)} by DensityFunction identity.
     * {@code McToAst.toAst} rebuilds a FRESH AstNode tree on every call, so the same
     * spline-coordinate DF (continents/erosion/ridges/weirdness, referenced across many
     * sibling sub-splines of the overworld offset/factor/depth/finalDensity splines) was
     * re-emitted N times — {@link #nodeFns} dedups by identity and never saw them as equal.
     * Returning the SAME AstNode instance for a repeated coordinate DF restores that
     * identity dedup, shrinking the large spline DFs' generated source (and their
     * super-linear-in-size {@code clBuildProgram} cost). Output is unchanged: a deduped
     * function is byte-identical to the copies it replaces.
     */
    private final Map<DensityFunction, AstNode> coordAstMemo = new IdentityHashMap<>();
    // Pool structurally-identical helper functions by generated body text (bit-identical: a byte-identical
    // body over the identity-deduped child names computes the exact same value, so reuse == recompute).
    // Per-emitter (tag is constant within an instance) so the merged/tagged program stays correct.
    private final Map<String, String> helperBodyMemo = new java.util.HashMap<>();
    private int fnCounter = 0;
    private int splineCounter = 0;

    /** float[] constants (spline locations/derivatives) collected during emission. */
    private final List<float[]> floatArrays = new ArrayList<>();

    /**
     * COMPACT-IDS decide mode (Stage 5, {@link #emitDecide}): non-null maps a
     * CacheLikeNode (an {@code interpolated()} marker whose subtree IS a fused
     * corner-grid root) to its {@code ScDecG} slot — the per-block PRE-INTERPOLATED
     * grid value the kernel computes once and threads through every generated
     * function (the {@code _g} parameter, mirroring the bundleNoise mechanism).
     * {@code null} = normal emission (byte-identical to before).
     */
    private final Map<AstNode, Integer> decideGrids;

    private OpenCLAstEmitter(NoiseRegistry noiseRegistry, String tag) {
        this(noiseRegistry, tag, null);
    }

    private OpenCLAstEmitter(NoiseRegistry noiseRegistry, String tag, Map<AstNode, Integer> decideGrids) {
        this.noiseRegistry = noiseRegistry;
        this.tag = tag == null ? "" : tag;
        this.decideGrids = decideGrids;
        // Decide mode forces the flat-pointer form (its program is tiny — the
        // bundleNoiseState cold-compile lever is irrelevant there) and threads the
        // extra ScDecG value-struct through every generated function instead.
        this.bundleNoise = decideGrids == null && bundleNoiseState();
        if (decideGrids != null) {
            this.treeParams = NOISE_PARAMS + ", ScDecG _g";
            this.treeArgs = NOISE_ARGS + ", _g";
            this.noiseValueFn = "sc_dec_noise_value";
            this.blendedNoiseFn = "sc_dec_blended_noise";
        } else if (this.bundleNoise) {
            this.treeParams = "NoiseState _ns";
            this.treeArgs = "_ns";
            this.noiseValueFn = "df_noise_value_ns";
            this.blendedNoiseFn = "df_blended_noise_ns";
        } else {
            this.treeParams = NOISE_PARAMS;
            this.treeArgs = NOISE_ARGS;
            this.noiseValueFn = "df_noise_value";
            this.blendedNoiseFn = "df_blended_noise";
        }
    }

    /**
     * Reads the {@code -Dsuperchunk.gpu.bundleNoiseState} cold-compile lever (default FALSE).
     * When false the emitter is byte-for-byte the shipping flat-pointer emitter; when true it
     * emits the {@code NoiseState} struct-bundled form. Read per emitter construction so a
     * JVM-level {@code -D} toggle takes effect without a restart of the emitter machinery.
     */
    private static boolean bundleNoiseState() {
        return Boolean.parseBoolean(System.getProperty("superchunk.gpu.bundleNoiseState", "false"));
    }

    /**
     * Emits the bundled-mode program preamble at the TOP of the generated body (right after the
     * host-prepended, UNTOUCHED noise.cl + dfc_support.cl). No-op when the lever is off, so the
     * off-path program text is byte-identical to today. Defines:
     * <ul>
     *   <li>{@code NoiseState} — a struct bundling the 8 read-only noise pointers in
     *       {@link #NOISE_PARAMS} order. Uses {@code real}, so it is correct in BOTH the fp32
     *       ({@code real==float}) and fp64 ({@code real==double}) builds.</li>
     *   <li>{@code df_noise_value_ns} — unpacks {@code _ns} and forwards to the flat
     *       {@code df_noise_value} defined in dfc_support.cl. A struct member is the SAME pointer
     *       that would have been passed flatly, so every read (and thus every value) is identical
     *       by construction — a pure text/param-passing refactor, no math change.</li>
     * </ul>
     *
     * <p>Wrapped in an {@code #ifndef} include-guard: the MERGED-program path
     * ({@code GpuDfcHook.buildGroup}) concatenates many DFs' {@code emit().source()} bodies into
     * ONE program, so each would carry its own copy of this (tag-independent) preamble — the guard
     * makes the duplicates compile to a single definition instead of a redefinition error. The
     * single-DF / fused / cross-chunk paths emit exactly one copy either way.
     */
    private void appendBundlePreamble(StringBuilder src) {
        if (!bundleNoise) return;
        src.append("#ifndef SUPERCHUNK_NOISESTATE_DEFINED\n")
                .append("#define SUPERCHUNK_NOISESTATE_DEFINED\n")
                .append("typedef struct {\n")
                .append("    __global const int* noiseDesc;\n")
                .append("    __global const real* noiseFactors;\n")
                .append("    perm_ptr nPerm;\n")
                .append("    __global const int* nActive;\n")
                .append("    __global const real* nXo;\n")
                .append("    __global const real* nYo;\n")
                .append("    __global const real* nZo;\n")
                .append("    __global const real* nAmp;\n")
                .append("} NoiseState;\n")
                .append("inline real df_noise_value_ns(int id, NoiseState _ns, real x, real y, real z) {\n")
                .append("    return df_noise_value(id, _ns.noiseDesc, _ns.noiseFactors, _ns.nPerm, ")
                .append("_ns.nActive, _ns.nXo, _ns.nYo, _ns.nZo, _ns.nAmp, x, y, z);\n")
                .append("}\n")
                // BlendedNoise forwarder: unpack _ns, forward to the UNTOUCHED flat
                // df_blended_noise (dfc_support.cl) — same pointers, identical values.
                .append("inline real df_blended_noise_ns(NoiseState _ns, ")
                .append("int permBase, int octBase, int nLimit, int nMain, ")
                .append("real xzMult, real yMult, real xzFactor, real yFactor, real smear, ")
                .append("real x, real y, real z) {\n")
                .append("    return df_blended_noise(_ns.noiseDesc, _ns.noiseFactors, _ns.nPerm, ")
                .append("_ns.nActive, _ns.nXo, _ns.nYo, _ns.nZo, _ns.nAmp, ")
                .append("permBase, octBase, nLimit, nMain, xzMult, yMult, xzFactor, yFactor, smear, x, y, z);\n")
                .append("}\n")
                .append("#endif\n");
    }

    /**
     * The kernel-entry line that packs the 8 flat kernel-arg pointers into one local
     * {@code NoiseState _ns} (bundled mode) — passed by value to the generated tree. Empty when
     * the lever is off (byte-identical to today: no extra line emitted). The initializer order
     * matches {@link #NOISE_PARAMS} / the {@code NoiseState} member order exactly.
     */
    private String packNsLine() {
        return bundleNoise ? "    NoiseState _ns = { " + NOISE_ARGS + " };\n" : "";
    }

    /** The generated {@code <tag>fa<i>} __constant name for spline float-array index {@code i}. */
    private String fa(int i) {
        return tag + "fa" + i;
    }

    /**
     * Compiles {@code node} (the AST root) to OpenCL. Populates {@code noiseRegistry}
     * with referenced noises. Returns the generated source (functions + kernel),
     * which must be prepended with {@code noise.cl + dfc_support.cl}.
     *
     * @throws UnsupportedDfNodeException if any node can't be translated
     */
    public static Result emit(AstNode node, NoiseRegistry noiseRegistry) {
        return emit(node, noiseRegistry, "");
    }

    /**
     * Tagged emission for the MERGED-program path: every generated symbol is prefixed with
     * {@code tag} so this DF's functions/constants/kernels don't collide with the other DFs
     * sharing one program. {@code tag=""} reproduces {@link #emit(AstNode, NoiseRegistry)}
     * byte-for-byte. The returned {@link Result} carries the tag so the host retrieves the
     * right kernel names ({@code <tag>df_batch}, {@code <tag>df_batch_lattice}).
     */
    public static Result emit(AstNode node, NoiseRegistry noiseRegistry, String tag) {
        OpenCLAstEmitter e = new OpenCLAstEmitter(noiseRegistry, tag);
        String rootFn = e.emitNode(node);

        StringBuilder src = new StringBuilder();
        // Bundled-mode preamble (NoiseState typedef + df_noise_value_ns wrapper); no-op when off.
        e.appendBundlePreamble(src);
        // Emit float-array constants for splines as __constant globals.
        e.appendFloatConstants(src);
        src.append(e.functions);
        // ---- Array-coord kernel (fallback / non-lattice fills): coords uploaded. ----
        src.append("__kernel void ").append(e.tag).append("df_batch(\n")
                .append("        ").append(NOISE_PARAMS).append(",\n")
                .append("        __global const int* sx,\n")
                .append("        __global const int* sy,\n")
                .append("        __global const int* sz,\n")
                .append("        __global real* out,\n")
                .append("        const int n) {\n")
                .append("    int gid = get_global_id(0);\n")
                .append("    if (gid >= n) return;\n")
                .append(e.packNsLine())
                .append("    out[gid] = ").append(rootFn).append("(sx[gid], sy[gid], sz[gid], ").append(e.treeArgs).append(");\n")
                .append("}\n");
        // ---- Lattice-coord kernel (Phase B #3): coords computed from gid in-kernel.
        // No x/y/z uploads — the host passes 9 lattice scalars. Index layout matches
        // NoiseChunk.c2me$fillCoordinates: outer Y, mid X, inner Z (see LatticeCoords).
        src.append("__kernel void ").append(e.tag).append("df_batch_lattice(\n")
                .append("        ").append(NOISE_PARAMS).append(",\n")
                .append("        const int ox, const int oy, const int oz,\n")
                .append("        const int strx, const int stry, const int strz,\n")
                .append("        const int dx, const int dz,\n")
                .append("        __global real* out,\n")
                .append("        const int n) {\n")
                .append("    int gid = get_global_id(0);\n")
                .append("    if (gid >= n) return;\n")
                .append("    int plane = dx * dz;\n")
                .append("    int iy = gid / plane;\n")
                .append("    int rem = gid - iy * plane;\n")
                .append("    int ix = rem / dz;\n")
                .append("    int iz = rem - ix * dz;\n")
                .append("    int xx = ox + ix * strx;\n")
                .append("    int yy = oy + iy * stry;\n")
                .append("    int zz = oz + iz * strz;\n")
                .append(e.packNsLine())
                .append("    out[gid] = ").append(rootFn).append("(xx, yy, zz, ").append(e.treeArgs).append(");\n")
                .append("}\n");
        return new Result(src.toString(), e.tag);
    }

    /** Result of emission: the generated source and the per-DF symbol tag ({@code ""} = un-tagged). */
    public record Result(String source, String tag) {
    }

    /**
     * Compiles MULTIPLE AST roots into ONE program (the DF-fusion seam). All roots
     * share a single {@link NoiseRegistry} and one emitter instance, so common
     * sub-expressions and shared noise nodes are emitted ONCE across every root
     * (cross-DF CSE) instead of once per DF. Emits {@code df_batch_lattice_multi},
     * a multi-output variant of {@link #emit}'s {@code df_batch_lattice} that writes
     * root {@code k}'s whole-grid result to {@code out[k*n + gid]} — each n-length
     * slice is bit-identical to the corresponding single-root kernel's output, so
     * parity is preserved by construction.
     *
     * @throws UnsupportedDfNodeException if ANY root contains an untranslatable node
     */
    public static MultiResult emitMulti(List<AstNode> roots, NoiseRegistry noiseRegistry) {
        OpenCLAstEmitter e = new OpenCLAstEmitter(noiseRegistry, "");
        List<String> rootFns = new ArrayList<>(roots.size());
        for (AstNode root : roots) {
            // Same emitter instance across roots -> nodeFns/splineFns/noiseRegistry are
            // shared, so identical sub-trees and shared noise emit a single function.
            rootFns.add(e.emitNode(root));
        }

        StringBuilder src = new StringBuilder();
        e.appendBundlePreamble(src);
        e.appendFloatConstants(src);
        src.append(e.functions);
        // Multi-output lattice kernel. Same gid->(xx,yy,zz) decode as df_batch_lattice;
        // out is M consecutive n-length grids: root k -> out[k*n + gid].
        src.append("__kernel void df_batch_lattice_multi(\n")
                .append("        ").append(NOISE_PARAMS).append(",\n")
                .append("        const int ox, const int oy, const int oz,\n")
                .append("        const int strx, const int stry, const int strz,\n")
                .append("        const int dx, const int dz,\n")
                .append("        __global real* out,\n")
                .append("        const int n) {\n")
                .append("    int gid = get_global_id(0);\n")
                .append("    if (gid >= n) return;\n")
                .append("    int plane = dx * dz;\n")
                .append("    int iy = gid / plane;\n")
                .append("    int rem = gid - iy * plane;\n")
                .append("    int ix = rem / dz;\n")
                .append("    int iz = rem - ix * dz;\n")
                .append("    int xx = ox + ix * strx;\n")
                .append("    int yy = oy + iy * stry;\n")
                .append("    int zz = oz + iz * strz;\n")
                .append(e.packNsLine());
        for (int k = 0; k < rootFns.size(); k++) {
            src.append("    out[").append(k).append(" * n + gid] = ")
                    .append(rootFns.get(k)).append("(xx, yy, zz, ").append(e.treeArgs).append(");\n");
        }
        src.append("}\n");
        return new MultiResult(src.toString(), rootFns.size());
    }

    /** Result of multi-root (fused) emission: the generated source and the root count M. */
    public record MultiResult(String source, int rootCount) {
    }

    /**
     * CROSS-CHUNK multi-root emission (the batched-GPU-dispatch seam). Additive to
     * {@link #emitMulti}: emits {@code df_batch_lattice_multichunk}, which computes
     * MANY chunks' density grids in ONE NDRange. Every batched chunk shares the SAME
     * roots (this program) and the SAME strides/dims (overworld chunks have identical
     * cell-corner dims) but a DIFFERENT origin, supplied per-chunk through the
     * {@code __global const int* origins} table (K*3 ints: ox,oy,oz per chunk).
     *
     * <p>Global work size is {@code K*n}; work-item {@code gid} decodes
     * {@code chunkIdx = gid/n}, {@code cellIdx = gid%n}, reads that chunk's origin from
     * {@code origins[chunkIdx*3 + 0/1/2]}, then decodes {@code (xx,yy,zz)} from
     * {@code cellIdx} with the EXACT same plane/iy/ix/iz math as
     * {@code df_batch_lattice_multi}. It writes root {@code k} to
     * {@code out[chunkIdx*M*n + k*n + cellIdx] = out[(chunkIdx*M + k)*n + cellIdx]} — so
     * each per-chunk M*n slice {@code out[chunkIdx*M*n ..]} is BIT-IDENTICAL to the
     * single-chunk {@code df_batch_lattice_multi} output for that chunk's origin. Parity
     * is preserved by construction (same unrolled root functions, same noise buffers).
     *
     * @throws UnsupportedDfNodeException if ANY root contains an untranslatable node
     */
    public static MultiResult emitMultiChunk(List<AstNode> roots, NoiseRegistry noiseRegistry) {
        OpenCLAstEmitter e = new OpenCLAstEmitter(noiseRegistry, "");
        List<String> rootFns = new ArrayList<>(roots.size());
        for (AstNode root : roots) {
            // Same emitter instance across roots -> nodeFns/splineFns/noiseRegistry are
            // shared, so identical sub-trees and shared noise emit a single function
            // (cross-DF CSE), exactly like emitMulti.
            rootFns.add(e.emitNode(root));
        }

        StringBuilder src = new StringBuilder();
        e.appendBundlePreamble(src);
        e.appendFloatConstants(src);
        src.append(e.functions);
        // Cross-chunk multi-output lattice kernel. Per-chunk origin from the origins
        // table; same cellIdx->(xx,yy,zz) decode as df_batch_lattice_multi; out is
        // K*M*n with chunk c -> out[c*M*n ..] holding that chunk's M consecutive
        // n-length grids (root k -> out[c*M*n + k*n + cellIdx]). `total` (= K*n) is the
        // work-item guard (global size is rounded up to a multiple of 64).
        src.append("__kernel void df_batch_lattice_multichunk(\n")
                .append("        ").append(NOISE_PARAMS).append(",\n")
                .append("        __global const int* origins,\n")
                .append("        const int strx, const int stry, const int strz,\n")
                .append("        const int dx, const int dz,\n")
                .append("        const int n, const int M, const int total,\n")
                .append("        __global real* out) {\n")
                .append("    int gid = get_global_id(0);\n")
                .append("    if (gid >= total) return;\n")
                .append("    int chunkIdx = gid / n;\n")
                .append("    int cellIdx = gid - chunkIdx * n;\n")
                .append("    int ox = origins[chunkIdx * 3 + 0];\n")
                .append("    int oy = origins[chunkIdx * 3 + 1];\n")
                .append("    int oz = origins[chunkIdx * 3 + 2];\n")
                .append("    int plane = dx * dz;\n")
                .append("    int iy = cellIdx / plane;\n")
                .append("    int rem = cellIdx - iy * plane;\n")
                .append("    int ix = rem / dz;\n")
                .append("    int iz = rem - ix * dz;\n")
                .append("    int xx = ox + ix * strx;\n")
                .append("    int yy = oy + iy * stry;\n")
                .append("    int zz = oz + iz * strz;\n")
                .append("    int base = chunkIdx * M * n + cellIdx;\n")
                .append(e.packNsLine());
        for (int k = 0; k < rootFns.size(); k++) {
            src.append("    out[base + ").append(k).append(" * n] = ")
                    .append(rootFns.get(k)).append("(xx, yy, zz, ").append(e.treeArgs).append(");\n");
        }
        src.append("}\n");
        // Only emitted when the dedup path is armed: two extra entry points make ptxas do
        // measurably more work on a ~3 MB program whose cold compile is already ~60 s, and
        // gating here also makes the program-cache key follow the flag automatically.
        if (COL_DEDUP) {
            appendUniqueColumnKernels(src, e, rootFns);
        }
        return new MultiResult(src.toString(), rootFns.size());
    }

    /** Mirrors {@code GpuBatchDispatcher.COL_DEDUP} — the host and the kernel must agree. */
    private static final boolean COL_DEDUP = Boolean.getBoolean("superchunk.gpu.colDedup");

    /**
     * COLUMN-DEDUPLICATED corner evaluation: a second pair of kernels that computes the
     * same output as {@code df_batch_lattice_multichunk} while evaluating each distinct
     * cell column only ONCE.
     *
     * <p>A chunk's corner lattice is {@code dimX x dimZ} columns wide with
     * {@code (dimX-1)*strx == 16}, so its last row and column of columns sit exactly on the
     * neighbouring chunk's first — 25 columns per chunk of which only 16 are new in a tiled
     * sweep. Chunks that land in the same batch therefore re-evaluate every shared column's
     * whole {@code dimY}-tall stack of density functions, and the corner kernel is ~73% of
     * all GPU time.
     *
     * <p>Two kernels, on the same in-order queue:
     * <ol>
     *   <li>{@code df_batch_lattice_uniqcol} — one work-item per (distinct column, iy),
     *       writing {@code uniq[k*U*dimY + iy*U + u]}. Column-inner so a warp's consecutive
     *       work-items write consecutive addresses.</li>
     *   <li>{@code sc_expand_columns} — a pure copy that fans the distinct columns back out
     *       into the EXACT {@code out[c*M*n + k*n + cellIdx]} layout the rest of the
     *       pipeline (the chained decide kernel, the corner readback, the per-chunk grid
     *       store) already expects, so nothing downstream changes.</li>
     * </ol>
     *
     * <p><b>Bit-exact by construction:</b> stage 1 evaluates the same root functions at the
     * same integer {@code (xx, yy, zz)} as the one-kernel path, and stage 2 only moves those
     * bits. It requires every chunk in the batch to share {@code oy} (the host checks) —
     * otherwise a column is not interchangeable between them.
     */
    private static void appendUniqueColumnKernels(StringBuilder src, OpenCLAstEmitter e, List<String> rootFns) {
        src.append("__kernel void df_batch_lattice_uniqcol(\n")
                .append("        ").append(NOISE_PARAMS).append(",\n")
                .append("        __global const int* colXZ,\n")   // 2*U: x,z of each distinct column
                .append("        const int oy, const int stry,\n")
                .append("        const int dimY, const int U,\n")
                .append("        const int total,\n")             // U*dimY
                .append("        __global real* uniq) {\n")
                .append("    int gid = get_global_id(0);\n")
                .append("    if (gid >= total) return;\n")
                .append("    int iy = gid / U;\n")
                .append("    int u = gid - iy * U;\n")
                .append("    int xx = colXZ[u * 2];\n")
                .append("    int zz = colXZ[u * 2 + 1];\n")
                .append("    int yy = oy + iy * stry;\n")
                .append("    int base = iy * U + u;\n")
                .append("    int stride = U * dimY;\n")
                .append(e.packNsLine());
        for (int k = 0; k < rootFns.size(); k++) {
            src.append("    uniq[base + ").append(k).append(" * stride] = ")
                    .append(rootFns.get(k)).append("(xx, yy, zz, ").append(e.treeArgs).append(");\n");
        }
        src.append("}\n");

        // Fan-out copy. No density-function code at all, so it costs nothing to compile and
        // its runtime is pure bandwidth (~3 MB per batch against a ~20 ms corner kernel).
        src.append("__kernel void sc_expand_columns(\n")
                .append("        __global const real* uniq,\n")
                .append("        __global const int* colIdx,\n")   // K*dimX*dimZ -> distinct column index
                .append("        const int dimX, const int dimZ, const int dimY,\n")
                .append("        const int U, const int n, const int M, const int total,\n")
                .append("        __global real* out) {\n")
                .append("    int gid = get_global_id(0);\n")
                .append("    if (gid >= total) return;\n")
                .append("    int chunkIdx = gid / n;\n")
                .append("    int cellIdx = gid - chunkIdx * n;\n")
                .append("    int plane = dimX * dimZ;\n")
                .append("    int iy = cellIdx / plane;\n")
                .append("    int rem = cellIdx - iy * plane;\n")
                .append("    int u = colIdx[chunkIdx * plane + rem];\n")
                .append("    int srcIdx = iy * U + u;\n")
                .append("    int ustride = U * dimY;\n")
                .append("    int dst = chunkIdx * M * n + cellIdx;\n")
                .append("    for (int k = 0; k < M; ++k) {\n")
                .append("        out[dst + k * n] = uniq[srcIdx + k * ustride];\n")
                .append("    }\n")
                .append("}\n");
    }

    /**
     * fp32-compute / double-out variant of {@link #emitMultiChunk} for the CLIMATE
     * chain ({@code gpu.climateFp32}): built with {@code -DUSE_FP32} so the root
     * functions evaluate in fp32 ({@code real} = float — ~30-60x the fp64 ALU rate on
     * consumer NVIDIA), but the output buffer stays {@code __global double*} (each
     * value is the EXACT widening of the fp32 result), so every host-side buffer,
     * readback and consumer path is byte-layout-identical to the fp64 chain. The
     * host-side quantization guard (MixinClimateSampler) routes any sample whose
     * float value lands within epsilon of a {@code Climate.quantizeCoord} boundary
     * to the vanilla CPU compute, which is what makes the fp32 values biome-safe.
     * {@code cl_khr_fp64} is enabled here explicitly because noise.cl's
     * {@code -DUSE_FP32} branch does not enable it.
     */
    public static MultiResult emitMultiChunkF32D(List<AstNode> roots, NoiseRegistry noiseRegistry) {
        OpenCLAstEmitter e = new OpenCLAstEmitter(noiseRegistry, "");
        List<String> rootFns = new ArrayList<>(roots.size());
        for (AstNode root : roots) {
            rootFns.add(e.emitNode(root));
        }
        StringBuilder src = new StringBuilder();
        src.append("#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n");
        e.appendBundlePreamble(src);
        e.appendFloatConstants(src);
        src.append(e.functions);
        src.append("__kernel void df_batch_lattice_multichunk_f32d(\n")
                .append("        ").append(NOISE_PARAMS).append(",\n")
                .append("        __global const int* origins,\n")
                .append("        const int strx, const int stry, const int strz,\n")
                .append("        const int dx, const int dz,\n")
                .append("        const int n, const int M, const int total,\n")
                .append("        __global double* out) {\n")
                .append("    int gid = get_global_id(0);\n")
                .append("    if (gid >= total) return;\n")
                .append("    int chunkIdx = gid / n;\n")
                .append("    int cellIdx = gid - chunkIdx * n;\n")
                .append("    int ox = origins[chunkIdx * 3 + 0];\n")
                .append("    int oy = origins[chunkIdx * 3 + 1];\n")
                .append("    int oz = origins[chunkIdx * 3 + 2];\n")
                .append("    int plane = dx * dz;\n")
                .append("    int iy = cellIdx / plane;\n")
                .append("    int rem = cellIdx - iy * plane;\n")
                .append("    int ix = rem / dz;\n")
                .append("    int iz = rem - ix * dz;\n")
                .append("    int xx = ox + ix * strx;\n")
                .append("    int yy = oy + iy * stry;\n")
                .append("    int zz = oz + iz * strz;\n")
                .append("    int base = chunkIdx * M * n + cellIdx;\n")
                .append(e.packNsLine());
        for (int k = 0; k < rootFns.size(); k++) {
            src.append("    out[base + ").append(k).append(" * n] = (double) ")
                    .append(rootFns.get(k)).append("(xx, yy, zz, ").append(e.treeArgs).append(");\n");
        }
        src.append("}\n");
        return new MultiResult(src.toString(), rootFns.size());
    }

    /**
     * Serializes the collected spline {@code float[]} constants as
     * {@code __constant float faN[] = { ... };} globals into {@code src}. Shared by
     * every kernel emitter ({@link #emit}, {@link #emitMulti}, {@link #emitMultiChunk})
     * so the single-root, fused, and cross-chunk kernels keep identical constant
     * formatting from one place.
     */
    private void appendFloatConstants(StringBuilder src) {
        List<float[]> arrays = this.floatArrays;
        for (int i = 0; i < arrays.size(); i++) {
            float[] arr = arrays.get(i);
            src.append("__constant float ").append(fa(i)).append("[").append(Math.max(1, arr.length)).append("] = {");
            for (int j = 0; j < arr.length; j++) {
                if (j > 0) src.append(", ");
                src.append(floatLit(arr[j]));
            }
            if (arr.length == 0) src.append("0.0f");
            src.append("};\n");
        }
    }

    /**
     * COMPACT-IDS (Stage 5) decide-tail emission: compiles the REAL finalDensity
     * tail — {@code tailRoot} is the router finalDensity AST, with every
     * {@code interpolated()} marker in {@code gridSlots} replaced by the per-block
     * pre-interpolated corner-grid value {@code _g.v[slot]} — plus the router
     * {@code barrierNoise} AST, into per-block functions
     * {@code real <fn>(int x,int y,int z, NOISE_PARAMS, ScDecG _g)}. The caller
     * (CompactIds) pre-computes the {@code _g} slots per block (X&rarr;Y&rarr;Z
     * lerp3 over the device-resident multichunk corner buffer) and appends the
     * kernel entry. Nothing dimension-specific is hand-coded here: the tail is
     * whatever the actual AST between the markers and the root says.
     *
     * <p>Non-grid cache markers inside the tail are handled by CPU-consumption
     * semantics (see {@link #emitDecideCacheLike}); an interpolated marker that is
     * NOT a fused grid throws {@link UnsupportedDfNodeException} (inlining it would
     * CHANGE the math, not just its rounding — the caller then cleanly reports the
     * plan unavailable).
     *
     * @throws UnsupportedDfNodeException when the tail/barrier cannot be emitted
     */
    public static DecideResult emitDecide(AstNode tailRoot, AstNode barrierRoot,
                                          Map<AstNode, Integer> gridSlots, int slotCount,
                                          NoiseRegistry noiseRegistry) {
        return emitDecide(tailRoot, barrierRoot, null, null, null, gridSlots, slotCount, noiseRegistry);
    }

    /**
     * Decide emission WITH the ore-vein router slots (Phase A vein activation).
     * The vein roots are emitted through the SAME decide-mode emitter as the
     * tail/barrier — i.e. from the REAL captured ASTs, nothing hand-coded:
     * <ul>
     *   <li>{@code veinToggle} = {@code interpolated(rangeChoice(...))} — a single
     *       grid reference, resolves to {@code _g.v[slot]};</li>
     *   <li>{@code veinRidged} = {@code add(-0.08f, max(abs(interp(A)), abs(interp(B))))}
     *       — TWO grid references plus the per-block abs/max/add tail (the exact
     *       vanilla 1.21.1 shape; emitted generically whatever the AST says);</li>
     *   <li>{@code veinGap} = {@code noise(ORE_GAP)} — no grid at all, a plain
     *       in-kernel noise eval exactly like the barrier.</li>
     * </ul>
     * The caller pre-fills the vein grid slots in {@code gridSlots} with
     * VALUE-order interpolation slots ({@code sc_dec_value_grid} — the per-block
     * order the CPU vein DF reads see), distinct from the tail's lerp3 slots.
     * Vein roots may be {@code null} (veins unresolved) — the corresponding
     * function names in the result are then {@code null} and no vein code is
     * emitted (byte-identical to the veins-off program).
     */
    public static DecideResult emitDecide(AstNode tailRoot, AstNode barrierRoot,
                                          AstNode veinToggleRoot, AstNode veinRidgedRoot, AstNode veinGapRoot,
                                          Map<AstNode, Integer> gridSlots, int slotCount,
                                          NoiseRegistry noiseRegistry) {
        OpenCLAstEmitter e = new OpenCLAstEmitter(noiseRegistry, "", gridSlots);
        String tailFn = e.emitNode(tailRoot);
        String barrierFn = e.emitNode(barrierRoot);
        String veinToggleFn = veinToggleRoot == null ? null : e.emitNode(veinToggleRoot);
        String veinRidgedFn = veinRidgedRoot == null ? null : e.emitNode(veinRidgedRoot);
        String veinGapFn = veinGapRoot == null ? null : e.emitNode(veinGapRoot);
        StringBuilder src = new StringBuilder();
        // ScDecG + the noise-value forwarder MUST precede the generated functions
        // (they all take ", ScDecG _g" and call sc_dec_noise_value).
        src.append("#define SC_DEC_NG ").append(Math.max(1, slotCount)).append('\n')
                .append("typedef struct { real v[SC_DEC_NG]; } ScDecG;\n")
                .append("inline real sc_dec_noise_value(int id, ").append(NOISE_PARAMS)
                .append(", ScDecG _g, real x, real y, real z) {\n")
                .append("    return df_noise_value(id, ").append(NOISE_ARGS).append(", x, y, z);\n")
                .append("}\n")
                // BlendedNoise forwarder (decide mode): drop _g, forward to the
                // UNTOUCHED flat df_blended_noise (dfc_support.cl) — same pointers,
                // identical values. Mirrors sc_dec_noise_value; unused defs are
                // discarded by the driver when the tail contains no BlendedNoise.
                .append("inline real sc_dec_blended_noise(").append(NOISE_PARAMS)
                .append(", ScDecG _g, int permBase, int octBase, int nLimit, int nMain, ")
                .append("real xzMult, real yMult, real xzFactor, real yFactor, real smear, ")
                .append("real x, real y, real z) {\n")
                .append("    return df_blended_noise(").append(NOISE_ARGS)
                .append(", permBase, octBase, nLimit, nMain, xzMult, yMult, xzFactor, yFactor, smear, x, y, z);\n")
                .append("}\n");
        e.appendFloatConstants(src);
        src.append(e.functions);
        return new DecideResult(src.toString(), tailFn, barrierFn, veinToggleFn, veinRidgedFn, veinGapFn);
    }

    /**
     * Result of decide emission: generated source + the tail/barrier root function
     * names + the three vein root function names ({@code null} when veins were not
     * emitted). All functions share the signature
     * {@code real <fn>(int x,int y,int z, NOISE_PARAMS, ScDecG _g)}.
     */
    public record DecideResult(String source, String tailFn, String barrierFn,
                               String veinToggleFn, String veinRidgedFn, String veinGapFn) {
        public boolean veins() {
            return veinToggleFn != null && veinRidgedFn != null && veinGapFn != null;
        }
    }

    /**
     * Decide-mode CacheLikeNode handling — reproduce what the CPU FILL actually
     * consumes per block for each cache kind:
     * <ul>
     *   <li>a fused corner grid ({@code gridSlots} hit): the pre-interpolated
     *       {@code _g.v[slot]} (X&rarr;Y&rarr;Z lerp3 — census-proven bit-exact);</li>
     *   <li>{@code FlatCache}: the delegate at the quart-aligned column,
     *       {@code (blockX&~3, 0, blockZ&~3)} — vanilla NoiseChunk.FlatCache fills its
     *       values at exactly those coords and every in-chunk lookup is in range;</li>
     *   <li>{@code Cache2D}/{@code CacheOnce}/{@code CacheAllInCell} (and shared
     *       already-compiled sub-DFs): pure per-block memos &rarr; identity &rarr;
     *       inline through, exactly like normal emission;</li>
     *   <li>an interpolated marker that is NOT a fused grid: REFUSE (the CPU serves
     *       a trilinearly interpolated value there; inlining the subtree would
     *       compute a genuinely different number).</li>
     * </ul>
     */
    private String emitDecideCacheLike(CacheLikeNode cn) {
        Integer slot = decideGrids.get(cn);
        if (slot != null) {
            return "_g.v[" + slot + "]";
        }
        Object cacheLike = cn.getCacheLike();
        if (cacheLike == null) {
            return call(cn.getDelegate());
        }
        if (cacheLike instanceof DensityFunctions.MarkerOrMarked mm) {
            DensityFunctions.Marker.Type type = mm.type();
            if (type == DensityFunctions.Marker.Type.Interpolated) {
                throw new UnsupportedDfNodeException(
                        "decide: interpolated marker is not a fused corner grid — cannot reproduce the CPU value");
            }
            if (type == DensityFunctions.Marker.Type.FlatCache) {
                String fn = emitNode(cn.getDelegate());
                String body = "    return " + fn + "(((x >> 2) << 2), 0, ((z >> 2) << 2), " + treeArgs + ");\n";
                return emitHelperFn(body);
            }
            // Cache2D / CacheOnce / CacheAllInCell: identity memo per block.
            return call(cn.getDelegate());
        }
        if (cacheLike instanceof dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.SubCompiledDensityFunction) {
            // a shared, already-compiled sub-DF reused as a cache-like: identity.
            return call(cn.getDelegate());
        }
        throw new UnsupportedDfNodeException("decide: unrecognized cache-like "
                + cacheLike.getClass().getName() + " — refusing to guess its per-block semantics");
    }

    // ----------------------------------------------------------------------
    // Node dispatch — returns the name of a generated function
    //   real <name>(int x,int y,int z, DF_NOISE_PARAMS)
    // that computes this node's density.
    // ----------------------------------------------------------------------

    private String emitNode(AstNode node) {
        String cached = nodeFns.get(node);
        if (cached != null) {
            return cached;
        }
        String name = tag + "n" + (fnCounter++);
        nodeFns.put(node, name);

        // Build the function body into a temporary, then commit.
        StringBuilder body = new StringBuilder();
        body.append("real ").append(name).append("(int x, int y, int z, ").append(treeParams).append(") {\n");
        body.append("    return ").append(emitExpr(node)).append(";\n");
        body.append("}\n");
        functions.append(body);
        return name;
    }

    /** Forwards x,y,z + noise buffers to a node function and returns the call expression. */
    private String call(AstNode node) {
        String fn = emitNode(node);
        return fn + "(x, y, z, " + treeArgs + ")";
    }

    /**
     * Emits an OpenCL expression computing {@code node}'s value in the (x,y,z)
     * context. Simple nodes inline; nodes needing locals/branches delegate to a
     * helper function (via {@link #emitHelperFn}).
     */
    private String emitExpr(AstNode node) {
        // ---- structural ----
        if (node instanceof RootNode r) {
            return call(child(r, 0));
        }
        // ---- cache nodes: pure CPU-side memoization, semantically identity ----
        // CacheLikeNode (FlatCache / Cache2D / CacheOnce / Interpolated markers) and
        // DelegateNode wrap a sub-function with a CPU cache. On the GPU we recompute
        // every cell regardless, so a cache node is semantically equal to its
        // delegate -- inline straight through it. This is what makes the live
        // batch-fillArray path (always cache-wrapped) actually GPU-compilable.
        if (node instanceof CacheLikeNode cn) {
            if (decideGrids != null) {
                return emitDecideCacheLike(cn);
            }
            return call(cn.getDelegate());
        }
        // BlendedNoise ("old_blended_noise"): a SUPPORTED DelegateNode subtype —
        // must be matched BEFORE the generic DelegateNode opacity fallback below.
        if (node instanceof BlendedNoiseNode bn) {
            return emitBlendedNoise(bn);
        }
        if (node instanceof DelegateNode dn) {
            AstNode inner = delegateInnerAst(dn);
            if (inner != null) {
                return call(inner);
            }
            // A DelegateNode wrapping an opaque/compiled density function we can't
            // reconstruct as AST -> fall back to CPU for the whole DF (safe).
            throw new UnsupportedDfNodeException("DelegateNode wraps a non-inlinable density function: "
                    + dn.getDelegate().getClass().getName());
        }
        // ---- constant ----
        if (node instanceof ConstantNode c) {
            return realLit(c.getValue());
        }
        // ---- binary arithmetic ----
        if (node instanceof AddNode b) {
            return "(" + call(child(b, 0)) + " + " + call(child(b, 1)) + ")";
        }
        if (node instanceof MulNode b) {
            // evalSingle: left==0 ? 0 : left*right (short-circuit on the LEFT).
            return helperBinaryMul(b);
        }
        if (node instanceof MinShortNode b) {
            return helperMinShort(b);
        }
        if (node instanceof MaxShortNode b) {
            return helperMaxShort(b);
        }
        if (node instanceof MinNode b) {
            return "fmin(" + call(child(b, 0)) + ", " + call(child(b, 1)) + ")";
        }
        if (node instanceof MaxNode b) {
            return "fmax(" + call(child(b, 0)) + ", " + call(child(b, 1)) + ")";
        }
        // ---- unary ----
        if (node instanceof AbsNode u) {
            return "fabs(" + call(child(u, 0)) + ")";
        }
        if (node instanceof SquareNode u) {
            return helperSquare(u);
        }
        if (node instanceof CubeNode u) {
            return helperCube(u);
        }
        if (node instanceof NegMulNode u) {
            return helperNegMul(u);
        }
        if (node instanceof SqueezeNode u) {
            return helperSqueeze(u);
        }
        // ---- misc ----
        if (node instanceof YClampedGradientNode g) {
            return emitYClampedGradient(g);
        }
        if (node instanceof RangeChoiceNode rc) {
            return helperRangeChoice(rc);
        }
        // ---- noise ----
        if (node instanceof DFTNoiseNode dn) {
            return emitDftNoise(dn);
        }
        if (node instanceof ShiftedNoiseNode sn) {
            return helperShiftedNoise(sn);
        }
        if (node instanceof DFTShiftNode sn) {
            return emitShift(sn, "x", "y", "z");
        }
        if (node instanceof DFTShiftANode sn) {
            return emitShiftA(sn);
        }
        if (node instanceof DFTShiftBNode sn) {
            return emitShiftB(sn);
        }
        if (node instanceof DFTWeirdScaledSamplerNode wn) {
            return helperWeirdScaled(wn);
        }
        // ---- spline ----
        if (node instanceof SplineAstNode sp) {
            return emitSplineRoot(sp);
        }
        throw new UnsupportedDfNodeException("Unsupported AstNode subtype: " + node.getClass().getName()
                + " (DelegateNode / CacheLikeNode and unknown nodes fall back to CPU)");
    }

    // ----------------------------------------------------------------------
    // Helper-function emission for nodes that need locals / branches.
    // Each appends a `real <name>(int x,int y,int z, NOISE_PARAMS)` and returns
    // a call to it.
    // ----------------------------------------------------------------------

    /** Allocates a uniquely-named helper function with the given body statements. */
    private String emitHelperFn(String bodyStatements) {
        String existing = helperBodyMemo.get(bodyStatements);
        if (existing != null) return existing + "(x, y, z, " + treeArgs + ")";
        String name = tag + "h" + (fnCounter++);
        functions.append("real ").append(name).append("(int x, int y, int z, ").append(treeParams).append(") {\n");
        functions.append(bodyStatements);
        functions.append("}\n");
        helperBodyMemo.put(bodyStatements, name);
        return name + "(x, y, z, " + treeArgs + ")";
    }

    private String helperBinaryMul(MulNode b) {
        String left = call(child(b, 0));
        String right = call(child(b, 1));
        // real l = left; return l == 0.0 ? 0.0 : l * right;
        String body = "    real l = " + left + ";\n"
                + "    if (l == REAL_C(0.0)) return REAL_C(0.0);\n"
                + "    return l * (" + right + ");\n";
        return emitHelperFn(body);
    }

    private String helperMinShort(MinShortNode b) {
        double rightMin = readDoubleField(b, "rightMin");
        String left = call(child(b, 0));
        String right = call(child(b, 1));
        String body = "    real l = " + left + ";\n"
                + "    if (l <= " + realLit(rightMin) + ") return l;\n"
                + "    return fmin(l, " + right + ");\n";
        return emitHelperFn(body);
    }

    private String helperMaxShort(MaxShortNode b) {
        double rightMax = readDoubleField(b, "rightMax");
        String left = call(child(b, 0));
        String right = call(child(b, 1));
        String body = "    real l = " + left + ";\n"
                + "    if (l >= " + realLit(rightMax) + ") return l;\n"
                + "    return fmax(l, " + right + ");\n";
        return emitHelperFn(body);
    }

    private String helperSquare(SquareNode u) {
        String body = "    real v = " + call(child(u, 0)) + ";\n"
                + "    return v * v;\n";
        return emitHelperFn(body);
    }

    private String helperCube(CubeNode u) {
        String body = "    real v = " + call(child(u, 0)) + ";\n"
                + "    return v * v * v;\n";
        return emitHelperFn(body);
    }

    private String helperNegMul(NegMulNode u) {
        double negMul = readDoubleField(u, "negMul");
        String body = "    real v = " + call(child(u, 0)) + ";\n"
                + "    return v > REAL_C(0.0) ? v : v * " + realLit(negMul) + ";\n";
        return emitHelperFn(body);
    }

    private String helperSqueeze(SqueezeNode u) {
        // v = clamp(operand,-1,1); v/2 - v*v*v/24
        String body = "    real v = mth_clamp(" + call(child(u, 0)) + ", REAL_C(-1.0), REAL_C(1.0));\n"
                + "    return v / REAL_C(2.0) - v * v * v / REAL_C(24.0);\n";
        return emitHelperFn(body);
    }

    private String emitYClampedGradient(YClampedGradientNode g) {
        double fromY = readDoubleField(g, "fromY");
        double toY = readDoubleField(g, "toY");
        double fromValue = readDoubleField(g, "fromValue");
        double toValue = readDoubleField(g, "toValue");
        // Mth.clampedMap((double)y, fromY,toY,fromValue,toValue)
        return "mth_clamped_map((real) y, " + realLit(fromY) + ", " + realLit(toY) + ", "
                + realLit(fromValue) + ", " + realLit(toValue) + ")";
    }

    private String helperRangeChoice(RangeChoiceNode rc) {
        double minInclusive = readDoubleField(rc, "minInclusive");
        double maxExclusive = readDoubleField(rc, "maxExclusive");
        AstNode input = child(rc, 0);
        AstNode whenInRange = child(rc, 1);
        AstNode whenOutOfRange = child(rc, 2);
        String body = "    real v = " + call(input) + ";\n"
                + "    if (v >= " + realLit(minInclusive) + " && v < " + realLit(maxExclusive) + ") {\n"
                + "        return " + call(whenInRange) + ";\n"
                + "    } else {\n"
                + "        return " + call(whenOutOfRange) + ";\n"
                + "    }\n";
        return emitHelperFn(body);
    }

    // ---- noise nodes ----

    private String emitDftNoise(DFTNoiseNode dn) {
        DensityFunction.NoiseHolder noise = readNoiseHolder(dn, "noise");
        double xzScale = readDoubleField(dn, "xzScale");
        double yScale = readDoubleField(dn, "yScale");
        int id = noiseRegistry.register(noise);
        // noise.getValue(x*xzScale, y*yScale, z*xzScale)
        return noiseValueFn + "(" + id + ", " + treeArgs + ", "
                + "(real) x * " + realLit(xzScale) + ", "
                + "(real) y * " + realLit(yScale) + ", "
                + "(real) z * " + realLit(xzScale) + ")";
    }

    /**
     * Vanilla {@code BlendedNoise} ("old_blended_noise"): the whole {@code compute()}
     * runs inside {@code df_blended_noise} (dfc_support.cl) — a bit-exact port of the
     * legacy three-PerlinNoise blend (min/max limit octaves -15..0, main -7..0, the
     * yLimit/smear {@code ImprovedNoise.noise(x,y,z,yScale,yMax)} sampling variant,
     * 10.0/2.0 main normalization, {@code Mth.clampedLerp(d8/512, d9/512, d16)/128}).
     * The node's three samplers are registered ONCE per program (identity-deduped)
     * into the SAME flat octave/permutation buffers as the NormalNoise entries; their
     * base offsets + octave counts + the five scalar parameters are baked as literals
     * (xzMultiplier/yMultiplier are the exact CONSTRUCTED {@code 684.412*scale} field
     * bits). Raw block coordinates pass through unscaled — {@code compute()} applies
     * its own multipliers internally, exactly like vanilla.
     */
    private String emitBlendedNoise(BlendedNoiseNode bn) {
        NoiseRegistry.BlendedSlot slot = noiseRegistry.registerBlended(bn.getBlendedNoise());
        return blendedNoiseFn + "(" + treeArgs + ", "
                + slot.permBase() + ", " + slot.octaveBase() + ", "
                + slot.nLimit() + ", " + slot.nMain() + ", "
                + realLit(bn.getXzMultiplier()) + ", " + realLit(bn.getYMultiplier()) + ", "
                + realLit(bn.getXzFactor()) + ", " + realLit(bn.getYFactor()) + ", "
                + realLit(bn.getSmearScaleMultiplier()) + ", "
                + "(real) x, (real) y, (real) z)";
    }

    private String helperShiftedNoise(ShiftedNoiseNode sn) {
        DensityFunction.NoiseHolder noise = readNoiseHolder(sn, "noise");
        double xzScale = readDoubleField(sn, "xzScale");
        double yScale = readDoubleField(sn, "yScale");
        int id = noiseRegistry.register(noise);
        AstNode shiftX = child(sn, 0);
        AstNode shiftY = child(sn, 1);
        AstNode shiftZ = child(sn, 2);
        // d = x*xzScale + shiftX ; e = y*yScale + shiftY ; f = z*xzScale + shiftZ
        String body = "    real d = (real) x * " + realLit(xzScale) + " + " + call(shiftX) + ";\n"
                + "    real e = (real) y * " + realLit(yScale) + " + " + call(shiftY) + ";\n"
                + "    real f = (real) z * " + realLit(xzScale) + " + " + call(shiftZ) + ";\n"
                + "    return " + noiseValueFn + "(" + id + ", " + treeArgs + ", d, e, f);\n";
        return emitHelperFn(body);
    }

    /** DFTShift: offsetNoise.getValue(x*.25,y*.25,z*.25)*4. */
    private String emitShift(DFTShiftNode sn, String xExpr, String yExpr, String zExpr) {
        DensityFunction.NoiseHolder noise = readNoiseHolder(sn, "offsetNoise");
        int id = noiseRegistry.register(noise);
        return "(" + noiseValueFn + "(" + id + ", " + treeArgs + ", "
                + "(real) " + xExpr + " * REAL_C(0.25), "
                + "(real) " + yExpr + " * REAL_C(0.25), "
                + "(real) " + zExpr + " * REAL_C(0.25)) * REAL_C(4.0))";
    }

    /** DFTShiftA: offsetNoise.getValue(x*.25, 0.0, z*.25)*4. */
    private String emitShiftA(DFTShiftANode sn) {
        DensityFunction.NoiseHolder noise = readNoiseHolder(sn, "offsetNoise");
        int id = noiseRegistry.register(noise);
        return "(" + noiseValueFn + "(" + id + ", " + treeArgs + ", "
                + "(real) x * REAL_C(0.25), REAL_C(0.0), (real) z * REAL_C(0.25)) * REAL_C(4.0))";
    }

    /** DFTShiftB: offsetNoise.getValue(z*.25, x*.25, 0.0)*4. */
    private String emitShiftB(DFTShiftBNode sn) {
        DensityFunction.NoiseHolder noise = readNoiseHolder(sn, "offsetNoise");
        int id = noiseRegistry.register(noise);
        return "(" + noiseValueFn + "(" + id + ", " + treeArgs + ", "
                + "(real) z * REAL_C(0.25), (real) x * REAL_C(0.25), REAL_C(0.0)) * REAL_C(4.0))";
    }

    private String helperWeirdScaled(DFTWeirdScaledSamplerNode wn) {
        DensityFunction.NoiseHolder noise = readNoiseHolder(wn, "noise");
        DensityFunctions.WeirdScaledSampler.RarityValueMapper mapper = readMapper(wn);
        int id = noiseRegistry.register(noise);
        AstNode input = child(wn, 0);
        // d = scaleFn(input); return d * fabs(noise.getValue(x/d, y/d, z/d))
        String scaleExpr = rarityScaleExpr(mapper, "(" + call(input) + ")");
        String body = "    real d = " + scaleExpr + ";\n"
                + "    return d * fabs(" + noiseValueFn + "(" + id + ", " + treeArgs + ", "
                + "(real) x / d, (real) y / d, (real) z / d));\n";
        return emitHelperFn(body);
    }

    /**
     * Inlines the RarityValueMapper scale function. The two vanilla mappers
     * (TYPE1/TYPE2) are simple stepwise maps; emit them directly so no host
     * callback is needed. Throws if a mapper isn't one of the known two.
     */
    private String rarityScaleExpr(DensityFunctions.WeirdScaledSampler.RarityValueMapper mapper, String valueExpr) {
        String name = mapper.name();
        // mirror DensityFunctions.RarityValueMapper TYPE1/TYPE2 scale functions exactly.
        switch (name) {
            case "TYPE1":
                // getSpaghettiRarity3D
                return "weird_rarity_type1(" + valueExpr + ")";
            case "TYPE2":
                // getSpaghettiRarity2D
                return "weird_rarity_type2(" + valueExpr + ")";
            default:
                throw new UnsupportedDfNodeException("Unknown WeirdScaledSampler mapper: " + name);
        }
    }

    // ---- spline ----

    private String emitSplineRoot(SplineAstNode node) {
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline = readSpline(node);
        String splineFn = emitSpline(spline);
        // spline.apply -> float; node returns (double) that value.
        return "((real) " + splineFn + "(x, y, z, " + treeArgs + "))";
    }

    /**
     * Emits a {@code float <name>(int x,int y,int z, NOISE_PARAMS)} for a CubicSpline,
     * mirroring SplineAstNode / CubicSpline.Multipoint.apply (all float math).
     * Returns the function name.
     */
    @SuppressWarnings("unchecked")
    private String emitSpline(CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline) {
        String cached = splineFns.get(spline);
        if (cached != null) {
            return cached;
        }
        String name = tag + "spl" + (splineCounter++);
        splineFns.put(spline, name);

        StringBuilder b = new StringBuilder();
        b.append("float ").append(name).append("(int x, int y, int z, ").append(treeParams).append(") {\n");

        if (spline instanceof CubicSpline.Constant<?, ?> c) {
            b.append("    return ").append(floatLit(c.value())).append(";\n");
            b.append("}\n");
            functions.append(b);
            return name;
        }
        if (!(spline instanceof CubicSpline.Multipoint<?, ?> impl0)) {
            throw new UnsupportedDfNodeException("Unsupported spline implementation: " + spline.getClass().getName());
        }
        CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> impl =
                (CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>) impl0;

        float[] locations = impl.locations();
        float[] derivatives = impl.derivatives();
        List<? extends CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>> values =
                impl.values();

        int locIdx = addFloatArray(locations);
        int derIdx = addFloatArray(derivatives);

        // The coordinate's underlying density function -> AST -> a node function. Memoize
        // toAst by DF identity so a coordinate DF shared across sibling sub-splines yields
        // the SAME AstNode instance and emitNode dedups it (instead of re-emitting the
        // whole multi-octave subtree per sub-spline).
        AstNode coordAst = coordAstMemo.computeIfAbsent(
                impl.coordinate().function().value(), McToAst::toAst);
        String coordFn = emitNode(coordAst);

        // pre-emit child spline functions
        String[] childFns = new String[values.size()];
        for (int i = 0; i < values.size(); i++) {
            childFns[i] = emitSpline(values.get(i));
        }

        int lastConst = locations.length - 1;
        // point = (float) coordFn(...)
        b.append("    float point = (float) ").append(coordFn).append("(x, y, z, ").append(treeArgs).append(");\n");

        if (childFns.length == 1) {
            b.append("    return spline_sample_outside(point, ").append(fa(locIdx)).append(", ")
                    .append(childFns[0]).append("(x, y, z, ").append(treeArgs).append("), ")
                    .append(fa(derIdx)).append(", 0);\n");
        } else {
            b.append("    int r = spline_find_range(").append(fa(locIdx)).append(", ").append(locations.length).append(", point);\n");
            b.append("    if (r < 0) {\n");
            b.append("        return spline_sample_outside(point, ").append(fa(locIdx)).append(", ")
                    .append(childFns[0]).append("(x, y, z, ").append(treeArgs).append("), ")
                    .append(fa(derIdx)).append(", 0);\n");
            b.append("    }\n");
            b.append("    if (r == ").append(lastConst).append(") {\n");
            b.append("        return spline_sample_outside(point, ").append(fa(locIdx)).append(", ")
                    .append(childFns[lastConst]).append("(x, y, z, ").append(treeArgs).append("), ")
                    .append(fa(derIdx)).append(", ").append(lastConst).append(");\n");
            b.append("    }\n");
            // interpolation between range r and r+1
            b.append("    float loc0 = ").append(fa(locIdx)).append("[r];\n");
            b.append("    float loc1 = ").append(fa(locIdx)).append("[r + 1];\n");
            b.append("    float locDist = loc1 - loc0;\n");
            // spline_div (dfc_support.cl): correctly-rounded float divide. NVIDIA's
            // default fp32 '/' is an approximate (<=2.5 ulp) divide, which drifted the
            // spline by 1 float ULP vs Java's correctly-rounded divide on planes where
            // the approximate quotient rounds differently (Stage-1 attribution).
            b.append("    float k = spline_div(point - loc0, locDist);\n");
            b.append("    float nv; float ov;\n");
            // switch over r in [0, values.length-2]
            b.append("    switch (r) {\n");
            for (int i = 0; i < childFns.length - 1; i++) {
                b.append("        case ").append(i).append(": nv = ")
                        .append(childFns[i]).append("(x, y, z, ").append(treeArgs).append("); ov = ")
                        .append(childFns[i + 1]).append("(x, y, z, ").append(treeArgs).append("); break;\n");
            }
            b.append("        default: nv = 0.0f; ov = 0.0f; break;\n");
            b.append("    }\n");
            b.append("    float onDist = ov - nv;\n");
            b.append("    float p = ").append(fa(derIdx)).append("[r] * locDist - onDist;\n");
            b.append("    float q = -").append(fa(derIdx)).append("[r + 1] * locDist + onDist;\n");
            b.append("    return spline_lerp(k, nv, ov) + k * (1.0f - k) * spline_lerp(k, p, q);\n");
        }
        b.append("}\n");
        functions.append(b);
        return name;
    }

    private int addFloatArray(float[] arr) {
        int idx = floatArrays.size();
        floatArrays.add(arr);
        return idx;
    }

    // ----------------------------------------------------------------------
    // AstNode reflection helpers (the C2ME node fields are private).
    // ----------------------------------------------------------------------

    private static AstNode child(AstNode node, int idx) {
        AstNode[] children = node.getChildren();
        return children[idx];
    }

    /**
     * Recovers an inlinable AST for a {@link DelegateNode}'s wrapped density
     * function. {@code McToAst} builds cache markers whose {@code wrapped()} is an
     * already-compiled {@code CompiledDensityFunction} (not round-trippable to AST),
     * so those are NOT inlinable here — return {@code null} and let the caller fall
     * back to CPU. Only a genuinely vanilla wrapped DF (rare in practice) is
     * convertible; if its sub-tree has an unsupported node the recursive emit throws
     * and we still fall back. Conservative on purpose: never produce a wrong kernel.
     */
    private static AstNode delegateInnerAst(DelegateNode dn) {
        // Preferred: the original sub-tree AST stashed by McToAst for cache markers.
        AstNode hint = dn.superchunk$getInnerAst();
        if (hint != null) {
            return hint;
        }
        DensityFunction df = dn.getDelegate();
        // A Marker around a CompiledDensityFunction cannot be reconstructed faithfully.
        if (df instanceof DensityFunctions.MarkerOrMarked marker
                && marker.wrapped() instanceof SubCompiledDensityFunction) {
            return null;
        }
        try {
            AstNode ast = McToAst.toAst(df);
            // TERMINATION GUARD (bugfix): an McToAst-OPAQUE density function (its
            // switch's default case — e.g. vanilla BlendedNoise "old_blended_noise"
            // inside the overworld/nether finalDensity, or EndIslands) round-trips to a
            // FRESH DelegateNode wrapping the SAME df. Recursing into that re-enters
            // this method with a brand-new node every time (the emitNode identity memo
            // never hits), which was measured as a StackOverflowError killing the whole
            // DF's GPU compile (probe run 2026-07-01, s5_probe2 debug.log). Such a node
            // is simply not inlinable -> null -> clean UnsupportedDfNodeException ->
            // per-DF CPU fallback, exactly the documented contract.
            // EXCEPTION: BlendedNoiseNode IS a DelegateNode subtype but is fully
            // supported by the emitter (df_blended_noise) and terminates trivially
            // (a leaf — no recursion back into this method), so let it through.
            // This also RECOVERS the typed node if a plain DelegateNode wrapping a
            // raw BlendedNoise ever reaches the emitter (e.g. a mapAll copy built
            // before the typed McToAst case existed).
            if (ast instanceof BlendedNoiseNode) {
                return ast;
            }
            return ast instanceof DelegateNode ? null : ast;
        } catch (Throwable t) {
            return null;
        }
    }

    // Resolved-Field cache keyed by (declaringClass, fieldName). The same
    // (class, field) pairs recur across every node of a type in a large DF (hundreds
    // of YClampedGradient/RangeChoice/DFTNoise nodes), so caching removes the repeated
    // getDeclaredField linear scan + setAccessible module-access check. Emit-time only;
    // byte-identical generated output.
    private static final Map<String, Field> FIELD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Field cachedField(Class<?> cls, String field) throws NoSuchFieldException {
        Field f = FIELD_CACHE.get(cls.getName() + "#" + field);
        if (f != null) {
            return f;
        }
        Field resolved = cls.getDeclaredField(field);
        resolved.setAccessible(true);
        FIELD_CACHE.put(cls.getName() + "#" + field, resolved);
        return resolved;
    }

    private static double readDoubleField(AstNode node, String field) {
        try {
            return cachedField(node.getClass(), field).getDouble(node);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedDfNodeException("Could not read field " + field + " on " + node.getClass());
        }
    }

    private static DensityFunction.NoiseHolder readNoiseHolder(AstNode node, String field) {
        try {
            return (DensityFunction.NoiseHolder) cachedField(node.getClass(), field).get(node);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedDfNodeException("Could not read noise field " + field + " on " + node.getClass());
        }
    }

    private static DensityFunctions.WeirdScaledSampler.RarityValueMapper readMapper(AstNode node) {
        try {
            return (DensityFunctions.WeirdScaledSampler.RarityValueMapper) cachedField(node.getClass(), "mapper").get(node);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedDfNodeException("Could not read mapper on " + node.getClass());
        }
    }

    @SuppressWarnings("unchecked")
    private static CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> readSpline(AstNode node) {
        try {
            return (CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>) cachedField(node.getClass(), "spline").get(node);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedDfNodeException("Could not read spline on " + node.getClass());
        }
    }

    // ----------------------------------------------------------------------
    // Literal formatting.
    // ----------------------------------------------------------------------

    /** A `real` literal via REAL_C() so it adapts to fp32/fp64. */
    public static String realLit(double v) {
        return "REAL_C(" + doubleRepr(v) + ")";
    }

    /** A `float` literal for spline math (always float in vanilla). */
    public static String floatLit(float v) {
        if (Float.isNaN(v)) return "NAN";
        if (Float.isInfinite(v)) return v > 0 ? "INFINITY" : "-INFINITY";
        return floatRepr(v) + "f";
    }

    /** Exact double string (round-trippable). */
    private static String doubleRepr(double v) {
        if (Double.isNaN(v)) return "NAN";
        if (Double.isInfinite(v)) return v > 0 ? "INFINITY" : "-INFINITY";
        // Java's Double.toString is round-trip exact.
        return Double.toString(v);
    }

    private static String floatRepr(float v) {
        return Float.toString(v);
    }
}
