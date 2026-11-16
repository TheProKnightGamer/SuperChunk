package dev.superchunk.compat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Checks that everything a patch refers to in another mod's code is there, before the patch is
 * applied.
 *
 * <p>A mixin into another mod is compiled against that mod's classes as they were (here: stubs of
 * them). If an update renames a method, changes a field's type or turns an interface into a class,
 * the patch still applies, and the first time the changed member is reached the JVM throws a
 * {@code LinkageError} from inside the other mod's code. This walks the patch's own bytecode and
 * resolves each reference to the other mod the way the JVM would: the class exists, the member
 * exists there or in a supertype with that exact descriptor, a static call meets a static method,
 * an interface call meets an interface. {@code @Shadow} members are looked up in the target class.
 * A patch with any reference that does not resolve is left out.
 *
 * <p>Supertypes outside the other mod (Minecraft, libraries) are not followed: a member that may
 * come from one of them counts as present. No loader classes here, so it also runs outside the game.
 */
public final class ForeignLinks {

    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

    private ForeignLinks() {
    }

    /**
     * The first reference of the patch to the other mod (classes named {@code prefix...}) that does
     * not resolve, as text for a log line, or {@code null} when all do.
     *
     * @param mixinBytes  the patch's class file
     * @param targetClass internal name of the class the patch is applied to
     * @param classes     the other mod's class files by internal name ({@code null}: no such class)
     */
    public static String firstUnresolved(byte[] mixinBytes, String targetClass, String prefix, Function<String, byte[]> classes) {
        Resolver resolver = new Resolver(prefix, classes);
        ClassNode mixin = new ClassNode();
        new ClassReader(mixinBytes).accept(mixin, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (resolver.node(targetClass) == null) {
            return "class " + simple(targetClass);
        }
        for (FieldNode field : mixin.fields) {
            if (shadow(field.visibleAnnotations) || shadow(field.invisibleAnnotations)) {
                boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
                if (resolver.field(targetClass, field.name, field.desc, isStatic) == Found.NO) {
                    return "field " + simple(targetClass) + "." + field.name;
                }
            }
        }
        for (MethodNode method : mixin.methods) {
            if (shadow(method.visibleAnnotations) || shadow(method.invisibleAnnotations)) {
                boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
                if (resolver.method(targetClass, method.name, method.desc, isStatic) == Found.NO
                        && !resolver.hasObfuscatedTwin(targetClass, method.desc)) {
                    return "method " + simple(targetClass) + "." + method.name + method.desc;
                }
            }
        }
        for (MethodNode method : mixin.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                String missing = null;
                if (insn instanceof MethodInsnNode m && m.owner.startsWith(prefix)) {
                    missing = resolver.call(m.owner, m.name, m.desc, m.getOpcode() == Opcodes.INVOKESTATIC, m.itf);
                } else if (insn instanceof FieldInsnNode f && f.owner.startsWith(prefix)) {
                    boolean isStatic = f.getOpcode() == Opcodes.GETSTATIC || f.getOpcode() == Opcodes.PUTSTATIC;
                    if (resolver.node(f.owner) == null) {
                        missing = "class " + simple(f.owner);
                    } else if (resolver.field(f.owner, f.name, f.desc, isStatic) == Found.NO) {
                        missing = "field " + simple(f.owner) + "." + f.name;
                    }
                } else if (insn instanceof TypeInsnNode t) {
                    missing = resolver.type(Type.getObjectType(t.desc));
                } else if (insn instanceof LdcInsnNode l && l.cst instanceof Type type && type.getSort() != Type.METHOD) {
                    missing = resolver.type(type);
                } else if (insn instanceof InvokeDynamicInsnNode d) {
                    for (Object arg : d.bsmArgs) {
                        if (arg instanceof Handle h && h.getOwner().startsWith(prefix) && missing == null) {
                            boolean isStatic = h.getTag() == Opcodes.H_INVOKESTATIC;
                            missing = h.getTag() <= Opcodes.H_PUTSTATIC
                                    ? (resolver.field(h.getOwner(), h.getName(), h.getDesc(), h.getTag() == Opcodes.H_GETSTATIC || h.getTag() == Opcodes.H_PUTSTATIC) == Found.NO
                                            ? "field " + simple(h.getOwner()) + "." + h.getName() : null)
                                    : resolver.call(h.getOwner(), h.getName(), h.getDesc(), isStatic, h.isInterface());
                        }
                    }
                }
                if (missing != null) {
                    return missing;
                }
            }
        }
        return null;
    }

