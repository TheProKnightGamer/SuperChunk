package dev.superchunk.mixin.compat;

import dev.superchunk.compat.DhWorldGenBridge;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Distant Horizons interop: batch-prefetch a whole DH generation GROUP on the GPU before DH
 * generates it.
 *
 * <p>Every DH world-generation step begins the same way — {@code generateGroup} calls
 * {@code getChunkWrappersToGenerate(list)} on this base class to filter the group down to the
 * chunks that actually need this step, then loops them calling the vanilla generator. Injecting at
 * that filter's RETURN is the ideal seam: it hands us DH's own final chunk list, one call per
 * group, just before the loop. {@link DhWorldGenBridge} then submits the whole list to
 * SuperChunk's cross-chunk GPU batcher, so those chunks share batched dispatches (and receive
 * GPU-computed block ids) exactly like server-generated ones instead of paying a dispatch each.
 *
 * <p><b>Why this method and not {@code generateGroup}.</b> {@code generateGroup}'s descriptor is
 * built from DH's own types, and Mixin will not bind an injector to it from a mod that cannot name
 * them — {@code @Coerce}-ing them to {@code Object} is rejected with "Invalid descriptor" at apply
 * time (verified on DH 3.2.0-b). {@code getChunkWrappersToGenerate(List)ArrayList} and
 * {@code getChunkStatus()ChunkStatus} carry only JDK and Minecraft types, so both bind cleanly —
 * and, as a bonus, DH's own status filter is reused rather than re-implemented.
 *
 * <p><b>Version-tolerant targeting.</b> DH ships one merged Fabric/NeoForge jar and has changed how
 * it names the loader-specific half: 3.x suffixes the class
 * ({@code AbstractWorldGenStep_neoforge}), 2.x prefixes the package
 * ({@code loaderCommon.neoforge.…}). Both are listed; only the installed one resolves (the other
 * logs a "target was not found" line at boot), and with DH absent neither does — this mixin then
 * never applies. The config is {@code "required": false} and {@code DhMixinPlugin} additionally
 * refuses it unless DH is on the mod list. {@code remap = false}: DH's classes are not
 * Mojang-mapped.
 *
 * <p>{@code require = 0}: should a future DH restructure this step base, the hook is quietly not
 * installed and DH generates exactly as it would without SuperChunk's seam — never a boot failure.
 */
@Mixin(targets = {
        "com.seibel.distanthorizons.common.wrappers.worldGeneration.step.AbstractWorldGenStep_neoforge",
        "loaderCommon.neoforge.com.seibel.distanthorizons.common.wrappers.worldGeneration.step.AbstractWorldGenStep"
}, remap = false)
public abstract class MixinDhWorldGenStep {

    /** Which generation step this instance is — SuperChunk only prefetches BIOMES and NOISE. */
    @Shadow
    public abstract ChunkStatus getChunkStatus();

    @Inject(method = "getChunkWrappersToGenerate", at = @At("RETURN"), require = 0)
    private void superchunk$dhPrefetchGroup(List<?> allWrappers,
                                            CallbackInfoReturnable<ArrayList<?>> cir) {
        DhWorldGenBridge.onGroup(this.getChunkStatus(), cir.getReturnValue());
    }
}
