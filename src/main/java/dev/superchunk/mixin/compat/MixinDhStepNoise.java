package dev.superchunk.mixin.compat;

import dev.superchunk.compat.DhWorldGenBridge;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Distant Horizons interop (DH <b>2.3.x</b> line, i.e. the line that ships for 1.20.1):
 * batch-prefetch a whole DH NOISE generation GROUP on the GPU before DH generates it.
 *
 * <p>The NOISE sibling of {@link MixinDhStepBiomes} — see that class for the full rationale (why
 * the 3.x {@code AbstractWorldGenStep} seam does not exist here, why the injection is a
 * {@code @Redirect} on the generation loop's {@code ArrayList.iterator()} rather than an
 * {@code @Inject} into the DH-typed {@code generateGroup}, and how a mis-bind degrades to "no
 * prefetch"). {@code StepNoise.generateGroup} has the identical two-loop shape on 2.3.4-b, and its
 * second loop is what calls {@code ChunkGenerator.fillFromNoise} per chunk.
 *
 * <p>This is the seam that matters most: the batched submit is the ONLY carrier of the chained
 * decide-kernel block ids (compact ids), so without it DH's chunks re-run the whole CPU decide loop
 * that server-generated chunks skip. Each chunk's {@code NoiseChunk} already exists by the time
 * this fires — DH ran its BIOMES step over the same group moments earlier, and {@code createBiomes}
 * is what creates and caches it (the same precondition the chunk-system seam relies on).
 */
// @Pseudo: the target is a Distant Horizons class, absent from the compile classpath and
// possibly from the runtime one. Without it the mixin AP (0.8.7 processor, pinned — see
// PORT-NOTES) fails the build with "Mixin target ... could not be found"; with it the
// target is resolved leniently at apply time, which is also the runtime posture we want
// (no DH installed -> nothing applies). Same shape as mixin/client/MixinSodiumRenderDistanceCap.
@Pseudo
@Mixin(targets = {
        "loaderCommon.forge.com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepNoise",
        "com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepNoise_forge"
}, remap = false)
public abstract class MixinDhStepNoise {

    @Redirect(
            method = "generateGroup",
            at = @At(value = "INVOKE", target = "Ljava/util/ArrayList;iterator()Ljava/util/Iterator;"),
            require = 0)
    private Iterator<?> superchunk$dhPrefetchNoiseGroup(ArrayList<?> chunksToDo) {
        // See MixinDhStepBiomes: this handler replaces a call DH's own loop depends on, so it must
        // never let anything escape into DH.
        try {
            DhWorldGenBridge.onChunkGroup(ChunkStatus.NOISE, chunksToDo);
        } catch (Throwable ignored) {
        }
        return chunksToDo.iterator();
    }
}