    private static boolean shadow(List<AnnotationNode> annotations) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (SHADOW.equals(annotation.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String simple(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private enum Found { YES, NO, UNKNOWN }

    private static final class Resolver {
        private final String prefix;
        private final Function<String, byte[]> classes;
        private final Map<String, ClassNode> nodes = new HashMap<>();
        private static final ClassNode ABSENT = new ClassNode();

        Resolver(String prefix, Function<String, byte[]> classes) {
            this.prefix = prefix;
            this.classes = classes;
        }

        /** The other mod's class, or null when it has no such class. */
        ClassNode node(String internalName) {
            ClassNode node = this.nodes.get(internalName);
            if (node == null) {
                byte[] bytes = this.classes.apply(internalName);
                if (bytes == null || bytes.length == 0) {
                    node = ABSENT;
                } else {
                    node = new ClassNode();
                    new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                this.nodes.put(internalName, node);
            }
            return node == ABSENT ? null : node;
        }

        String type(Type type) {
            while (type.getSort() == Type.ARRAY) {
                type = type.getElementType();
            }
            if (type.getSort() == Type.OBJECT && type.getInternalName().startsWith(this.prefix) && node(type.getInternalName()) == null) {
                return "class " + simple(type.getInternalName());
            }
            return null;
        }

        String call(String owner, String name, String desc, boolean isStatic, boolean itf) {
            ClassNode node = node(owner);
            if (node == null) {
                return "class " + simple(owner);
            }
            if (((node.access & Opcodes.ACC_INTERFACE) != 0) != itf) {
                return (itf ? "interface " : "class ") + simple(owner);
            }
            return method(owner, name, desc, isStatic) == Found.NO ? "method " + simple(owner) + "." + name + desc : null;
        }

        Found method(String owner, String name, String desc, boolean isStatic) {
            ClassNode node = node(owner);
            if (node == null) {
                // Gone, or outside the other mod: the JDK can be asked, the rest cannot be told from here.
                return owner.startsWith(this.prefix) ? Found.NO : owner.startsWith("java/") ? jdk(owner, name, desc, false) : Found.UNKNOWN;
            }
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(desc)) {
                    return ((method.access & Opcodes.ACC_STATIC) != 0) == isStatic ? Found.YES : Found.NO;
                }
            }
            Found best = Found.NO;
            if (node.superName != null) {
                best = method(node.superName, name, desc, isStatic);
            }
            for (String itf : node.interfaces) {
                if (best == Found.YES) {
                    break;
                }
                Found found = method(itf, name, desc, isStatic);
                if (found != Found.NO) {
                    best = found;
                }
            }
            return best;
        }

        Found field(String owner, String name, String desc, boolean isStatic) {
            ClassNode node = node(owner);
            if (node == null) {
                return owner.startsWith(this.prefix) ? Found.NO : owner.startsWith("java/") ? jdk(owner, name, desc, true) : Found.UNKNOWN;
            }
            for (FieldNode field : node.fields) {
                if (field.name.equals(name) && field.desc.equals(desc)) {
                    return ((field.access & Opcodes.ACC_STATIC) != 0) == isStatic ? Found.YES : Found.NO;
                }
            }
            Found best = Found.NO;
            if (node.superName != null) {
                best = field(node.superName, name, desc, isStatic);
            }
            for (String itf : node.interfaces) {
                if (best == Found.YES) {
                    break;
                }
                Found found = field(itf, name, desc, isStatic);
                if (found != Found.NO) {
                    best = found;
                }
            }
            return best;
        }

        /** Whether a JDK class or one of its supertypes has the member (a hierarchy that ends in Object has no others). */
        private static Found jdk(String owner, String name, String desc, boolean field) {
            try {
                Class<?> type = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                java.util.ArrayDeque<Class<?>> todo = new java.util.ArrayDeque<>();
                todo.add(type);
                while (!todo.isEmpty()) {
                    Class<?> at = todo.poll();
                    if (field) {
                        for (java.lang.reflect.Field f : at.getDeclaredFields()) {
                            if (f.getName().equals(name) && Type.getDescriptor(f.getType()).equals(desc)) {
                                return Found.YES;
                            }
                        }
                    } else {
                        for (java.lang.reflect.Method m : at.getDeclaredMethods()) {
                            if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
                                return Found.YES;
                            }
                        }
                    }
                    if (at.getSuperclass() != null) {
                        todo.add(at.getSuperclass());
                    }
                    todo.addAll(List.of(at.getInterfaces()));
                }
                return Found.NO;
            } catch (Throwable t) {
                return Found.UNKNOWN;
            }
        }

        /**
         * A shadowed method that overrides a Minecraft one keeps Minecraft's runtime name on Forge
         * ({@code m_1234_}); the patch's class file has the development name, which Mixin maps when
         * it applies. A method of the target with that descriptor and a runtime name stands for it.
         */
        boolean hasObfuscatedTwin(String owner, String desc) {
            ClassNode node = node(owner);
            if (node != null) {
                for (MethodNode method : node.methods) {
                    if (method.desc.equals(desc) && method.name.startsWith("m_") && method.name.endsWith("_")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
