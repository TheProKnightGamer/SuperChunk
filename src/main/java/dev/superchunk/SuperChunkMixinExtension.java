package dev.superchunk;

import dev.superchunk.compat.ByePregenCompat;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Mixin extension with two jobs, each run for every class Mixin transforms.
 *
 * <p><b>Before the mixins apply:</b> it drops other mods' mixins that cannot run alongside a bundled
 * mod, where that mod's own check for the bundled one cannot see it because the copy here is
 * relocated ({@link ByePregenCompat#dropsMixin}: Bye Pregen's YA light, which Bye Pregen turns off
 * itself when ScalableLux is installed). MixinSquared cancels mixins the same way.
 *
 * <p><b>After the whole class is transformed:</b> it records each probed class a second time. The
 * probe's {@code postApply} ({@link MixinTargetScan#record}) runs inside Mixin's applicator, before
 * the extensions' {@code postApply}, and MixinExtras applies its late injectors
 * ({@code @WrapOperation}, {@code @ModifyExpressionValue}, {@code @ModifyReturnValue},
 * {@code @ModifyReceiver}, {@code @WrapWithCondition}) and every {@code @WrapMethod} there. At probe
 * time those handlers are merged but nothing calls them yet, so a hook made with one of them was
 * never attributed to its method and the replica ran anyway: Collections of Optimizations'
 * {@code MixinBeardifierReach} ({@code @ModifyExpressionValue} in {@code Beardifier.compute}) was
 * skipped by the beardifier replica. {@link MixinTargetScan#record} only adds per-method entries,
 * so recording again never loses one.
 *
 * <p>The extension keeps itself last in Mixin's extension list (MixinExtras registers its own when
 * it initialises, which can be after us), so its {@code postApply} sees the finished method bodies.
 */
final class SuperChunkMixinExtension implements IExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");
    /** Internal names of the classes the probe recorded, waiting for the late pass. */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final SuperChunkMixinExtension INSTANCE = new SuperChunkMixinExtension();
    private static volatile boolean registered;

    private static Field extensionsField;
    private static Field activeField;
    /** {@code TargetClassContext.mixins}, the mixins about to apply to the class. */
    private static volatile Field mixinsField;
    private static volatile boolean dropFailureLogged;

    private SuperChunkMixinExtension() {
    }

    /** Called by the probe's postApply: record this class again after the late injectors. */
    static void pending(String internalName) {
        if (registered) {
            PENDING.add(internalName);
        }
    }

    /** Adds the extension. Without it neither job runs (the probe's first record stands). */
    static synchronized void register() {
        if (registered) {
            return;
        }
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (!(transformer instanceof IMixinTransformer mixinTransformer)
                    || !(mixinTransformer.getExtensions() instanceof Extensions extensions)) {
                LOGGER.warn("[SuperChunk] Mixin's transformer has no extension list; hooks made with MixinExtras' "
                        + "late injectors will not make SuperChunk's replicas stand down, and other mods' mixins "
                        + "that cannot run with a bundled mod are not turned off.");
                return;
            }
            extensionsField = Extensions.class.getDeclaredField("extensions");
            extensionsField.setAccessible(true);
            activeField = Extensions.class.getDeclaredField("activeExtensions");
            activeField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<IExtension> all = (List<IExtension>) extensionsField.get(extensions);
            all.add(INSTANCE);
            moveLast(extensions);
            registered = true;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk] Could not register SuperChunk's mixin extension ({}); hooks made with "
                    + "MixinExtras' late injectors will not make SuperChunk's replicas stand down, and other mods' "
                    + "mixins that cannot run with a bundled mod are not turned off.", t.toString());
        }
    }

    /** Puts this extension at the end of the active list unless it is there already. */
    private static void moveLast(Extensions extensions) throws IllegalAccessException {
        @SuppressWarnings("unchecked")
        List<IExtension> active = (List<IExtension>) activeField.get(extensions);
        if (!active.isEmpty() && active.get(active.size() - 1) == INSTANCE) {
            return;
        }
        List<IExtension> reordered = new ArrayList<>(active);
        reordered.remove(INSTANCE);
        reordered.add(INSTANCE);
        activeField.set(extensions, Collections.unmodifiableList(reordered));
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (transformer instanceof IMixinTransformer mixinTransformer
                    && mixinTransformer.getExtensions() instanceof Extensions extensions) {
                moveLast(extensions);
            }
        } catch (Throwable ignored) {
            // the extension list is read again for the next class
        }
        if (ByePregenCompat.INSTALLED) {
            dropForeignMixins(context);
        }
    }

    /** Removes the mixins {@link ByePregenCompat#dropsMixin} names from those about to apply. */
    private static void dropForeignMixins(ITargetClassContext context) {
        try {
            Field field = mixinsField;
            if (field == null) {
                field = context.getClass().getDeclaredField("mixins");
                field.setAccessible(true);
                mixinsField = field;
            }
            @SuppressWarnings("unchecked")
            Collection<? extends IMixinInfo> mixins = (Collection<? extends IMixinInfo>) field.get(context);
            mixins.removeIf(info -> ByePregenCompat.dropsMixin(info.getClassName()));
        } catch (Throwable t) {
            if (!dropFailureLogged) {
                dropFailureLogged = true;
                LOGGER.warn("[SuperChunk] Could not read the mixins about to apply ({}); other mods' mixins that "
                        + "cannot run with a bundled mod are not turned off.", t.toString());
            }
        }
    }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode node = context.getClassNode();
        if (node != null && PENDING.remove(node.name)) {
            MixinTargetScan.record(node.name, node);
        }
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
    }
}
