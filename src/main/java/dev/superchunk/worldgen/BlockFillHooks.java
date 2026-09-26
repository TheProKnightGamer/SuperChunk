package dev.superchunk.worldgen;

import dev.superchunk.MixinTargetScan;
import org.slf4j.LoggerFactory;

/**
 * Whether another mod hooks vanilla's per-block noise fill: {@code NoiseChunk} (e.g.
 * {@code getInterpolatedState}, {@code updateFor*}), the beardifier, the noise aquifer, the material
 * rules, the ore veinifier, or {@code NoiseBasedChunkGenerator.doFill} itself.
 *
 * <p>SuperChunk's fill shortcuts — GPU compact block ids ({@code CompactIds}) and the air-cell skip
 * ({@link AirCells}) — answer blocks without running that code, so while another mod hooks it they
 * stand down and every block takes the ordinary per-block path, where its code runs. YUNG's API
 * does this (to bury its structures). One line names the mod, once.
 */
public final class BlockFillHooks {

    private static final String LEVELGEN = "net.minecraft.world.level.levelgen.";
    private static volatile byte state;

    private BlockFillHooks() {
    }

    /** True when nothing else hooks the fill; cached once the involved classes are scanned. */
    public static boolean unhooked() {
        byte s = state;
        if (s == ForeignHooks.UNKNOWN) {
            s = decide();
        }
        return s == ForeignHooks.CLEAR;
    }

    private static synchronized byte decide() {
        byte s = state;
        if (s == ForeignHooks.UNKNOWN) {
            String foreign = MixinTargetScan.foreignMixin(
                    LEVELGEN + "NoiseChunk", LEVELGEN + "Beardifier", LEVELGEN + "Aquifer$NoiseBasedAquifer",
                    LEVELGEN + "material.MaterialRuleList", LEVELGEN + "OreVeinifier");
            if (foreign == null) {
                foreign = MixinTargetScan.foreignHook(LEVELGEN + "NoiseBasedChunkGenerator#doFill");
            }
            if (foreign != null && foreign.startsWith("<")) {
                return ForeignHooks.UNKNOWN; // a class not loaded yet: ask again next chunk
            }
            s = foreign == null ? ForeignHooks.CLEAR : ForeignHooks.HOOKED;
            if (foreign != null) {
                LoggerFactory.getLogger("SuperChunk-Compat").warn("[SuperChunk] Noise-fill shortcuts are off (GPU block "
                        + "ids, air-cell skip): the block fill is also changed by {}, whose code must run.", foreign);
            }
            state = s;
        }
        return s;
    }
}
