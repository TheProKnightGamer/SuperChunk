package dev.superchunk.gpu.aquifer;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * STAGE-3 census (Phase A) — pass-through recorder for ONE of the three ore-vein
 * density functions handed to the vanilla {@code OreVeinifier.create} lambda
 * ({@code veinToggle} / {@code veinRidged} / {@code veinGap}). Installed ONLY when
 * {@code -Dsuperchunk.gpu.blockIdVerify} is on (see {@code MixinNoiseChunkVeinCensus});
 * every {@link #compute} forwards to the UNCHANGED delegate and returns its exact
 * value — shipped terrain is untouched — while depositing the value (plus the ore
 * positional-Xoroshiro seed halves) into this worker's live census accumulator
 * ({@link BlockIdCensus#recordVein}). Because the OreVeinifier computes these DFs
 * exactly as lazily as its decision tree demands, the capture mask mirrors the real
 * branch structure: toggle for every aquifer-null block, ridged/gap only where
 * vanilla actually evaluated them.
 */
public final class ScVeinCaptureDf implements DensityFunction {

    private final DensityFunction delegate;
    /** {@link BlockIdCensus#VEIN_TOGGLE} / {@link BlockIdCensus#VEIN_RIDGED} / {@link BlockIdCensus#VEIN_GAP}. */
    private final int kind;
    private final long seedLo;
    private final long seedHi;
    private final boolean seedOk;

    public ScVeinCaptureDf(DensityFunction delegate, int kind, long seedLo, long seedHi, boolean seedOk) {
        this.delegate = delegate;
        this.kind = kind;
        this.seedLo = seedLo;
        this.seedHi = seedHi;
        this.seedOk = seedOk;
    }

    @Override
    public double compute(FunctionContext context) {
        double v = delegate.compute(context);
        BlockIdCensus.recordVein(kind, context.blockX(), context.blockY(), context.blockZ(),
                v, seedLo, seedHi, seedOk);
        return v;
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        // The OreVeinifier only ever calls compute(); a bulk fill (not used on this
        // path) passes through untouched and simply records nothing.
        delegate.fillArray(array, contextProvider);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // Not expected post-construction (the wrappers are installed AFTER the
        // NoiseChunk ctor's mapAll(this::wrap)); keep the recorder in place if it
        // ever happens so capture cannot silently vanish.
        return new ScVeinCaptureDf(delegate.mapAll(visitor), kind, seedLo, seedHi, seedOk);
    }

    @Override
    public double minValue() {
        return delegate.minValue();
    }

    @Override
    public double maxValue() {
        return delegate.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return delegate.codec();
    }
}
