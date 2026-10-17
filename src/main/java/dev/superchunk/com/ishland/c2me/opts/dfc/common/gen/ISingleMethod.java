package dev.superchunk.com.ishland.c2me.opts.dfc.common.gen;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;

@FunctionalInterface
public interface ISingleMethod {

    double evalSingle(int x, int y, int z, EvalType type);

}
