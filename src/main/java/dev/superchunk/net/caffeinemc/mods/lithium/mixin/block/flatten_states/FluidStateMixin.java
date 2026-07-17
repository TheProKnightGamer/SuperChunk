package dev.superchunk.net.caffeinemc.mods.lithium.mixin.block.flatten_states;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
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

    @Inject(method = "<init>", at = @At("RETURN"))
    private void initFluidCache(Fluid fluid, Reference2ObjectArrayMap<?, ?> propertyMap, MapCodec<?> codec, CallbackInfo ci) {
        // Equivalent to this.getType().isEmpty(): only EmptyFluid (Fluids.EMPTY) reports empty.
        // Fluid#isEmpty() is protected on NeoForge 21.1.215 and AT-widening it cascades through
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
