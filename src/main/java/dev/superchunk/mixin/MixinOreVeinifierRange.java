package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.BlockFillHooks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.OreVeinifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The ore vein filler without the vein toggle where no vein can be.
 *
 * <p>Vanilla's filler reads the vein toggle first, picks copper or iron by its sign, and only then
 * tests the block's height against that type's range (copper 0 to 50, iron -60 to -8), returning
 * no block outside it. Every solid block of an undecided cell goes through it, and most of those
 * are near the surface, above both ranges. At a height outside every type's range the answer is
 * "no block" whichever type the sign picks, so the toggle is not read there: the test that follows
 * fails for the type picked from the stand-in value as it does for the other.
 *
 * <p>Stands down while another mod hooks the fill ({@link BlockFillHooks}). Kill switch
 * {@code -Dsuperchunk.worldgen.veinRange=false}.
 */
@Mixin(OreVeinifier.class)
public abstract class MixinOreVeinifierRange {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.veinRange", "true"));

    /** {@code [lowest minY, highest maxY]} then one flag per height in between; null: unknown, never skip. */
    @Unique
    private static volatile int[] superchunk$heights;

    // FORGE 1.20.1 PORT: the filler lambda is m_209660_ in production (SRG) and lambda$create$0 in
    // dev; lambda names are not refmap-remapped, so both are listed.
    @WrapOperation(method = {"m_209660_", "lambda$create$0"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
            ordinal = 0), require = 0)
    private static double superchunk$toggleInRange(DensityFunction veinToggle, DensityFunction.FunctionContext context,
                                                   Operation<Double> original) {
        if (SUPERCHUNK$ENABLED && !(dev.superchunk.diag.StageAb.VEIN_RANGE && dev.superchunk.diag.StageAb.off())) {
            int[] heights = superchunk$heights;
            if (heights == null) {
                heights = superchunk$readHeights();
            }
            if (heights.length >= 2) {
                final int y = context.blockY();
                if ((y < heights[0] || y > heights[1] || heights[2 + y - heights[0]] == 0) && BlockFillHooks.unhooked()) {
                    return 0.0; // no type's range holds y: the filler returns no block either way
                }
            }
        }
        return original.call(veinToggle, context);
    }

    @Unique
    private static int[] superchunk$readHeights() {
        int[] heights = new int[0];
        try {
            Object[] types = Class.forName("net.minecraft.world.level.levelgen.OreVeinifier$VeinType").getEnumConstants();
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            boolean ok = types != null && types.length > 0;
            if (ok) {
                for (Object type : types) {
                    if (!(type instanceof IOreVeinTypeAccess range)) {
                        ok = false;
                        break;
                    }
                    lo = Math.min(lo, range.superchunk$minY());
                    hi = Math.max(hi, range.superchunk$maxY());
                }
            }
            if (ok && lo <= hi && (long) hi - lo < 8192) {
                heights = new int[2 + hi - lo + 1];
                heights[0] = lo;
                heights[1] = hi;
                for (Object type : types) {
                    IOreVeinTypeAccess range = (IOreVeinTypeAccess) type;
                    for (int y = range.superchunk$minY(); y <= range.superchunk$maxY(); y++) {
                        heights[2 + y - lo] = 1;
                    }
                }
            }
        } catch (Throwable t) {
            heights = new int[0];
        }
        superchunk$heights = heights;
        return heights;
    }
}
