package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SuperChunk: inline world-height methods on {@link WorldGenRegion} — the region view
 * every feature/carver/surface stage queries (`WorldGenRegion.getMinBuildHeight` alone
 * is 2.0% of the FEATURES subtree; the interface-default chains it feeds are more).
 * Same pattern and rationale as {@link MixinChunkAccessInlineHeights}; values captured
 * from the backing {@link ServerLevel} at construction, immutable for the region's
 * lifetime. Gated load-time by {@code -Dsuperchunk.worldgen.inlineHeights=false}.
 */
@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegionInlineHeights implements LevelHeightAccessor {

    @Shadow
    @Final
    private ServerLevel level;

    @Unique
    private int superchunk$bottomY;
    @Unique
    private int superchunk$height;
    @Unique
    private int superchunk$topYInclusive;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void superchunk$initHeights(CallbackInfo ci) {
        this.superchunk$height = this.level.getHeight();
        this.superchunk$bottomY = this.level.getMinBuildHeight();
        this.superchunk$topYInclusive = this.superchunk$bottomY + this.superchunk$height - 1;
    }

    @WrapMethod(method = "getMinBuildHeight()I", require = 0)
    private int superchunk$getMinBuildHeight(Operation<Integer> original) {
        return this.superchunk$bottomY;
    }

    @WrapMethod(method = "getHeight()I", require = 0)
    private int superchunk$getHeight(Operation<Integer> original) {
        return this.superchunk$height;
    }

    @Override
    public int getMaxBuildHeight() {
        return this.superchunk$topYInclusive + 1;
    }

    @Override
    public boolean isOutsideBuildHeight(BlockPos pos) {
        int y = pos.getY();
        return y < this.superchunk$bottomY || y > this.superchunk$topYInclusive;
    }

    @Override
    public boolean isOutsideBuildHeight(int y) {
        return y < this.superchunk$bottomY || y > this.superchunk$topYInclusive;
    }

    @Override
    public int getSectionIndex(int y) {
        return (y >> 4) - (this.superchunk$bottomY >> 4);
    }

    @Override
    public int getSectionIndexFromSectionY(int sectionY) {
        return sectionY - (this.superchunk$bottomY >> 4);
    }

    @Override
    public int getSectionYFromSectionIndex(int sectionIndex) {
        return sectionIndex + (this.superchunk$bottomY >> 4);
    }

    @Override
    public int getSectionsCount() {
        // == getMaxSection() - getMinSection(), the vanilla default's exact formula
        return ((this.superchunk$topYInclusive >> 4) + 1) - (this.superchunk$bottomY >> 4);
    }

    @Override
    public int getMinSection() {
        return this.superchunk$bottomY >> 4;
    }

    @Override
    public int getMaxSection() {
        return (this.superchunk$topYInclusive >> 4) + 1;
    }
}
