package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.gpu.dfc.BiomeClimateCache;
import net.minecraft.core.HolderGetter;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk GPU — <b>dimension label for biome-climate registration logging</b>.
 *
 * <p>{@code BiomeClimateCache.tryRegister} runs inside {@code RandomState}'s
 * constructor ({@code MixinNoiseConfig}), where no dimension identity is reachable —
 * {@code RandomState} only receives the {@code NoiseGeneratorSettings} VALUE. The
 * dimension IS known one frame up: {@code ChunkMap}'s constructor calls
 * {@code RandomState.create(...)} on the same thread, right after assigning
 * {@code this.level}. So we wrap those calls and push the level's registry id (e.g.
 * {@code minecraft:overworld}) into {@link BiomeClimateCache}'s thread-local label for
 * exactly the duration of the create — the '[biome] climate offload ENABLED/skipped'
 * and gate log lines then carry the dimension id. Pure logging metadata: any failure
 * falls back to the {@code unknown-dimension} label and behavior is unchanged.
 */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMapClimateDimLabel {

    @Shadow
    @Final
    public ServerLevel level;

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/RandomState;create(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;Lnet/minecraft/core/HolderGetter;J)Lnet/minecraft/world/level/levelgen/RandomState;"))
    private RandomState superchunk$labelClimateDimension(NoiseGeneratorSettings settings,
                                                         HolderGetter<NormalNoise.NoiseParameters> noises,
                                                         long seed,
                                                         Operation<RandomState> original) {
        String label = null;
        try {
            label = String.valueOf(this.level.dimension().location());
        } catch (Throwable ignored) {
            // metadata only — never let labeling break ChunkMap construction.
        }
        BiomeClimateCache.pushDimensionLabel(label);
        try {
            return original.call(settings, noises, seed);
        } finally {
            BiomeClimateCache.popDimensionLabel();
        }
    }
}
