package dev.superchunk.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interop with <b>YUNG's Better Caves</b> ({@code bettercaves}) — a cave-generation mod that
 * post-processes aquifer output so its "liquid regions" decide which fluid an aquifer places.
 *
 * <p><b>The hard conflict.</b> Better Caves' {@code aquiferfix.AquiferMixin} targets
 * {@code Aquifer$NoiseBasedAquifer} and hangs a <i>cancellable</i>
 * {@code @Inject(method = "computeSubstance", at = @At("RETURN"))} off it: at every return it takes
 * the computed {@link net.minecraft.world.level.block.state.BlockState}, looks up the per-chunk
 * liquid-region data, and rewrites the block at or below that chunk's liquid altitude. SuperChunk
 * {@code @Overwrite}s that same method — see
 * {@code opts.worldgen.vanilla.mixin.aquifer.MixinAquiferSamplerImpl#computeSubstance} (C2ME's
 * split-for-C2 rewrite, plus SuperChunk's adaptive air-skip early return) — and {@code @Overwrite}s
 * {@code getAquiferStatus} alongside it.
 *
 * <p>An {@code @Overwrite} of a method another mod injects into has no <i>guaranteed</i> outcome:
 * which one wins is decided by mixin apply order. If ours applies last the overwrite replaces the
 * body Better Caves injected into and its liquid fix silently disappears; if theirs applies last,
 * their per-block callback runs against a body with different return structure and reachability
 * than the one it was written against — including our air-skip early return.
 *
 * <p><b>DEFAULT OFF — this is a probe, not a shipped fix.</b> The pairing above looks alarming on
 * paper and is <i>not</i> what breaks in practice, so standing our mixin down by default would cost
 * every Better Caves user the aquifer optimisation to buy nothing. This class exists because Better
 * Caves was the prime suspect for a modpack's world-generation failure across two rounds of
 * investigation; the actual culprit was <b>Better Chunk Loading</b> tripping C2ME's deliberate
 * null-the-vanilla-futures tripwire in {@code chunksystem.mixin.MixinChunkHolder}, with the aquifer
 * entirely uninvolved (see {@code compatibility.txt}). Three pieces of evidence:
 * <ul>
 *   <li><b>Upstream C2ME does the same thing, harder.</b> {@code C2ME-fabric/c2me-opts-worldgen-
 *       vanilla/.../mixin/aquifer/MixinAquiferSamplerImpl} {@code @Overwrite}s {@code apply}
 *       (Mojmap {@code computeSubstance}) plus {@code getWaterLevel}, {@code getFluidBlockY},
 *       {@code calculateDensity} and {@code getFluidBlockState} — a strict superset of ours. A
 *       reporter running stock C2ME + Lithium alongside Better Caves and Streams Reflowing has a
 *       working game, so this exact collision is survivable.</li>
 *   <li><b>Measured, on the bench harness.</b> With the conflict live, a 16641-chunk pregen
 *       (SuperChunk + Better Caves + YUNG's API + Streams Reflowing + Distant Horizons) completed in
 *       47s. {@code -Dmixin.debug.export} + {@code javap} on the merged
 *       {@code Aquifer$NoiseBasedAquifer} confirms Better Caves applies LAST there — its
 *       {@code handler$bbf000$bettercaves$fixAquiferLiquids} sits inside our overwritten body, the
 *       theirs-last case — and nothing stalls.</li>
 *   <li><b>The real repro exonerated it.</b> Installing SuperChunk + Better Caves + Streams
 *       Reflowing + Distant Horizons into the reporting pack (321 mods, 2026-08-13) reproduced the
 *       failure as an NPE in the chunk pipeline — {@code ChunkHolder.addSaveDependency} on a nulled
 *       {@code saveSync}, called from Better Chunk Loading — with no aquifer frame in the stack.</li>
 * </ul>
 *
 * <p><b>What it does when enabled.</b> {@code -Dsuperchunk.compat.betterCaves=true} makes
 * {@link #applyOwnAquiferMixin()} return {@code false}, so {@code opts.worldgen.vanilla.MixinPlugin
 * #shouldApplyMixin} skips our whole {@code mixin.aquifer.*} package.
 * {@code Aquifer$NoiseBasedAquifer} then stays vanilla and Better Caves injects into exactly the
 * code it was written against. The cost is the aquifer optimisation only — C2ME's method split, the
 * cross-chunk {@code ScAquiferCellCache}, the refresh/ceiling memos, the adaptive air-skip, and (on
 * the GPU path) the compact-ids decide chain, which degrades to corner-only because the aquifer no
 * longer implements {@code ScCompactAuxSource}. Terrain is correct either way. Keep this flag as the
 * one-line way to ask "is the aquifer surface implicated at all?" without rebuilding; if it ever
 * answers yes for a real report, flip the default here and say so.
 *
 * <p>Note the runtime kill-switch {@code -Dsuperchunk.worldgen.aquiferCellCache=false} does
 * <i>not</i> substitute for this: it only turns the caches into pass-throughs at run time — the
 * mixin still applies, so the {@code @Overwrite} of {@code computeSubstance} is still there. The
 * stand-down must happen at LOAD time.
 *
 * <p><b>Not the cause, for the record.</b> Better Caves also installs a thread-scoped
 * {@code AquiferContext} by {@code @WrapOperation}-ing the {@code Aquifer.create(...)} call inside
 * {@code NoiseChunk.<init>} ({@code aquiferfix.NoiseChunkMixin}), which its {@code AquiferMixin}
 * {@code <init>} hook reads back. SuperChunk's own {@code NoiseChunk} mixins only {@code @Inject} /
 * {@code @ModifyArg} — none rewrites that constructor — so the context plumbing survives us intact.
 * A {@code "AquiferContext is null"} line in a log alongside SuperChunk points at some other mod
 * building an aquifer off that path, not at this conflict. Measured, not assumed: on a 5-mod
 * dedicated server (SuperChunk + Better Caves + YUNG's API + Streams Reflowing + Chunky, 2026-08-11)
 * a 2401-chunk pregen logged those warnings at the same rate with this stand-down active (24) as
 * with it forced off (22) — they track Streams Reflowing's own {@code streamsreflowing-sampler}
 * thread building aquifers off the wrapped path, and are unaffected by whether our mixin is applied.
 *
 * <p><b>Detection</b> is via {@link LoadingModList} — the same early API {@link ByePregenCompat} and
 * {@link StructureLayoutOptimizerCompat} use — so it resolves at mixin-plugin time, before mixins
 * apply. Never throws: any trouble is logged and the shipped default (our aquifer mixin on) is kept,
 * which is correct whenever Better Caves is genuinely absent.
 */
public final class BetterCavesCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    /** YUNG's Better Caves' mod id (also the name of the logger it writes under). */
    public static final String MOD_ID = "bettercaves";

    /**
     * Master switch for this stand-down — <b>default {@code false}</b>: our aquifer mixin applies
     * normally alongside Better Caves, because upstream C2ME overwrites the same (and more) and is
     * known to work with it. Set {@code true} to stand ours down and test whether the aquifer
     * surface is implicated in a report. See the class javadoc for the evidence.
     */
    private static final boolean COMPAT_ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.compat.betterCaves", "false"));

    /** True when Better Caves is on the mod list. Resolved once, defensively. */
    public static final boolean INSTALLED = detectInstalled();

    /** One-shot guard so the stand-down is logged exactly once (shouldApplyMixin is called per mixin). */
    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    private BetterCavesCompat() {
    }

    private static boolean detectInstalled() {
        try {
            return LoadingModList.get().getModFileById(MOD_ID) != null;
        } catch (Throwable t) {
            // If the mod list can't be read this early, fail toward shipped behavior (our mixin on).
            LOGGER.debug("[SuperChunk-Compat] Could not query the mod list for '{}' — assuming absent.", MOD_ID, t);
            return false;
        }
    }

    /**
     * Whether SuperChunk's own aquifer mixin ({@code opts.worldgen.vanilla.mixin.aquifer.*}) should
     * be applied. Normally {@code true} even alongside Better Caves; returns {@code false} only when
     * Better Caves is installed AND {@code -Dsuperchunk.compat.betterCaves=true} was passed, so
     * {@code Aquifer$NoiseBasedAquifer} stays vanilla and Better Caves' liquid-region injection lands
     * on the code it was written against. Logs a single explanatory boot line when it stands down.
     */
    public static boolean applyOwnAquiferMixin() {
        boolean standDown = INSTALLED && COMPAT_ENABLED;
        if (standDown && LOGGED.compareAndSet(false, true)) {
            LOGGER.info("[SuperChunk-Compat] bettercaves detected AND -Dsuperchunk.compat.betterCaves=true — "
                    + "standing down SuperChunk's aquifer mixin (Aquifer$NoiseBasedAquifer computeSubstance / "
                    + "getAquiferStatus) so Better Caves owns aquifer output. This is a DIAGNOSTIC probe, not "
                    + "the default: upstream C2ME overwrites the same methods and more, and works with Better "
                    + "Caves, so this collision is survivable. It costs the aquifer optimisation (cell cache, "
                    + "refresh/ceiling memos, adaptive air-skip) and drops the GPU compact-ids decide chain to "
                    + "corner-only; terrain is correct either way. Remove the flag to run normally.");
        }
        return !standDown;
    }
}
