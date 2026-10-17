package dev.superchunk.net.caffeinemc.mods.lithium.mixin.block.flatten_states;

import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidState.class)
public abstract class FluidStateMixin {
    @Shadow
    public abstract Fluid getType();

    private boolean isEmptyCache;

    // PORT-1.20.1: the FluidState ctor takes ImmutableMap on 1.20.1 (1.21.1 switched to
    // Reference2ObjectArrayMap) — captured ctor params adapted to match.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void initFluidCache(Fluid fluid, ImmutableMap<?, ?> propertyMap, MapCodec<?> codec, CallbackInfo ci) {
        // Equivalent to this.getType().isEmpty(): only EmptyFluid (Fluids.EMPTY) reports empty.
        // Fluid#isEmpty() is protected on 1.20.1 too, and AT-widening it cascades through
        // the override hierarchy and breaks the Minecraft recompile, so check the type directly.
        this.isEmptyCache = this.getType() instanceof EmptyFluid;
    }

    /**
     * @reason Use cached property
     * @author Maity
     */
    @Overwrite
    public boolean isEmpty() {
        return this.isEmptyCache;
    }
}
