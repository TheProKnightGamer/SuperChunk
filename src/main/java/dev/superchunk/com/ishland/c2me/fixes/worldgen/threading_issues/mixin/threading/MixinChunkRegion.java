package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Supplier;

@Mixin(WorldGenRegion.class)
public class MixinChunkRegion {

    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private ChunkStep generatingStep;

    @Shadow
    private Supplier<String> currentlyGenerating;

    @ModifyVariable(method = "destroyBlock", at = @At("HEAD"), argsOnly = true)
    private boolean preventDropItem(final boolean drop, final BlockPos pos, final boolean drop1, final Entity breakingEntity, final int maxUpdateDepth) {
        if (drop) {
            LOGGER.error("Detected breakBlock item drop on pos {}, status: {}, currently generating: {}",
                    pos, this.generatingStep.targetStatus(), this.currentlyGenerating == null ? "unknown": this.currentlyGenerating.get());
        }
        return false;
    }

}
