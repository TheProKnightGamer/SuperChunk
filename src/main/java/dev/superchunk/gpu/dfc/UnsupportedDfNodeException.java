package dev.superchunk.gpu.dfc;

/**
 * Thrown by {@link OpenCLAstEmitter} when it encounters an AST node subtype it
 * cannot translate to OpenCL C (e.g. a {@code DelegateNode} wrapping an opaque
 * vanilla density function, a {@code CacheLikeNode}, an unsupported spline
 * implementation, or a node that would require an active {@code Blender}).
 *
 * <p>This is the graceful per-density-function fallback signal: when emission of
 * a given density function throws this, that DF stays on C2ME's CPU bytecode
 * path; all other DFs that <i>do</i> compile run on the GPU.
 */
public final class UnsupportedDfNodeException extends RuntimeException {
    public UnsupportedDfNodeException(String message) {
        super(message);
    }
}
