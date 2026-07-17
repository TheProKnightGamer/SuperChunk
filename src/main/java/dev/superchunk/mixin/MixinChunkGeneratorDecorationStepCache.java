package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Registry;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.stream.Collector;
import java.util.stream.Stream;

/**
 * SuperChunk: cache {@code applyBiomeDecoration}'s structure-by-step map.
 *
 * <p>Vanilla rebuilds {@code registry.stream().collect(groupingBy(step ordinal))} from
 * the FULL structure registry for EVERY decorated chunk. The registry is frozen during
 * gen, so the result is a pure function of the registry instance — cache it keyed on
 * registry identity (a datapack reload creates a new registry → natural invalidation).
 *
 * <p>Bit-identical: the cached map is the exact object a previous {@code original.call}
 * produced; downstream only reads it ({@code map.getOrDefault}). Safe publication via a
 * single volatile {@code {registry, map}} pair — key and map must publish atomically, or a
 * datapack {@code /reload} racing active decoration could match the old key against the new
 * registry's map. Concurrent readers of an effectively-immutable HashMap are safe.
 * {@code @WrapOperation} composes with other mods' injections into the method.
 * Disable with {@code -Dsuperchunk.worldgen.decorationStepCache=false}.
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGeneratorDecorationStepCache {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.decorationStepCache", "true"));

    /** {@code {registry, map}}, published as one volatile store so the pair can never tear. */
    @Unique
    private static volatile Object[] superchunk$stepMapEntry;

    @WrapOperation(
            method = "applyBiomeDecoration",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"),
            require = 0)
    private Object superchunk$cachedStepMap(Stream<?> stream, Collector<?, ?, ?> collector,
                                            Operation<Object> original,
                                            @Local(ordinal = 0) Registry<Structure> registry) {
        if (!SUPERCHUNK$ENABLED) {
            return original.call(stream, collector);
        }
        Object[] entry = superchunk$stepMapEntry;
        if (entry != null && entry[0] == registry) {
            return entry[1];
        }
        Object map = original.call(stream, collector);
        superchunk$stepMapEntry = new Object[] {registry, map};
        return map;
    }
}
