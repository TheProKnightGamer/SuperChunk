package dev.superchunk.compat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Fingerprints of methods in another mod's classes, for patches that replace or depend on exactly
 * one version of that code.
 *
 * <p>A mixin that overwrites another mod's method, or skips work only because of what that method
 * does today, is correct for the version it was checked against and nothing else. Mod ids and
 * version strings are a weak guard (a patch release can change the method). This hashes the
 * method's instructions instead: opcodes and operands, with labels numbered by position and no
 * line numbers or frames, so a rebuild of the same source matches and any change to the code does
 * not. A plugin compares the hash with the ones recorded for the checked versions and leaves the
 * patch out on a mismatch.
 *
 * <p>No loader classes here, so the same code can print the hashes of a jar outside the game.
 */
public final class ForeignBytecode {

    private ForeignBytecode() {
    }

    /**
     * Hash of one method of a class file, or {@code null} if the class has no such method.
     * Every method of that name matches when {@code desc} is null and there is exactly one.
     */
    public static String hash(byte[] classBytes, String methodName, String desc) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals(methodName) && (desc == null || method.desc.equals(desc))) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found == null ? null : hash(found);
    }

    static String hash(MethodNode method) {
        StringBuilder text = new StringBuilder(4096);
        text.append(method.access).append(' ').append(method.desc).append('\n');
        InsnList insns = method.instructions;
        Map<LabelNode, Integer> labels = new HashMap<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                labels.put(label, labels.size());
            }
        }
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                text.append('L').append(labels.get(label)).append('\n');
                continue;
            }
            if (insn.getOpcode() < 0) {
                continue;
            }
            text.append(insn.getOpcode());
            if (insn instanceof FieldInsnNode f) {
                text.append(' ').append(f.owner).append('.').append(f.name).append(':').append(f.desc);
            } else if (insn instanceof MethodInsnNode m) {
                text.append(' ').append(m.owner).append('.').append(m.name).append(m.desc).append(m.itf ? " itf" : "");
            } else if (insn instanceof TypeInsnNode t) {
                text.append(' ').append(t.desc);
            } else if (insn instanceof LdcInsnNode l) {
                text.append(' ').append(l.cst.getClass().getSimpleName()).append(':').append(l.cst);
            } else if (insn instanceof IntInsnNode i) {
                text.append(' ').append(i.operand);
            } else if (insn instanceof VarInsnNode v) {
                text.append(' ').append(v.var);
            } else if (insn instanceof IincInsnNode i) {
                text.append(' ').append(i.var).append(' ').append(i.incr);
            } else if (insn instanceof JumpInsnNode j) {
                text.append(" L").append(labels.get(j.label));
            } else if (insn instanceof TableSwitchInsnNode t) {
                text.append(' ').append(t.min).append(' ').append(t.max).append(" L").append(labels.get(t.dflt));
                t.labels.forEach(label -> text.append(" L").append(labels.get(label)));
            } else if (insn instanceof LookupSwitchInsnNode l) {
                text.append(' ').append(l.keys).append(" L").append(labels.get(l.dflt));
                l.labels.forEach(label -> text.append(" L").append(labels.get(label)));
            } else if (insn instanceof MultiANewArrayInsnNode m) {
                text.append(' ').append(m.desc).append(' ').append(m.dims);
            } else if (insn instanceof InvokeDynamicInsnNode d) {
                text.append(' ').append(d.name).append(d.desc).append(' ').append(handle(d.bsm));
                for (Object arg : d.bsmArgs) {
                    text.append(' ').append(arg instanceof Handle h ? handle(h) : String.valueOf(arg));
                }
            }
            text.append('\n');
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            text.append("try L").append(labels.get(block.start)).append(" L").append(labels.get(block.end))
                    .append(" L").append(labels.get(block.handler)).append(' ').append(block.type).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (byte b : Arrays.copyOf(digest, 8)) {
                hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String handle(Handle h) {
        return h.getTag() + ":" + h.getOwner() + "." + h.getName() + h.getDesc();
    }

    /**
     * Prints the hashes of methods in a jar: {@code <jar> <class> <method>[:<desc>]...}.
     * Used to record the hashes of a newly checked version.
     */
    public static void main(String[] args) throws Exception {
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(args[0])) {
            byte[] bytes = jar.getInputStream(jar.getEntry(args[1].replace('.', '/') + ".class")).readAllBytes();
            for (int i = 2; i < args.length; i++) {
                int colon = args[i].indexOf(':');
                String name = colon < 0 ? args[i] : args[i].substring(0, colon);
                String desc = colon < 0 ? null : args[i].substring(colon + 1);
                System.out.println(args[i] + " " + hash(bytes, name, desc));
            }
        }
    }
}
