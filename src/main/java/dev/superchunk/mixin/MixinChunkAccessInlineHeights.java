package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SuperChunk: inline world-height methods on {@link ChunkAccess} (extends Lithium's
 * {@code world.inline_height} pattern — which covers only {@code Level}/{@code LevelChunk}
 * — to the class worldgen actually hammers: {@code ProtoChunk} reads/writes, heightmap
 * queries, section indexing).
 *
 * <p>Vanilla routes {@code getMinBuildHeight()} etc. through
 * {@code levelHeightAccessor → Level → dimensionType() → Holder.value() → minY()} on
 * EVERY call (~10⁵-10⁶/chunk from {@code getBlockState} bounds checks,
 * {@code getSectionIndex}, {@code Heightmap.getFirstAvailable}...). The values are
 * immutable for the life of the instance — vanilla itself freezes the section count at
 * construction — so we capture them once at constructor RETURN (the ctor body itself
 * only uses the accessor directly, never {@code this}-virtual calls: verified).
 *
 * <p>Bit-identical: same values, same arithmetic as the {@link LevelHeightAccessor}
 * defaults. Soft-overrides shadow the interface defaults; the two concrete delegators
 * are wrapped via {@code @WrapMethod} (composes with other mods' wraps, unlike
 * {@code @Overwrite}). {@code getHeightAccessorForGeneration()} (below-zero retrogen)
 * is untouched. Whole mixin is gated load-time by
 * {@code -Dsuperchunk.worldgen.inlineHeights=false} via SuperChunkMixinPlugin.
 */
@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccessInlineHeights implements LevelHeightAccessor {

    @Shadow
    @Final
    protected LevelHeightAccessor levelHeightAccessor;

    @Unique
    private int superchunk$bottomY;
    @Unique
    private int superchunk$height;
    @Unique
    private int superchunk$topYInclusive;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void superchunk$initHeights(CallbackInfo ci) {
        this.superchunk$height = this.levelHeightAccessor.getHeight();
        this.superchunk$bottomY = this.levelHeightAccessor.getMinBuildHeight();
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
