package dev.superchunk;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records, per target class and per target method, whether a mixin outside SuperChunk changes it.
 *
 * <p>Some optimizations replace a whole vanilla method or the objects it creates. They must
 * stand down when another mod has hooked the same code, or that mod's changes would be
 * skipped. Mixin's {@code ClassInfo.getAppliedMixins()} does not answer this for a target class,
 * and its internals are not opened to other modules, so the check reads the transformed class
 * itself: every method a mixin merges into its target, including {@code @Overwrite}s and injector
 * handlers, carries a {@code @MixinMerged(mixin = ...)} annotation. A no-op probe mixin with the
 * highest priority is applied after all others, and {@code SuperChunkMixinPlugin.postApply}
 * passes the finished class here.
 *
 * <p>Merged methods that only expose existing state — accessor getters and invokers, which many
 * mods add for reading (Create's Ponder and ModernFix both add one to {@code BiomeManager}) — do
 * not count: they change nothing. Setters do count.
 *
 * <p>Per method ({@link #foreignHook}): a method is hooked when a foreign mixin overwrote it, when
 * its body calls a foreign merged method (what {@code @Inject}, {@code @Redirect},
 * {@code @ModifyArg}, {@code @WrapOperation} and the like leave behind), or when a foreign
 * MixinExtras {@code @WrapMethod} handler wraps it (that stage runs after this scan, so it is
 * matched by its handler's name and descriptor instead).
 *
 * <p>Results are kept in system properties rather than a static field so they are visible
 * whichever class loader loaded the plugin and the game code. NeoForge coremods and other
 * non-Mixin transformers are not visible to this check.
 */
public final class MixinTargetScan {
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
    private static final String PROPERTY = "superchunk.internal.mixinScan.";
    private static final String OURS = "dev.superchunk.";

    private MixinTargetScan() {
    }

    /** Called once per probed class after every mixin has been applied to it. */
    public static void record(String targetClassName, ClassNode target) {
        final String className = targetClassName.replace('/', '.');
        // name+desc of every foreign merged method that can change behaviour -> its mixin
        final Map<String, String> foreign = new HashMap<>();
        String classForeign = null;
        for (MethodNode method : target.methods) {
            String mixin = foreignMixin(method.visibleAnnotations);
            if (mixin == null) {
                mixin = foreignMixin(method.invisibleAnnotations);
            }
            if (mixin != null && !exposesOnly(method)) {
                foreign.put(method.name + method.desc, mixin);
                if (classForeign == null) {
                    classForeign = mixin;
                }
            }
        }
        System.setProperty(PROPERTY + className, classForeign == null ? "" : classForeign);
        if (foreign.isEmpty()) {
            return;
        }
        for (MethodNode method : target.methods) {
            String hook = foreign.get(method.name + method.desc);
            if (hook == null) {
                hook = firstForeignCall(method, target.name, foreign);
            }
            // A MixinExtras @WrapMethod moves the original body (with every injection) into
            // "<name>$mixinextras$wrapped$<n>": report it under the method's own name.
            String name = method.name;
            int wrapped = name.indexOf("$mixinextras$wrapped$");
            if (wrapped > 0) {
                name = name.substring(0, wrapped);
            }
            if (hook != null && System.getProperty(PROPERTY + className + "#" + name) == null) {
                System.setProperty(PROPERTY + className + "#" + name, hook);
            }
        }
        // @WrapMethod handlers, keyed by the descriptor they wrap (checked above per method)
        for (Map.Entry<String, String> entry : Map.copyOf(foreign).entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("wrapMethod$")) {
                int desc = key.indexOf('(');
                String wrapped = key.substring(desc).replace(OPERATION + ")", ")");
                for (MethodNode method : target.methods) {
                    if (method.desc.equals(wrapped) && !method.name.contains("$")
                            && System.getProperty(PROPERTY + className + "#" + method.name) == null) {
                        System.setProperty(PROPERTY + className + "#" + method.name, entry.getValue());
                    }
                }
            }
        }
    }

    /**
     * {@code null} when the class was scanned and only SuperChunk's mixins (or other mods'
     * read-only accessors) were merged into it; otherwise the first foreign mixin found, or a
     * reason the class was not scanned.
     */
    public static String foreignMixin(String className) {
        String recorded = System.getProperty(PROPERTY + className);
        if (recorded == null) {
            return "<" + className + " was not scanned>";
        }
        return recorded.isEmpty() ? null : recorded;
    }

    /** The first foreign mixin among {@code classNames}, or {@code null} if all are clean. */
    public static String foreignMixin(String... classNames) {
        for (String name : classNames) {
            String foreign = foreignMixin(name);
            if (foreign != null) {
                return foreign;
            }
        }
        return null;
    }

    /**
     * For {@code "pkg.Class#method"} names (every overload of the method): {@code null} when each
     * class was scanned and none of the methods is hooked by another mod; otherwise the first
     * foreign mixin found, or a reason the class was not scanned.
     */
    public static String foreignHook(String... classHashMethods) {
        for (String name : classHashMethods) {
            String className = name.substring(0, name.indexOf('#'));
            if (System.getProperty(PROPERTY + className) == null) {
                return "<" + className + " was not scanned>";
            }
            String hook = System.getProperty(PROPERTY + name);
            if (hook != null && !hook.isEmpty()) {
                return hook;
            }
        }
        return null;
    }

    /** A getter ({@code [aload] getfield|getstatic; return}) or invoker ({@code loads; invoke; return}). */
    static boolean exposesOnly(MethodNode method) {
        boolean access = false;
        boolean returned = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op < 0) {
                continue; // label, line number, frame
            }
            if (returned) {
                return false;
            }
            if (op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
                continue;
            }
            if (!access && (op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC || insn instanceof MethodInsnNode)) {
                access = true;
                continue;
            }
            if (access && op == Opcodes.CHECKCAST) {
                continue;
            }
            if (access && op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                returned = true;
                continue;
            }
            return false;
        }
        return access && returned;
    }

    private static String firstForeignCall(MethodNode method, String owner, Map<String, String> foreign) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) {
                String hook = foreign.get(call.name + call.desc);
                if (hook != null) {
                    return hook;
                }
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle handle && handle.getOwner().equals(owner)) {
                        String hook = foreign.get(handle.getName() + handle.getDesc());
                        if (hook != null) {
                            return hook;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String foreignMixin(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (!MERGED.equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("mixin".equals(annotation.values.get(i))) {
                    String mixin = String.valueOf(annotation.values.get(i + 1));
                    if (!mixin.startsWith(OURS)) {
                        return mixin;
                    }
                }
            }
        }
        return null;
    }
}
