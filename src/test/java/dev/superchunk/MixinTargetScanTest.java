package dev.superchunk;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Checks that the post-apply scan tells SuperChunk-only classes from ones other mods mixed into. */
public final class MixinTargetScanTest {
    public static void main(String[] args) {
        String a = "test.scan.OnlyOurs", b = "test.scan.Foreign", c = "test.scan.ForeignInvisible";
        MixinTargetScan.record(a.replace('.', '/'), node(merged("dev.superchunk.mixin.MixinX", true), plain()));
        MixinTargetScan.record(b, node(merged("dev.superchunk.mixin.MixinX", true), merged("com.other.mod.MixinY", true)));
        MixinTargetScan.record(c, node(plain(), merged("org.example.MixinZ", false)));
        check(MixinTargetScan.foreignMixin(a) == null, "SuperChunk-only class reported foreign");
        check("com.other.mod.MixinY".equals(MixinTargetScan.foreignMixin(b)), "foreign handler missed");
        check("org.example.MixinZ".equals(MixinTargetScan.foreignMixin(c)), "foreign invisible annotation missed");
        check(MixinTargetScan.foreignMixin("test.scan.NeverScanned") != null, "unscanned class must fail closed");
        check(MixinTargetScan.foreignMixin(a, b) != null && MixinTargetScan.foreignMixin(a) == null, "multi-class check");

        // Read-only accessors do not count; setters do.
        MethodNode getter = foreignNamed("com.create.ponder.BiomeManagerAccessor", "catnip$getZoomSeed", "()J");
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "test/scan/Acc", "biomeZoomSeed", "J"));
        getter.instructions.add(new InsnNode(Opcodes.LRETURN));
        MethodNode invoker = foreignNamed("com.mod.Invoker", "mod$callSearch", "()Ljava/lang/Object;");
        invoker.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        invoker.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "test/scan/Acc", "search", "()Ljava/lang/Object;"));
        invoker.instructions.add(new InsnNode(Opcodes.ARETURN));
        MixinTargetScan.record("test/scan/Acc", node("test/scan/Acc", getter, invoker, plain()));
        check(MixinTargetScan.foreignMixin("test.scan.Acc") == null, "getter/invoker accessors counted as foreign");
        MethodNode setter = foreignNamed("com.lithostitched.Setter", "lithostitched$setIndex", "(Ljava/lang/Object;)V");
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setter.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, "test/scan/Set", "index", "Ljava/lang/Object;"));
        setter.instructions.add(new InsnNode(Opcodes.RETURN));
        MixinTargetScan.record("test/scan/Set", node("test/scan/Set", setter));
        check("com.lithostitched.Setter".equals(MixinTargetScan.foreignMixin("test.scan.Set")), "setter accessor missed");

        // Per method: an injector call, an overwrite, a body moved by @WrapMethod, a foreign @WrapMethod.
        String owner = "test/scan/Hooked";
        MethodNode handler = foreignNamed("io.wispforest.owo.mixin.Copenhagen", "handler$zzz000$owo$capture", "()V");
        MethodNode doPlace = new MethodNode(Opcodes.ACC_PUBLIC, "doPlace", "()Z", null, null);
        doPlace.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        doPlace.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, handler.name, handler.desc));
        doPlace.instructions.add(new InsnNode(Opcodes.ICONST_1));
        doPlace.instructions.add(new InsnNode(Opcodes.IRETURN));
        MethodNode overwritten = foreignNamed("com.mod.Overwrite", "getAll", "()V");
        MethodNode moved = new MethodNode(Opcodes.ACC_PRIVATE, "place$mixinextras$wrapped$3", "()V", null, null);
        moved.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        moved.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, handler.name, handler.desc));
        moved.instructions.add(new InsnNode(Opcodes.RETURN));
        MethodNode wrapHandler = foreignNamed("com.mod.Wrap", "wrapMethod$zfc000$mod$wrap",
                "(ILcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z");
        MethodNode check = new MethodNode(Opcodes.ACC_PUBLIC, "check", "(I)Z", null, null);
        MethodNode untouched = new MethodNode(Opcodes.ACC_PUBLIC, "untouched", "()V", null, null);
        MixinTargetScan.record(owner, node(owner, handler, doPlace, overwritten, moved, wrapHandler, check, untouched));
        check("io.wispforest.owo.mixin.Copenhagen".equals(MixinTargetScan.foreignHook("test.scan.Hooked#doPlace")), "injector call missed");
        check("com.mod.Overwrite".equals(MixinTargetScan.foreignHook("test.scan.Hooked#getAll")), "overwrite missed");
        check("io.wispforest.owo.mixin.Copenhagen".equals(MixinTargetScan.foreignHook("test.scan.Hooked#place")), "wrapped body not folded");
        check("com.mod.Wrap".equals(MixinTargetScan.foreignHook("test.scan.Hooked#check")), "foreign @WrapMethod missed");
        check(MixinTargetScan.foreignHook("test.scan.Hooked#untouched") == null, "untouched method reported hooked");
        check(MixinTargetScan.foreignHook("test.scan.OnlyOurs#plain") == null, "clean class method reported hooked");
        check(MixinTargetScan.foreignHook("test.scan.NeverScanned#x").startsWith("<"), "unscanned class must fail closed per method");
        System.out.println("MixinTargetScan: clean, foreign (visible and invisible), unscanned, accessor and per-method cases passed");
    }

    private static MethodNode foreignNamed(String mixin, String name, String desc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        annotation.values = new ArrayList<>(List.of("mixin", mixin, "priority", 1000, "sessionId", "x"));
        method.visibleAnnotations = new ArrayList<>(List.of(annotation));
        return method;
    }

    private static ClassNode node(String name, MethodNode... methods) {
        ClassNode node = node(methods);
        node.name = name;
        return node;
    }

    private static ClassNode node(MethodNode... methods) {
        ClassNode node = new ClassNode();
        node.methods = new ArrayList<>(List.of(methods));
        return node;
    }

    private static MethodNode plain() {
        return new MethodNode(Opcodes.ACC_PUBLIC, "plain", "()V", null, null);
    }

    private static MethodNode merged(String mixin, boolean visible) {
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "handler$zz0$" + mixin.hashCode(), "()V", null, null);
        AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        annotation.values = new ArrayList<>(List.of("mixin", mixin, "priority", 1000, "sessionId", "x"));
        if (visible) {
            method.visibleAnnotations = new ArrayList<>(List.of(annotation));
        } else {
            method.invisibleAnnotations = new ArrayList<>(List.of(annotation));
        }
        return method;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
