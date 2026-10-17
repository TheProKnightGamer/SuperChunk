package dev.superchunk.com.ishland.c2me.opts.dfc.common.gen;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;

@FunctionalInterface
public interface IMultiMethod {

    void evalMulti(double[] res, int[] x, int[] y, int[] z, EvalType type, ArrayCache arrayCache);

}
