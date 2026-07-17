package dev.superchunk.ca.spottedleaf.starlight.common.blockstate;

public interface ExtendedAbstractBlockState {

    public boolean isConditionallyFullOpaque();

    public int getOpacityIfCached();

    public boolean scalablelux$actuallyDynamicLightEmission();

}
