package dev.superchunk.chunkio;

import dev.superchunk.mixin.IPalettedContainerStrategyAccess;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.lang.reflect.Method;

/**
 * {@code PalettedContainer.Strategy.calculateBitsForSerialization} is package-private and is not
 * widened by an access transformer (that crashes other mods' overwrites of it, GitHub #31). In game
 * it is reached through {@link IPalettedContainerStrategyAccess}. The standalone regression tests run
 * without Mixin, so there it is called reflectively.
 */
final class PaletteStrategyBits {

    private PaletteStrategyBits() {}

    static <A> int calculate(PalettedContainer.Strategy strategy, IdMap<A> registry, int size) {
        if (strategy instanceof IPalettedContainerStrategyAccess access) {
            return access.superchunk$calculateBitsForSerialization(registry, size);
        }
        return Unmixed.calculate(strategy, registry, size);
    }

    /** Loaded only when the invoker mixin is absent. */
    private static final class Unmixed {
        private static final Method METHOD = find();

        private static Method find() {
            for (String name : new String[] {"calculateBitsForSerialization", "m_188151_"}) {
                try {
                    Method m = PalettedContainer.Strategy.class.getDeclaredMethod(name, IdMap.class, int.class);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
            }
            throw new IllegalStateException("PalettedContainer.Strategy.calculateBitsForSerialization not found");
        }

        static int calculate(PalettedContainer.Strategy strategy, IdMap<?> registry, int size) {
            try {
                return (int) METHOD.invoke(strategy, registry, size);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
