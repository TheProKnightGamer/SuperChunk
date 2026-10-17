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
 * batch-prefetch a whole DH BIOMES generation GROUP on the GPU before DH generates it.
 *
 * <p>This is the 1.20.1 half of the seam {@link MixinDhWorldGenStep} implements for DH 3.x. It
 * exists because the 3.x structure that class hooks is simply not present on the 1.20.1 DH line —
 * verified against {@code DistantHorizons-2.3.4-b-1.20.1-fabric-forge.jar}, which has no
 * {@code AbstractWorldGenStep} and no {@code getChunkWrappersToGenerate}. There, every step is a
 * standalone {@code final} class that inlines the filter:
 * <pre>{@code
 *   public void generateGroup(ThreadedParameters tParams, WorldGenRegion region,
 *                             List<ChunkWrapper> chunkWrappers) {
 *       ArrayList<ChunkAccess> chunksToDo = new ArrayList<>();
 *       for (ChunkWrapper w : chunkWrappers) { ...filter...; chunksToDo.add(w.getChunk()); }
 *       for (ChunkAccess chunk : chunksToDo) { ...generator.createBiomes(chunk)... }   // <-- seam
 *   }
 * }</pre>
 * The boundary between those two loops is the same group boundary the 3.x seam gets, and
 * {@code chunksToDo} is already DH's own final "these chunks get this step" list — of plain
 * {@link net.minecraft.world.level.chunk.ChunkAccess}es, so nothing has to be unwrapped.
 *
 * <p><b>Why a {@code @Redirect} on {@code ArrayList.iterator()} and not an {@code @Inject} into
 * {@code generateGroup}.</b> Same reason the 3.x seam avoids {@code generateGroup}: its descriptor
 * names a DH type ({@code ThreadedParameters}), an {@code @Inject} handler must repeat the target's
 * full argument list, and {@code @Coerce}-ing a DH type to {@code Object} is rejected with "Invalid
 * descriptor" at apply time (verified on DH 3.2.0-b; nothing about Mixin 0.8.5 makes that better).
 * A {@code @Redirect} handler carries only the REDIRECTED CALL's descriptor —
 * {@code (Ljava/util/ArrayList;)Ljava/util/Iterator;}, pure JDK — so it binds without naming a
 * single DH type. The injection point is unambiguous: the wrapper loop iterates the {@code List}
 * parameter (an {@code INVOKEINTERFACE java/util/List.iterator}), and only the generation loop
 * iterates the {@code ArrayList} local (an {@code INVOKEVIRTUAL java/util/ArrayList.iterator}) —
 * bytecode-verified on 2.3.4-b. Should a future DH build blur that distinction,
 * {@link DhWorldGenBridge#onChunkGroup} refuses anything that is not already a chunk, so the worst
 * case is "no prefetch", never "the wrong chunks prefetched".
 *
 * <p>{@code require = 0} + {@code "required": false} on the config + the mod-list gate in
 * {@code DhMixinPlugin}: with DH absent, with a DH whose steps moved, or with
 * {@code -Dsuperchunk.dh.batchPrefetch=false}, nothing is installed and DH generates exactly as it
 * would without SuperChunk's seam. {@code remap = false}: DH's classes are not Mojang-mapped.
 */
// @Pseudo: the target is a Distant Horizons class, absent from the compile classpath and
// possibly from the runtime one. Without it the mixin AP (0.8.7 processor, pinned — see
// PORT-NOTES) fails the build with "Mixin target ... could not be found"; with it the
// target is resolved leniently at apply time, which is also the runtime posture we want
// (no DH installed -> nothing applies). Same shape as mixin/client/MixinSodiumRenderDistanceCap.
@Pseudo
@Mixin(targets = {
        "loaderCommon.forge.com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepBiomes",
        "com.seibel.distanthorizons.common.wrappers.worldGeneration.step.StepBiomes_forge"
}, remap = false)
public abstract class MixinDhStepBiomes {

    @Redirect(
            method = "generateGroup",
            at = @At(value = "INVOKE", target = "Ljava/util/ArrayList;iterator()Ljava/util/Iterator;"),
            require = 0)
    private Iterator<?> superchunk$dhPrefetchBiomeGroup(ArrayList<?> chunksToDo) {
        // Belt-and-braces: this handler REPLACES a call DH's own loop depends on, so unlike the
        // 3.x @Inject seam it must survive even a class-initialization failure on our side
        // (DhWorldGenBridge pulls in the whole GPU prefetch stack). Whatever happens, DH gets its
        // iterator and generates exactly as it did before.
        try {
            DhWorldGenBridge.onChunkGroup(ChunkStatus.BIOMES, chunksToDo);
        } catch (Throwable ignored) {
        }
        return chunksToDo.iterator();
    }
}
