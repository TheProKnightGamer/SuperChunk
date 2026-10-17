package dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.common.utils;

import dev.superchunk.com.ishland.c2me.rewrites.chunk_serializer.mixin.IStarlightSaveState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class StarLightUtil {
    static final MethodHandle getBlockNibbles;
    static final MethodHandle getSkyNibbles;
    static final MethodHandle getSaveState;

    static {
        try {
            var lookup = MethodHandles.lookup();

            Class<?> ExtendedChunkInterface = lookup.findClass("dev.superchunk.ca.spottedleaf.starlight.common.chunk.ExtendedChunk");
            Class<?> SWMRNibbleArrayClass = lookup.findClass("dev.superchunk.ca.spottedleaf.starlight.common.light.SWMRNibbleArray");
            Class<?> SWMRNibbleArrayClassArray = SWMRNibbleArrayClass.arrayType();
            Class<?> SaveStateClass = lookup.findClass("dev.superchunk.ca.spottedleaf.starlight.common.light.SWMRNibbleArray$SaveState");

            MethodType nibbleArrayGetter = MethodType.methodType(SWMRNibbleArrayClassArray);

            getBlockNibbles = lookup.findVirtual(ExtendedChunkInterface, "getBlockNibbles", nibbleArrayGetter);
            getSkyNibbles = lookup.findVirtual(ExtendedChunkInterface, "getSkyNibbles", nibbleArrayGetter);

            MethodType saveStateGetter = MethodType.methodType(SaveStateClass);

            getSaveState = lookup.findVirtual(SWMRNibbleArrayClass, "getSaveState", saveStateGetter);

        } catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException e) {
            throw new RuntimeException("StarLightUtil failed to resolve starlight classes", e);
        }
    }

    public static Object[] getBlockNibbles(ChunkAccess chunk) {
        try {
            return (Object[]) getBlockNibbles.invoke(chunk);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static Object[] getSkyNibbles(ChunkAccess chunk) {
        try {
            return (Object[]) getSkyNibbles.invoke(chunk);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static IStarlightSaveState getSaveState(Object nibbleArray) {
        try {
            return (IStarlightSaveState) getSaveState.invoke(nibbleArray);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
