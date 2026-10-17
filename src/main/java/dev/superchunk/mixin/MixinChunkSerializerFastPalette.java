package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.superchunk.chunkio.FastChunkPaletteRead;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk: fast-path the block-state {@code PalettedContainer} deserialize in
 * {@code ChunkSerializer.read} — the untouched hot spot on chunk LOAD (C2ME accelerates writing
 * only). Wraps the first {@code Codec.parse(DynamicOps, Object)} in {@code read} (the
 * {@code "block_states"} container; ordinal 0 — biomes are ordinal 1) and routes it through
 * {@link FastChunkPaletteRead}, which decodes the palette directly and reuses vanilla
 * {@code PalettedContainer.unpack}. Returns {@code null} for anything non-trivial, in which case the
 * verbatim vanilla codec runs — so the result is always exactly vanilla's (and a mis-targeted wrap
 * would simply fall back). See {@link FastChunkPaletteRead} for the bit-parity argument.
 *
 * <p><b>Forge 1.20.1 port note.</b> 1.20.1 still has {@code ChunkSerializer} (the
 * {@code SerializableChunkData} rewrite is 1.21.2+), and the ordinal-0 claim was re-verified against
 * src1201 {@code ChunkSerializer.read} (ChunkSerializer:79):
 * <ol>
 *   <li>ordinal 0 — {@code BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, compoundtag.getCompound("block_states"))}
 *       (ChunkSerializer:101), receiver statically typed {@code Codec}, so the owner in the
 *       descriptor is {@code com/mojang/serialization/Codec} as targeted;</li>
 *   <li>ordinal 1 — the biome {@code codec.parse(NbtOps.INSTANCE, …)} (ChunkSerializer:110);</li>
 *   <li>the later {@code BlendingData.CODEC}/{@code BelowZeroRetrogen.CODEC} parses take a
 *       {@code Dynamic}, a different descriptor, so they do not shift the ordinals.</li>
 * </ol>
 * No overlap with the tree's other {@code ChunkSerializer.read} injections: C2ME's
 * {@code chunksystem.async_serialization.MixinChunkSerializer} redirects
 * {@code PoiManager.checkConsistencyWithBlocks} and Starlight's {@code ChunkSerializerMixin}
 * {@code @Inject}s at a light-data point — distinct instructions. The relocated C2ME
 * {@code rewrites/chunk_serializer} module is a WRITE-path (gc-free serialize) replacement and never
 * touches {@code read}.
 */
@Mixin(ChunkSerializer.class)
public abstract class MixinChunkSerializerFastPalette {

    @WrapOperation(
            method = "read",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
                    ordinal = 0))
    private static DataResult<PalettedContainer<BlockState>> superchunk$fastBlockStates(
            Codec<PalettedContainer<BlockState>> codec, DynamicOps<?> ops, Object input,
            Operation<DataResult<PalettedContainer<BlockState>>> original) {
        if (FastChunkPaletteRead.VERIFY) {
            // Lockstep verify: run vanilla, compare the fast read against it, return vanilla.
            DataResult<PalettedContainer<BlockState>> vanilla = original.call(codec, ops, input);
            FastChunkPaletteRead.verifyAgainst(input, vanilla);
            return vanilla;
        }
        DataResult<PalettedContainer<BlockState>> fast = FastChunkPaletteRead.readBlockStates(input);
        return fast != null ? fast : original.call(codec, ops, input);
    }
}
