package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraft.world.level.levelgen.feature.stateproviders.RandomizedIntStateProvider;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(RandomizedIntStateProvider.class)
public abstract class MixinRandomizedIntBlockStateProvider {

    @Shadow @Nullable private IntegerProperty property;

    @Shadow @Final private BlockStateProvider source;

    @Shadow
    @Nullable
    private static IntegerProperty findProperty(BlockState state, String propertyName) {
        throw new AbstractMethodError();
    }

    @Shadow @Final private String propertyName;

    @Shadow @Final private IntProvider values;

    /**
     * @author ishland
     * @reason ensure proper behavior
     */
    @Overwrite
    public BlockState getState(RandomSource random, BlockPos pos) {
        BlockState blockState = this.source.getState(random, pos);
        IntegerProperty propertyLocal = this.property; // used as cache only
        if (propertyLocal == null || !blockState.hasProperty(propertyLocal)) {
            IntegerProperty intProperty = findProperty(blockState, this.propertyName);
            if (intProperty == null) {
                return blockState;
            }

            this.property = propertyLocal = intProperty;
        }

        return blockState.setValue(propertyLocal, Integer.valueOf(this.values.sample(random)));
    }

}
