package dev.superchunk.worldgen;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A mutable {@link DensityFunction.FunctionContext} — a reusable stand-in for vanilla's immutable
 * {@code DensityFunction.SinglePointContext} record, so the carver's per-block aquifer probe does
 * not allocate a fresh context for every carved block (see {@code MixinWorldCarverContextReuse}).
 *
 * <p>Behaviourally identical to {@code SinglePointContext}: both expose only {@code blockX/Y/Z} and
 * inherit the same default {@code getBlender()} (neither overrides it). The context is only ever
 * read synchronously by {@code Aquifer.computeSubstance} (verified: it reads the coordinates and
 * forwards the reference down its call chain without storing it), so a single instance whose
 * coordinates are set immediately before each call yields bit-identical results — provided each
 * worker thread uses its own instance (the carver singletons are shared across threads).
 */
public final class ScMutableFunctionContext implements DensityFunction.FunctionContext {

    private int x;
    private int y;
    private int z;

    public void set(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public int blockX() {
        return this.x;
    }

    @Override
    public int blockY() {
        return this.y;
    }

    @Override
    public int blockZ() {
        return this.z;
    }
}
