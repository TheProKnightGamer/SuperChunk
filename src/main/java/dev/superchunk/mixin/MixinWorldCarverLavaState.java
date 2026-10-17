package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk: cache the lava {@link BlockState} in {@code WorldCarver.getCarveState}.
 *
 * <p>Vanilla calls {@code LAVA.createLegacyBlock()} for EVERY carved block at or below
 * the lava level — a per-call {@code FluidState → LavaFluid → defaultBlockState().
 * setValue(LEVEL, …)} state-table walk that always yields the same canonical interned
 * {@link BlockState} instance. We compute it once (via the real call, lazily) and return
 * the cached reference.
 *
 * <p>Bit-identical: block states are canonical singletons — the cached object IS the
 * per-call result (reference-equal). {@code getCarveState} is private in
 * {@code WorldCarver}, so all inheriting carvers (incl. modded subclasses that don't
 * override it) get the win; overriders are simply unaffected. {@code @WrapOperation}
 * (chains with other mods, unlike an exclusive {@code @Redirect}) + {@code require = 0}.
 * Disable with {@code -Dsuperchunk.worldgen.lavaStateCache=false}.
 */
@Mixin(WorldCarver.class)
public abstract class MixinWorldCarverLavaState {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.lavaStateCache", "true"));

    @Unique
    private static volatile BlockState superchunk$lavaLegacyBlock;

    @WrapOperation(
            method = "getCarveState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;createLegacyBlock()Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private BlockState superchunk$cachedLava(FluidState instance, Operation<BlockState> original) {
        if (!SUPERCHUNK$ENABLED) {
            return original.call(instance);
        }
        BlockState s = superchunk$lavaLegacyBlock;
        if (s == null) {
            s = original.call(instance);
            superchunk$lavaLegacyBlock = s;
        }
        return s;
    }
}
