package dev.superchunk.com.ishland.c2me.base.mixin.access;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.StateHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StateHolder.class)
public interface IState<S> {
    @Accessor("propertiesCodec")
    MapCodec<S> getCodec();
}
