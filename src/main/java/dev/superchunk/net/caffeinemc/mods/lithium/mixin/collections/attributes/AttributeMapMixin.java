package dev.superchunk.net.caffeinemc.mods.lithium.mixin.collections.attributes;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * PORT-1.20.1 (plan 6.2, radium AttributeContainerMixin blueprint): 1.20.1 has a SINGLE
 * dirtyAttributes set (1.21 split it into attributesToUpdate/attributesToSync).
 */
@Mixin(AttributeMap.class)
public class AttributeMapMixin {

    @Mutable
    @Shadow
    @Final
    private Set<AttributeInstance> dirtyAttributes;

    @Inject(
            method = "<init>",
            at = @At("RETURN")
    )
    private void initCollections(AttributeSupplier defaultAttributes, CallbackInfo ci) {
        this.dirtyAttributes = new ReferenceOpenHashSet<>(0);
    }
}
