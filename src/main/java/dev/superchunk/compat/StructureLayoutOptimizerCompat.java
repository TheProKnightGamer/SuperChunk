package dev.superchunk.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interop with <b>Structure Layout Optimizer</b> ({@code structure_layout_optimizer}, by
 * TelepathicGrunt) — a mod that optimizes jigsaw-structure assembly and NBT-piece placement.
 *
 * <p><b>The hard conflict.</b> SuperChunk already carries a jigsaw-placement optimization
 * (the {@code mixin.jigsaw.*} pair — {@code MixinJigsawPlacement} / {@code MixinJigsawPlacementPlacer},
 * backed by {@link dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.jigsaw.JigsawFreeShape}),
 * a boundary+octree replacement for the {@code VoxelShape} "free space" tracker in
 * {@code JigsawPlacement$Placer.tryPlacingChildren}. That technique was itself <i>inspired by</i>
 * Structure Layout Optimizer, so the two are functionally redundant — and, critically, they cannot
 * be applied together:
 * <ul>
 *   <li>both {@code @Redirect} the <b>same</b> {@code Shapes.joinUnoptimized(...)} invocation in
 *       {@code tryPlacingChildren} — two redirects on one instruction is a fatal
 *       {@code InvalidInjectionException} at mixin-apply time, and</li>
 *   <li>SuperChunk {@code @Redirect}s {@code Shapes.joinIsNotEmpty(...)} while SLO
 *       {@code @WrapOperation}s the same call — another mutually-exclusive clash.</li>
 * </ul>
 * So a server with both mods installed would crash on boot. Note the runtime kill-switch
 * {@code -Dsuperchunk.worldgen.jigsawOctree=false} does <i>not</i> avoid this: it only turns
 * SuperChunk's redirects into pass-throughs at run time — the mixins still <i>apply</i>, so the
 * apply-time conflict remains. The stand-down must happen at LOAD time.
 *
 * <p><b>What we do.</b> When {@code structure_layout_optimizer} is present we disable SuperChunk's
 * own {@code mixin.jigsaw.*} mixins (via {@link #applyOwnJigsawOctree()}, consulted from
 * {@code opts.worldgen.vanilla.MixinPlugin#shouldApplyMixin}) and let SLO own jigsaw placement.
 * Nothing is lost and much is gained: SLO's octree is equivalent to ours, and its other mixins
 * ({@code SinglePoolElementMixin}, {@code StructureTemplateMixin}, {@code StructureTemplatePoolAccessor})
 * have <b>no</b> SuperChunk counterpart, so they layer on cleanly — bringing four optimizations
 * SuperChunk lacks: NBT block-list bounds-stripping, blocked-jigsaw skipping, fused
 * shuffle+selection-priority, and rotation-cache / pool de-duplication.
 *
 * <p><b>Detection</b> is via {@link LoadingModList} — the same early API {@link ByePregenCompat}
 * and SuperChunk's bundled Lithium use — so it resolves at mixin-plugin time, before mixins apply.
 * Never throws: any trouble is logged and the shipped default (our jigsaw mixins on) is kept, which
 * is correct whenever SLO is genuinely absent.
 *
 * <p><b>Escape hatch.</b> {@code -Dsuperchunk.compat.structureLayoutOptimizer=false} forces
 * SuperChunk's jigsaw mixins back on even when SLO is present. This is a bisection/debug knob only —
 * it re-introduces the fatal apply-time conflict described above and will crash the boot.
 */
public final class StructureLayoutOptimizerCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    /** Structure Layout Optimizer's mod id (namespace of its assets/data and mixin package). */
    public static final String MOD_ID = "structure_layout_optimizer";

    /**
     * Master switch for this stand-down (default {@code true}). Setting it {@code false} forces our
     * jigsaw mixins to apply even alongside SLO — which fails at mixin apply. Debug only.
     */
    private static final boolean COMPAT_ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.compat.structureLayoutOptimizer", "true"));

    /** True when Structure Layout Optimizer is on the mod list. Resolved once, defensively. */
    public static final boolean INSTALLED = detectInstalled();

    /** One-shot guard so the stand-down is logged exactly once (shouldApplyMixin is called per mixin). */
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    private StructureLayoutOptimizerCompat() {
    }

    private static boolean detectInstalled() {
        try {
            return LoadingModList.get().getModFileById(MOD_ID) != null;
        } catch (Throwable t) {
            // If the mod list can't be read this early, fail toward shipped behavior (our mixins on).
            LOGGER.debug("[SuperChunk-Compat] Could not query the mod list for '{}' — assuming absent.", MOD_ID, t);
            return false;
        }
    }

    /**
     * Whether SuperChunk's own jigsaw-octree mixins ({@code opts.worldgen.vanilla.mixin.jigsaw.*})
     * should be applied. Returns {@code false} — standing them down — when Structure Layout Optimizer
     * is installed and this compat is enabled, so SLO owns jigsaw placement and the fatal
     * apply-time redirect conflict is avoided. Logs a single explanatory boot line the first time it
     * stands down.
     */
    public static boolean applyOwnJigsawOctree() {
        boolean standDown = INSTALLED && COMPAT_ENABLED;
        if (standDown && LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[SuperChunk-Compat] structure_layout_optimizer detected — standing down SuperChunk's "
                    + "jigsaw-octree mixins (JigsawPlacement / JigsawPlacement$Placer) so SLO owns jigsaw "
                    + "placement. They redirect the same Shapes.joinUnoptimized / joinIsNotEmpty calls in "
                    + "tryPlacingChildren and cannot coexist; SLO's octree is equivalent and additionally brings "
                    + "NBT-bounds stripping, blocked-jigsaw skipping, fused shuffle+priority, rotation-caching and "
                    + "pool de-duplication. Force ours back on (WILL crash) with "
                    + "-Dsuperchunk.compat.structureLayoutOptimizer=false.");
        }
        return !standDown;
    }
}
