package dev.superchunk.compat;

import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
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
 * ({@code JigsawPlacementMixin}, {@code StructureTemplateMixin}, {@code StructureTemplatePoolAccessor})
 * have <b>no</b> SuperChunk counterpart, so they layer on cleanly — bringing four optimizations
 * SuperChunk lacks: NBT block-list bounds-stripping, blocked-jigsaw skipping, fused
 * shuffle+selection-priority, and rotation-cache / pool de-duplication.
 *
 * <p><b>FORGE 1.20.1 PORT — the conflict was re-verified against SLO's own {@code 1.20.1} branch,
 * not assumed from the 1.21.1 argument.</b> On that branch
 * {@code telepathicgrunt.structure_layout_optimizer.mixins.JigsawPlacementPlacerMixin} still
 * {@code @Redirect}s {@code Shapes.joinUnoptimized(VoxelShape,VoxelShape,BooleanOp)} and still
 * {@code @WrapOperation}s {@code Shapes.joinIsNotEmpty(VoxelShape,VoxelShape,BooleanOp)}, both in
 * {@code tryPlacingChildren} — exactly the two instructions
 * {@code MixinJigsawPlacementPlacer} redirects on 1.20.1 (vanilla {@code JigsawPlacement}, lines
 * 297-298, has exactly one of each). {@code mod_id=structure_layout_optimizer} on that branch too,
 * so the id below is right for the Forge 1.20.1 build. Two 1.20.1-only details, neither of which
 * changes the decision: SLO's 1.20.1 mixin set has no {@code SinglePoolElementMixin} (that class is
 * 1.21.x-only — the four optimizations above are all still present, spread over the placer mixin
 * plus {@code StructureTemplateMixin}/{@code StructureTemplatePoolAccessor}), and its
 * {@code JigsawPlacementMixin} {@code @WrapOperation}s {@code Shapes.join} inside
 * {@code lambda$addPieces$1} where SuperChunk's {@code MixinJigsawPlacement} only {@code @Inject}s
 * into {@code addPieces} — a different instruction, so that pair would not have clashed on its own.
 * It stands down with the rest because the gate is per-package, matching neo.
 *
 * <p><b>Detection</b> is via {@link LoadingModList} — the same early API {@link ByePregenCompat}
 * and SuperChunk's bundled Lithium use — so it resolves at mixin-plugin time, before mixins apply.
 * (Valid on Forge 1.20.1: the loading mod list is populated during mod discovery, before mixin
 * config plugins load.) Never throws: any trouble is logged and the shipped default (our jigsaw
 * mixins on) is kept, which is correct whenever SLO is genuinely absent.
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

    /**
     * FORGE PORT of neo's single {@code getModFileById} probe: the mod list is read the way
     * {@code LithiumMixinPlugin}'s fork probe reads it — {@code getMods()} (the sorted
     * {@code ModInfo} list) FIRST, then the modId → mod-file map as belt-and-braces. A MISS here is
     * boot-fatal (our redirects would apply alongside SLO's), and a by-file probe was observed to
     * silently miss an installed mod on the sibling tree, so both are run; the scan costs nothing
     * and can only ever add detections.
     *
     * <p>The {@code getMods()} scan carries its OWN try/catch, exactly as {@code LithiumMixinPlugin}
     * does and for the same reason: it touches the CONCRETE fmlloader class
     * {@code moddiscovery.ModInfo}, so a module-graph/API surprise there must NOT swallow the
     * by-file probe below — that one is the production-QA-validated path and uses only public
     * {@code LoadingModList} surface. With one shared try, a {@code getMods()} throw would return
     * "SLO absent" and boot straight into the fatal double-redirect.
     */
    private static boolean detectInstalled() {
        try {
            LoadingModList lml = LoadingModList.get();
            if (lml != null) {
                try {
                    for (ModInfo mi : lml.getMods()) {
                        if (MOD_ID.equals(mi.getModId())) {
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                    // ModInfo not visible / API mismatch — fall through to the by-file lookup.
                }
                return lml.getModFileById(MOD_ID) != null;
            }
        } catch (Throwable t) {
            // If the mod list can't be read this early, fail toward shipped behavior (our mixins on).
            LOGGER.debug("[SuperChunk-Compat] Could not query the mod list for '{}' — assuming absent.", MOD_ID, t);
        }
        return false;
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
