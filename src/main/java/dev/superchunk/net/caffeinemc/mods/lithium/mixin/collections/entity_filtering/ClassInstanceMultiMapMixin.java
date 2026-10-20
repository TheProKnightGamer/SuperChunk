package dev.superchunk.net.caffeinemc.mods.lithium.mixin.collections.entity_filtering;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.util.ClassInstanceMultiMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.*;

/**
 * Patches {@link ClassInstanceMultiMap} to improve performance when entities are being queried in the world.
 */
@Mixin(ClassInstanceMultiMap.class)
public class ClassInstanceMultiMapMixin<T> {

    @Shadow
    @Final
    private Map<Class<?>, List<T>> byClass;

    @Shadow
    @Final
    private List<T> allInstances;

    /** Stand down when another mod hooks find ({@link dev.superchunk.worldgen.ForeignHooks}). */
    @Unique
    private static byte superchunk$hooks;

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("Lithium's entity type filter (ClassInstanceMultiMap.find)",
                    "net.minecraft.util.ClassInstanceMultiMap#find");
            superchunk$hooks = state;
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }

    /**
     * Only perform the slow Class#isAssignableFrom(Class) if a list doesn't exist for the type, otherwise
     * we can assume it's already valid. The slow-path code is moved to a separate method to help the JVM inline this.
     * (JellySquid)
     *
     * <p>SuperChunk: upstream overwrites find; a wrap leaves vanilla's body in place, so another mod's
     * change to it still runs when this stands down. Performant replaces find to hand back the type's
     * list itself and casts the result to ArrayList in EntitySection.getEntities; with this overwrite
     * in its place, entity queries crashed with a ClassCastException (GitHub #22).
     */
    @WrapMethod(method = "find")
    private Collection<?> superchunk$find(Class<?> type, Operation<Collection<?>> original) {
        if (!superchunk$unhooked()) {
            return original.call(type);
        }
        Collection<T> collection = this.byClass.get(type);

        if (collection == null) {
            collection = this.createAllOfType(type);
        }

        return Collections.unmodifiableCollection(collection);
    }

    private <S> Collection<T> createAllOfType(Class<S> type) {
        List<T> list = new ArrayList<>();

        for (T allElement : this.allInstances) {
            if (type.isInstance(allElement)) {
                list.add(allElement);
            }
        }

        this.byClass.put(type, list);

        return list;
    }
}
