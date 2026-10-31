package dev.superchunk.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lets MixinSquared cancellers that name a bundled mod's mixin cancel SuperChunk's copy of it.
 *
 * <p>Mods cancel other mods' mixins they conflict with through MixinSquared's {@code MixinCanceller},
 * which is asked about each mixin by class name. Every bundled mod is relocated under
 * {@code dev.superchunk.*}, so a canceller looking for its real name never matched the copy here:
 * AsyncParticles cancels Lithium's {@code collections.entity_by_type} mixins when its thread-safe
 * entity map is on, and with SuperChunk they applied anyway. When MixinSquared is installed this
 * registers one more canceller, which asks all the others about a bundled mixin's original name
 * (for Lithium, also the {@code me.jellysquid} package older Lithium and Radium use) and cancels the
 * copy here if any of them would cancel the original. It runs when MixinSquared applies its
 * cancellations, so it sees every canceller whenever it was registered. Kill switch:
 * {@code -Dsuperchunk.compat.mixinSquared=false}.
 */
public final class MixinSquaredBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");
    private static final String REGISTRAR = "com.bawnorton.mixinsquared.canceller.MixinCancellerRegistrar";
    private static final String CANCELLER = "com.bawnorton.mixinsquared.api.MixinCanceller";
    private static final String OURS = "dev.superchunk.";
    /** The bundled mods' own package roots, as relocated under {@link #OURS}. */
    private static final String[] BUNDLED = {
            "net.caffeinemc.mods.lithium.", "com.ishland.c2me.", "com.ishland.vmp.", "ca.spottedleaf.starlight.",
            "io.github.steveplays28.noisium.",
    };
    private static final String LITHIUM = "net.caffeinemc.mods.lithium.";
    private static final String OLD_LITHIUM = "me.jellysquid.mods.lithium.";

    private MixinSquaredBridge() {
    }

    /** Called once from {@code SuperChunkMixinPlugin.onLoad}. Never throws. */
    public static void register() {
        if (!Boolean.parseBoolean(System.getProperty("superchunk.compat.mixinSquared", "true"))) {
            return;
        }
        Class<?> registrar;
        Class<?> canceller;
        try {
            ClassLoader loader = MixinSquaredBridge.class.getClassLoader();
            registrar = Class.forName(REGISTRAR, false, loader);
            canceller = Class.forName(CANCELLER, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return; // MixinSquared is not installed
        }
        try {
            Object bridge = Proxy.newProxyInstance(canceller.getClassLoader(), new Class<?>[]{canceller},
                    new Bridge(registrar));
            registrar.getMethod("register", canceller).invoke(null, bridge);
            LOGGER.info("[SuperChunk-Compat] MixinSquared is installed: cancellers that name a bundled mod's mixins "
                    + "also cancel SuperChunk's copies.");
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Compat] Could not register with MixinSquared; cancellers naming a bundled mod's "
                    + "mixins will not affect SuperChunk's copies.", t);
        }
    }

    /** The original names a relocated bundled mixin may be cancelled by, or an empty list if it is not one. */
    static List<String> originalNames(String mixinClassName) {
        List<String> names = new ArrayList<>(2);
        if (mixinClassName.startsWith(OURS)) {
            String original = mixinClassName.substring(OURS.length());
            for (String root : BUNDLED) {
                if (original.startsWith(root)) {
                    names.add(original);
                    if (root.equals(LITHIUM)) {
                        names.add(OLD_LITHIUM + original.substring(LITHIUM.length()));
                    }
                    break;
                }
            }
        }
        return names;
    }

    private static final class Bridge implements InvocationHandler {
        private Method shouldCancel;
        private Method shouldCancelLegacy;
        private Field cancellers;

        Bridge(Class<?> registrar) {
            for (Method m : registrar.getMethods()) {
                if (m.getName().equals("shouldCancel") && m.getParameterCount() == 3) this.shouldCancel = m;
                if (m.getName().equals("shouldCancel") && m.getParameterCount() == 2) this.shouldCancelLegacy = m;
            }
            if (this.shouldCancel == null && this.shouldCancelLegacy == null) {
                try {
                    this.cancellers = registrar.getDeclaredField("cancellers");
                    this.cancellers.setAccessible(true);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "shouldCancel":
                    if (args != null && args.length == 2 && args[0] instanceof List<?> targets && args[1] instanceof String name) {
                        return this.cancels(proxy, targets, name);
                    }
                    return false;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "SuperChunk MixinSquared bridge";
                default:
                    return null;
            }
        }

        /** Asks the other cancellers about the original names; the registrar asks this bridge too, which says no. */
        private boolean cancels(Object self, List<?> targets, String mixinClassName) {
            for (String original : originalNames(mixinClassName)) {
                try {
                    String by = this.ask(self, targets, original);
                    if (by != null) {
                        LOGGER.info("[SuperChunk-Compat] {} cancels {}, so SuperChunk's copy {} is cancelled too.",
                                by, original, mixinClassName);
                        return true;
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[SuperChunk-Compat] MixinSquared canceller check failed for {}", original, t);
                    return false;
                }
            }
            return false;
        }

        /** The cancelling canceller's class name, "a MixinSquared canceller" if unknown, or null if none cancels. */
        private String ask(Object self, List<?> targets, String original) throws ReflectiveOperationException {
            if (this.shouldCancel != null) {
                String[] by = new String[1];
                Consumer<String> record = name -> by[0] = name;
                return (Boolean) this.shouldCancel.invoke(null, targets, original, record)
                        ? (by[0] != null ? by[0] : "a MixinSquared canceller") : null;
            }
            if (this.shouldCancelLegacy != null) {
                return (Boolean) this.shouldCancelLegacy.invoke(null, targets, original) ? "a MixinSquared canceller" : null;
            }
            if (this.cancellers != null && this.cancellers.get(null) instanceof Collection<?> all) {
                for (Object other : List.copyOf(all)) {
                    if (other != self) {
                        Method m = other.getClass().getMethod("shouldCancel", List.class, String.class);
                        if ((Boolean) m.invoke(other, targets, original)) {
                            return other.getClass().getName();
                        }
                    }
                }
            }
            return null;
        }
    }
}
