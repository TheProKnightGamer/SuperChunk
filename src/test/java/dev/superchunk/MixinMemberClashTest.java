package dev.superchunk;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Regression check: no two mixins with a common target add a field (or a {@code @Unique} method)
 * of the same name.
 *
 * <p>Mixin 0.8.5 (Forge 1.20.1) prepares every mixin of a class against the class as it was, so it
 * sees no clash between two mixins' added fields and merges them into one: both mixins then read
 * and write the same field. Two kill switches named {@code SUPERCHUNK$ENABLED} became one switch
 * that way, and two patches' self-check state one state. Newer Mixin renames the second field, so
 * the fault shows on one loader only. Every added member therefore has a name of its own.
 */
public final class MixinMemberClashTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    public static void main(String[] args) throws Exception {
        Path marker = Paths.get(MixinMemberClashTest.class.getClassLoader().getResource("dev/superchunk/SuperChunk.class").toURI());
        Path root = marker.getParent().getParent().getParent();
        // target -> member -> the mixins that add it
        Map<String, Map<String, List<String>>> added = new TreeMap<>();
        int mixins = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                List<String> targets = targets(node);
                if (targets.isEmpty()) {
                    continue;
                }
                mixins++;
                String mixin = node.name.substring(node.name.lastIndexOf('/') + 1);
                for (FieldNode field : node.fields) {
                    if (!has(field.visibleAnnotations, "Shadow") && !has(field.invisibleAnnotations, "Shadow")) {
                        for (String target : targets) {
                            added.computeIfAbsent(target, k -> new TreeMap<>()).computeIfAbsent("field " + field.name, k -> new ArrayList<>()).add(mixin);
                        }
                    }
                }
                for (MethodNode method : node.methods) {
                    if ((method.access & Opcodes.ACC_SYNTHETIC) == 0
                            && (has(method.visibleAnnotations, "Unique") || has(method.invisibleAnnotations, "Unique"))) {
                        for (String target : targets) {
                            added.computeIfAbsent(target, k -> new TreeMap<>())
                                    .computeIfAbsent("method " + method.name + method.desc, k -> new ArrayList<>()).add(mixin);
                        }
                    }
                }
            }
        }
        List<String> clashes = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> target : added.entrySet()) {
            for (Map.Entry<String, List<String>> member : target.getValue().entrySet()) {
                if (member.getValue().size() > 1) {
                    clashes.add(target.getKey().replace('/', '.') + ": " + member.getKey() + " is added by " + member.getValue());
                }
            }
        }
        if (mixins < 100) {
            throw new AssertionError("only " + mixins + " mixin classes found under " + root + ": the scan is looking in the wrong place");
        }
        if (!clashes.isEmpty()) {
            throw new AssertionError("mixins with a common target add members of the same name:\n  " + String.join("\n  ", clashes));
        }
        System.out.println("MixinMemberClashTest: " + mixins + " mixins, no two with a common target add a member of the same name");
    }

    @SuppressWarnings("unchecked")
    private static List<String> targets(ClassNode node) {
        List<String> targets = new ArrayList<>();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.invisibleAnnotations != null) {
            annotations.addAll(node.invisibleAnnotations);
        }
        if (node.visibleAnnotations != null) {
            annotations.addAll(node.visibleAnnotations);
        }
        for (AnnotationNode annotation : annotations) {
            if (!MIXIN.equals(annotation.desc) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                Object value = annotation.values.get(i + 1);
                if ("targets".equals(annotation.values.get(i))) {
                    for (String name : (List<String>) value) {
                        targets.add(name.replace('.', '/'));
                    }
                } else if ("value".equals(annotation.values.get(i))) {
                    for (Type type : (List<Type>) value) {
                        targets.add(type.getInternalName());
                    }
                }
            }
        }
        return targets;
    }

    private static boolean has(List<AnnotationNode> annotations, String simpleName) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.endsWith("/" + simpleName + ";")) {
                    return true;
                }
            }
        }
        return false;
    }
}
