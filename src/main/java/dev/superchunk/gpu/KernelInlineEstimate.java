package dev.superchunk.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimates how much code the OpenCL compiler sees once it has inlined every call, for
 * {@link CLProgramCache}'s build guard.
 *
 * <p>The generated density-function programs are thousands of small functions (one per spline,
 * shared subtree, noise) that each kernel reaches through a call DAG, and the NVIDIA compiler
 * inlines the whole DAG into the kernel. Source size says little about that: vanilla's largest
 * per-function program is 2.8 MB of source, Tectonic's final density 2.6 MB, yet Tectonic's splines
 * are shared by so many call sites that its programs measure 1.9-11.7 GB here (with the noise
 * headers) against vanilla's 214 MB at most. Vanilla builds cold at a ~7 GB process peak; one of
 * Tectonic's took the server past 20 GB until the OOM killer stopped it.
 *
 * <p>The estimate: every top-level function body (a {@code {...}} at depth 0 whose header ends in
 * {@code )}), the calls each body makes to other functions of the program (counted per call
 * site), and for each function its own length plus the inlined size of every callee. The result
 * is the largest such size, in characters. OpenCL C has no recursion; a cycle is cut, not looped.
 */
final class KernelInlineEstimate {

    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final long CAP = Long.MAX_VALUE / 4;

    private KernelInlineEstimate() {
    }

    /** The largest fully-inlined function size in {@code source}, in characters. */
    static long maxInlinedChars(String source) {
        Map<String, int[]> bodies = new HashMap<>();
        int depth = 0;
        int bodyStart = -1;
        String name = null;
        for (int i = 0, n = source.length(); i < n; i++) {
            char c = source.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    name = functionName(source, i);
                    bodyStart = i + 1;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && name != null) {
                    bodies.put(name, new int[]{bodyStart, i});
                    name = null;
                } else if (depth < 0) {
                    depth = 0; // unbalanced: give up on structure, keep scanning
                }
            }
        }
        Map<String, List<String>> calls = new HashMap<>(bodies.size() * 2);
        for (Map.Entry<String, int[]> e : bodies.entrySet()) {
            List<String> callees = new ArrayList<>();
            Matcher m = CALL.matcher(source).region(e.getValue()[0], e.getValue()[1]);
            while (m.find()) {
                String callee = m.group(1);
                if (bodies.containsKey(callee) && !callee.equals(e.getKey())) {
                    callees.add(callee);
                }
            }
            calls.put(e.getKey(), callees);
        }
        Map<String, Long> memo = new HashMap<>(bodies.size() * 2);
        long max = 0;
        for (String fn : bodies.keySet()) {
            max = Math.max(max, inlined(fn, bodies, calls, memo));
        }
        return max;
    }

    private static long inlined(String fn, Map<String, int[]> bodies, Map<String, List<String>> calls,
                                Map<String, Long> memo) {
        Long known = memo.get(fn);
        if (known != null) {
            return known;
        }
        memo.put(fn, 0L); // in progress: a cycle contributes nothing
        int[] body = bodies.get(fn);
        long size = body[1] - body[0];
        for (String callee : calls.get(fn)) {
            size = Math.min(CAP, size + inlined(callee, bodies, calls, memo));
        }
        memo.put(fn, size);
        return size;
    }

    /** The name of the function whose body opens at {@code brace}, or null for any other block. */
    private static String functionName(String source, int brace) {
        int i = brace - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        if (i < 0 || source.charAt(i) != ')') {
            return null; // an initializer, struct or other non-function block
        }
        int parens = 0;
        for (; i >= 0; i--) {
            char c = source.charAt(i);
            if (c == ')') parens++;
            else if (c == '(' && --parens == 0) break;
        }
        i--;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        int end = i + 1;
        while (i >= 0 && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) i--;
        return end > i + 1 ? source.substring(i + 1, end) : null;
    }
}
