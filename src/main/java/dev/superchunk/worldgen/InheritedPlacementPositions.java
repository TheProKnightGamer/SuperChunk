package dev.superchunk.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;

/**
 * Whether a placement modifier class still uses the {@code getPositions} of the vanilla class that
 * {@code MixinPlacedFeatureImperative}'s fast paths replicate ({@link RepeatingPlacement} or
 * {@link InSquarePlacement}). A modded subclass that overrides it takes the generic path, so its
 * own positions are used. Cached per class.
 */
public final class InheritedPlacementPositions {
    private static final ClassValue<Boolean> INHERITED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Class<?> declaring = type.getMethod("getPositions", PlacementContext.class, RandomSource.class,
                        BlockPos.class).getDeclaringClass();
                return declaring == RepeatingPlacement.class || declaring == InSquarePlacement.class;
            } catch (Throwable t) {
                return false;
            }
        }
    };

    private InheritedPlacementPositions() {
    }

    public static boolean of(Class<?> type) {
        return INHERITED.get(type);
    }
}
