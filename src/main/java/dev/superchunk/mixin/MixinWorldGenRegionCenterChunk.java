package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.superchunk.worldgen.RegionCenterChunk;

/**
 * {@code WorldGenRegion.getChunk} answers its own center chunk directly. A region exists for one
 * generation step of its center, which is the chunk passed to the constructor and stays the
 * holder's chunk for the whole step; the holder lookup ({@code getChunkIfPresentUnchecked}, a chain
 * of dependent loads through the chunk system) is 2.3% of worldgen CPU, and most feature, carver
 * and surface reads land in the center. Neighbours keep the lookup. Kill switch
 * {@code -Dsuperchunk.worldgen.regionCenterChunk=false}; {@code .verify=true} compares every answer.
 */
@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegionCenterChunk {

    @Shadow @Final private ChunkAccess center;

    @Unique
    private GenerationChunkHolder superchunk$centerHolder;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void superchunk$findCenter(net.minecraft.server.level.ServerLevel level, StaticCache2D<GenerationChunkHolder> cache,
                                       net.minecraft.world.level.chunk.status.ChunkStep step, ChunkAccess center, CallbackInfo ci) {
        if (RegionCenterChunk.ENABLED) {
            ChunkPos pos = center.getPos();
            this.superchunk$centerHolder = cache.get(pos.x, pos.z);
        }
    }

    @WrapOperation(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/GenerationChunkHolder;getChunkIfPresentUnchecked(Lnet/minecraft/world/level/chunk/status/ChunkStatus;)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
            require = 0)
    private ChunkAccess superchunk$centerChunk(GenerationChunkHolder holder, ChunkStatus status, Operation<ChunkAccess> original) {
        if (holder != this.superchunk$centerHolder || holder == null) {
            return original.call(holder, status);
        }
        if (RegionCenterChunk.VERIFY) {
            ChunkAccess actual = original.call(holder, status);
            RegionCenterChunk.verify(this.center, actual, status);
            return actual;
        }
        return this.center;
    }
}
