package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(FlowingFluid.class)
public interface IFlowableFluid {

    @Invoker("getDropOff")
    int invokeGetLevelDecreasePerBlock(LevelReader world);

    @Invoker("canPassThroughWall")
    boolean invokeReceivesFlow(Direction face, BlockGetter world, BlockPos pos, BlockState state, BlockPos fromPos, BlockState fromState);

    @Invoker("isSourceBlockOfThisType")
    boolean invokeIsMatchingAndStill(FluidState state);

}
