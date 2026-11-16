package dev.superchunk.compat;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Regression checks for the guards on patches of another mod's code: the method fingerprints
 * ({@link ForeignBytecode}), the link check ({@link ForeignLinks}) and the version comparison of
 * the Distant Horizons gate. The other mod here is a few generated classes.
 */
public final class ForeignCodeTest {

    private static final String THING = "mod/pkg/Thing";
    private static final String BASE = "mod/pkg/Base";
    private static final String IFACE = "mod/pkg/Iface";
    private static final String OTHER = "mod/pkg/Other";
    private static final String MIXIN = "test/MixinThing";
    private static int checks;

    public static void main(String[] args) {
        fingerprints();
        links();
        versions();
        System.out.println("ForeignCodeTest passed " + checks + " checks");
    }

    // ---- fingerprints

    private static void fingerprints() {
        String same = hashOf(work(m -> { }));
        check(same.equals(hashOf(work(m -> { }))), "the same code hashes the same");

        // work(x) = helper(x) * 2, computed in another order
        byte[] reordered = thing(cw -> {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "work", "(I)I", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.ICONST_2);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, THING, "helper", "(I)I", false);
            m.visitInsn(Opcodes.IMUL);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(2, 2);
            m.visitEnd();
        });
        check(!same.equals(hashOf(reordered)), "reordered code has another hash");
        String effects = effectsOf(work(m -> { }));
        check(effects.equals(effectsOf(reordered)), "reordered code has the same effects");

        byte[] nop = work(m -> m.visitInsn(Opcodes.NOP));
        check(!same.equals(hashOf(nop)) && effects.equals(effectsOf(nop)), "a NOP changes the hash, not the effects");

        byte[] jdkCall = work(m -> {
            m.visitInsn(Opcodes.ICONST_1);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false);
            m.visitInsn(Opcodes.POP);
        });
        check(effects.equals(effectsOf(jdkCall)), "a JDK call is not an effect");

        byte[] concat = work(m -> {
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitInvokeDynamicInsn("makeConcatWithConstants", "(I)Ljava/lang/String;",
                    new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                            false), "x=\u0001");
            m.visitInsn(Opcodes.POP);
        });
        check(effects.equals(effectsOf(concat)), "a compiler's string concatenation is not an effect");

        byte[] newCall = work(m -> m.visitMethodInsn(Opcodes.INVOKESTATIC, OTHER, "log", "()V", false));
        check(!effects.equals(effectsOf(newCall)), "a new call into the mod is an effect");

        byte[] write = work(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.ICONST_0);
            m.visitFieldInsn(Opcodes.PUTFIELD, THING, "count", "I");
        });
        check(!effects.equals(effectsOf(write)), "a field write is an effect");

        byte[] read = work(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, THING, "count", "I");
            m.visitInsn(Opcodes.POP);
        });
        check(effects.equals(effectsOf(read)), "a field read is not an effect");

        byte[] lock = work(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.MONITORENTER);
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.MONITOREXIT);
        });
        check(!effects.equals(effectsOf(lock)), "taking a monitor is an effect");

        check(ForeignBytecode.find(work(m -> { }), "work", "(J)I") == null, "another descriptor is another method");
        check(ForeignBytecode.find(work(m -> { }), "gone", null) == null, "a missing method is not found");
    }

    /** {@code Thing} with {@code work(x) = helper(x) * 2} and {@code prefix} emitted first. */
    private static byte[] work(Consumer<MethodVisitor> prefix) {
        return thing(cw -> {
            MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "work", "(I)I", null, null);
            m.visitCode();
            prefix.accept(m);
            m.visitVarInsn(Opcodes.ILOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, THING, "helper", "(I)I", false);
            m.visitInsn(Opcodes.ICONST_2);
            m.visitInsn(Opcodes.IMUL);
            m.visitInsn(Opcodes.IRETURN);
            m.visitMaxs(4, 2);
            m.visitEnd();
        });
    }

    private static byte[] thing(Consumer<ClassWriter> members) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, THING, null, BASE, new String[0]);
        cw.visitField(Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd();
        members.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String hashOf(byte[] bytes) {
        return ForeignBytecode.hash(bytes, "work", "(I)I");
    }

    private static String effectsOf(byte[] bytes) {
        MethodNode method = ForeignBytecode.find(bytes, "work", "(I)I");
        return ForeignBytecode.effects(method);
    }

    // ---- links

    /** How the pretend mod differs from what the patch was compiled against. */
    private enum Change { NONE, HELPER_RENAMED, HELPER_INSTANCE, COUNT_IS_LONG, IFACE_IS_CLASS, OTHER_GONE, INHERITED_GONE, EXTENDS_LIST }

    private static void links() {
        check(unresolved(Change.NONE, false) == null, "every reference resolves against the mod it was compiled for");
        check("method Thing.helper(I)I".equals(unresolved(Change.HELPER_RENAMED, false)), "a renamed method is reported");
        check("method Thing.helper(I)I".equals(unresolved(Change.HELPER_INSTANCE, false)), "a static method made an instance method is reported");
        check("field Thing.count".equals(unresolved(Change.COUNT_IS_LONG, false)), "a shadowed field of another type is reported");
        check("interface Iface".equals(unresolved(Change.IFACE_IS_CLASS, false)), "an interface made a class is reported");
        check("class Other".equals(unresolved(Change.OTHER_GONE, false)), "a missing class is reported");
        check("method Thing.inherited()V".equals(unresolved(Change.INHERITED_GONE, false)), "a method gone from the mod's superclass is reported");
        check(unresolved(Change.EXTENDS_LIST, true) == null, "a method inherited from a JDK class resolves");
        check("method Thing.sizeOfIt()I".equals(unresolved(Change.EXTENDS_LIST, false)),
                "a method that neither the mod nor its JDK superclass has is reported");
    }

    private static String unresolved(Change change, boolean callListSize) {
        Map<String, byte[]> mod = new HashMap<>();
        boolean list = change == Change.EXTENDS_LIST;
        // Base: inherited()V
        ClassWriter base = new ClassWriter(0);
        base.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, BASE, null, "java/lang/Object", new String[0]);
        if (change != Change.INHERITED_GONE) {
            base.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "inherited", "()V", null, null).visitEnd();
        }
        base.visitEnd();
        mod.put(BASE, base.toByteArray());
        // Thing
        ClassWriter thing = new ClassWriter(0);
        thing.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, THING, null, list ? "java/util/ArrayList" : BASE, new String[0]);
        thing.visitField(Opcodes.ACC_PUBLIC, "count", change == Change.COUNT_IS_LONG ? "J" : "I", null, null).visitEnd();
        thing.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | (change == Change.HELPER_INSTANCE ? 0 : Opcodes.ACC_STATIC) & ~(change == Change.HELPER_INSTANCE ? 0 : Opcodes.ACC_ABSTRACT),
                change == Change.HELPER_RENAMED ? "helperTwo" : "helper", "(I)I", null, null).visitEnd();
        if (list) {
            thing.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "inherited", "()V", null, null).visitEnd();
        }
        thing.visitEnd();
        mod.put(THING, thing.toByteArray());
        // Iface: run()V
        ClassWriter iface = new ClassWriter(0);
        iface.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | (change == Change.IFACE_IS_CLASS ? 0 : Opcodes.ACC_INTERFACE), IFACE, null,
                "java/lang/Object", new String[0]);
        iface.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null).visitEnd();
        iface.visitEnd();
        mod.put(IFACE, iface.toByteArray());
        if (change != Change.OTHER_GONE) {
            ClassWriter other = new ClassWriter(0);
            other.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OTHER, null, "java/lang/Object", new String[0]);
            other.visitEnd();
            mod.put(OTHER, other.toByteArray());
        }

        // The patch: shadows Thing.count:I; calls Thing.helper, Thing.inherited, Iface.run; casts to Other.
        ClassWriter mixin = new ClassWriter(0);
        mixin.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, MIXIN, null, "java/lang/Object", new String[0]);
        mixin.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null)
                .visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
        MethodVisitor m = mixin.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "patch", "(L" + THING + ";L" + IFACE + ";Ljava/lang/Object;)V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ICONST_1);
        m.visitMethodInsn(Opcodes.INVOKESTATIC, THING, "helper", "(I)I", false);
        m.visitInsn(Opcodes.POP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THING, "inherited", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 1);
        m.visitMethodInsn(Opcodes.INVOKEINTERFACE, IFACE, "run", "()V", true);
        m.visitVarInsn(Opcodes.ALOAD, 2);
        m.visitTypeInsn(Opcodes.CHECKCAST, OTHER);
        m.visitInsn(Opcodes.POP);
        if (list) {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THING, callListSize ? "size" : "sizeOfIt", "()I", false);
            m.visitInsn(Opcodes.POP);
        }
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(2, 3);
        m.visitEnd();
        mixin.visitEnd();
        return ForeignLinks.firstUnresolved(mixin.toByteArray(), THING, "mod/pkg/", mod::get);
    }

    // ---- versions

    private static void versions() {
        check(!DhFastPaths.newerThanChecked("3.3.3"), "the newest checked version is not newer");
        check(!DhFastPaths.newerThanChecked("3.2.0-b"), "an older beta is not newer");
        check(!DhFastPaths.newerThanChecked("2.4.5-b"), "an older major is not newer");
        check(DhFastPaths.newerThanChecked("3.3.4"), "a later patch is newer");
        check(DhFastPaths.newerThanChecked("3.4.0-b"), "a later minor is newer");
        check(DhFastPaths.newerThanChecked("4.0"), "a later major is newer");
        check(DhFastPaths.newerThanChecked("(unknown version)"), "an unreadable version counts as newer");
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            throw new AssertionError("FAILED: " + what);
        }
    }
}
