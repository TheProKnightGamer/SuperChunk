package dev.superchunk.com.ishland.c2me.opts.allocs.mixin.surfacebuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;
import java.util.function.Supplier;

@Mixin(SurfaceRules.Context.class)
public class MixinMaterialRuleContext {

    @Shadow
    @Final
    private Function<BlockPos, Holder<Biome>> biomeGetter;

    @Shadow
    @Final
    BlockPos.MutableBlockPos pos;

    @Shadow
    Supplier<Holder<Biome>> biome;

    @Shadow
    int blockY;

    @Shadow
    int waterHeight;

    @Shadow
    int stoneDepthBelow;

    @Shadow
    int stoneDepthAbove;

    @Shadow
    long lastUpdateY;

    @Unique
    private int lazyPosX;
    @Unique
    private int lazyPosY;
    @Unique
    private int lazyPosZ;
    @Unique
    private Holder<Biome> lastBiome = null;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.biome = () -> {
            if (this.lastBiome == null)
                return this.lastBiome = this.biomeGetter.apply(this.pos.set(this.lazyPosX, this.lazyPosY, this.lazyPosZ));
            return this.lastBiome;
        };
    }

    /**
     * @author ishland
     * @reason reduce allocs
     */
    @Overwrite
    public void updateY(int i, int j, int k, int l, int m, int n) {
        // TODO [VanillaCopy]
        this.lastUpdateY++;
        this.blockY = m;
        this.waterHeight = k;
        this.stoneDepthBelow = j;
        this.stoneDepthAbove = i;

        // set lazy values
        this.lazyPosX = l;
        this.lazyPosY = m;
        this.lazyPosZ = n;
        // clear cache
        this.lastBiome = null;
    }

}
