package dev.superchunk.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk: <b>crash guard for the ghost-mushroom suppression scan</b> in
 * {@code MixinServerAccessibleChunkSending.upgradeToThis}.
 *
 * <h2>The scan</h2>
 * C2ME works around MC-276863 (mushrooms surviving in chunks the client sees before
 * post-processing) by walking the chunk's post-processing lists at CHUNK_SENDING, calling
 * {@code BlockState.canSurvive} on every vanilla brown/red mushroom, and deleting the ones that
 * fail. Upstream C2ME ships that off ({@code chunkSystem.suppressGhostMushrooms}); SuperChunk
 * defaults it <b>on</b>, because {@code player.sendAtChunkSending} exposes the outer no-tick ring
 * to clients before post-processing runs, which is exactly when the bug shows.
 *
 * <h2>Why it can explode</h2>
 * The {@code LevelReader} handed to {@code canSurvive} is a {@code WorldGenRegion} built on
 * {@code ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)}. That step's
 * {@code directDependencies()} is a single entry ({@code [minecraft:spawn]}), so the region's
 * legal read radius is <b>zero</b> — {@code WorldGenRegion.getChunk} throws
 * {@code ReportedException("Requested chunk unavailable during world generation")} for anything
 * outside the centre chunk. Vanilla {@code MushroomBlock.canSurvive} only ever reads
 * {@code pos.below()}, so vanilla is always inside that radius and upstream never trips.
 *
 * <p>Mods do not have to stay inside it. Twilight Forest 4.8.3345 ASM-hooks
 * {@code MushroomBlock.canSurvive} ({@code twilightforest.asmhooks.BlockHooks#modifySoilDecision-
 * ForMushroomBlockSurvivability}); read from the shipped bytecode, when the soil decision is
 * DEFAULT it scans the eight horizontal neighbours at {@code pos.offset(x, -1, z)} looking for a
 * twilight portal block. For a mushroom on a chunk-border column that reaches one chunk out, which
 * is a hard throw here. And it is not a rare shape: {@code Blocks.BROWN_MUSHROOM} and
 * {@code RED_MUSHROOM} both declare {@code hasPostProcess(always)}, so <b>every</b> worldgen-placed
 * mushroom lands in the post-processing list and gets scanned. The throw propagates out of
 * {@code upgradeToThis}, and {@code TheChunkSystem.handleTransactionException} turns it into a
 * chunk IO error report plus {@code MARK_BROKEN}. Measured on a two-mod server (SuperChunk 0.3.0 +
 * Twilight Forest, nothing else): it broke a spawn chunk and the world load never reached "Done".
 *
 * <h2>The guard</h2>
 * Any failure of a {@code canSurvive} call is treated as <b>"it survives"</b>, i.e. the block is
 * left alone. That is deliberately the conservative direction: this scan exists only to
 * <i>delete</i> blocks, so a check we could not evaluate must never be allowed to delete
 * anything. The worst case is the pre-existing vanilla bug (a ghost mushroom near a chunk border
 * for one post-processing round), which is what upstream's default already accepts.
 *
 * <p>The guard is scoped to the single {@code canSurvive} call, not the surrounding loop, so a
 * throw at one position does not skip the rest of the chunk and so genuine SuperChunk bugs in the
 * scan itself still surface normally.
 *
 * <p>Distinct (block, failure, culprit) signatures are reported once at WARN, capped at
 * {@link #MAX_REPORTED_SIGNATURES}; everything after that is DEBUG. Set
 * {@code c2me.chunkSystem.suppressGhostMushrooms=false} to switch the scan off entirely.
 */
public final class GhostMushroomCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-GhostMushroom");

    /** Cap on distinct failure signatures reported at WARN — keeps {@link #REPORTED} bounded. */
    private static final int MAX_REPORTED_SIGNATURES = 16;

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong FAILURES = new AtomicLong();

    private GhostMushroomCheck() {
    }

    /**
     * {@code state.canSurvive(region, pos)}, guarded.
     *
     * @return the block's own answer, or {@code true} ("survives", so it is not removed) if the
     *         call threw.
     */
    public static boolean canSurvive(BlockState state, LevelReader region, BlockPos pos, ChunkPos chunkPos) {
        try {
            return state.canSurvive(region, pos);
        } catch (OutOfMemoryError e) {
            // Heap exhaustion is not this scan's to swallow: hiding it here would turn an OOM into
            // silently un-suppressed mushrooms and let the server limp on in an unusable state.
            throw e;
        } catch (Throwable t) {
            report(state, pos, chunkPos, t);
            return true;
        }
    }

    /** Total guarded failures this JVM — for diagnostics/tests. */
    public static long failureCount() {
        return FAILURES.get();
    }

    private static void report(BlockState state, BlockPos pos, ChunkPos chunkPos, Throwable t) {
        FAILURES.incrementAndGet();

        final String culprit = culpritOf(t);
        final String signature = state.getBlock() + "|" + t.getClass().getName() + "|" + culprit;

        if (REPORTED.size() < MAX_REPORTED_SIGNATURES && REPORTED.add(signature)) {
            LOGGER.warn(
                    "Ghost-mushroom suppression: canSurvive({}) threw at {} while sending chunk {} — keeping the block. "
                            + "Likely culprit: {}. This check runs against a single-chunk WorldGenRegion (the "
                            + "minecraft:full step allows a read radius of 0), so a canSurvive hook that reads a "
                            + "neighbouring chunk cannot be evaluated here. Nothing is deleted and worldgen is "
                            + "unaffected; the only effect is that MC-276863 ghost mushrooms may briefly show near "
                            + "chunk borders. Set c2me.chunkSystem.suppressGhostMushrooms=false to disable the scan. "
                            + "Further failures of this kind are logged at DEBUG.",
                    state.getBlock(), pos, chunkPos, culprit, t);
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Ghost-mushroom suppression: canSurvive({}) threw at {} while sending chunk {} — keeping the block.",
                    state.getBlock(), pos, chunkPos, t);
        }
    }

    /**
     * First stack frame outside Minecraft/SuperChunk/the JDK — for a {@code WorldGenRegion} throw
     * the top frames are all vanilla, and the interesting one is whoever called into it.
     */
    private static String culpritOf(Throwable t) {
        for (StackTraceElement element : t.getStackTrace()) {
            final String className = element.getClassName();
            if (className.startsWith("net.minecraft.")
                    || className.startsWith("dev.superchunk.")
                    || className.startsWith("java.")
                    || className.startsWith("jdk.")) {
                continue;
            }
            return className + "#" + element.getMethodName();
        }
        return "<unknown>";
    }
}
