package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.superchunk.chunkio.DirectPaletteEncoder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SuperChunk: {@code ChunkSerializer.write} gets each section's {@code block_states} and {@code biomes}
 * from {@link DirectPaletteEncoder} instead of DataFixerUpper's codecs (same tags; see there).
 *
 * <p>Wraps every {@code Codec.encodeStart} in {@code write} and takes only the two whose codec is
 * vanilla's: {@code BLOCK_STATE_CODEC}, and the biome codec {@code makeBiomeCodec} last returned on
 * this thread (its registry comes with it). Any other call, a codec another mod put in, or a
 * section the encoder declines goes to the codec as before. Stands down while another mod hooks
 * {@code PalettedContainer.pack} or the palette it packs into.
 */
@Mixin(ChunkSerializer.class)
public abstract class MixinChunkSerializerDirectEncode {
    @Shadow
    @Final
    private static Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC;

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.io.directPaletteEncode", "true"));

    /** The biome codec vanilla's {@code makeBiomeCodec} last built on this thread, and its registry. */
    @Unique
    private static final ThreadLocal<Object[]> SUPERCHUNK$BIOME_CODEC = ThreadLocal.withInitial(() -> new Object[2]);

    @Unique
    private static volatile byte superchunk$hooks;

    @ModifyReturnValue(method = "makeBiomeCodec", at = @At("RETURN"))
    private static Codec<PalettedContainerRO<Holder<Biome>>> superchunk$noteBiomeCodec(
            Codec<PalettedContainerRO<Holder<Biome>>> codec, @Local(argsOnly = true) Registry<Biome> registry) {
        Object[] last = SUPERCHUNK$BIOME_CODEC.get();
        last[0] = codec;
        last[1] = registry;
        return codec;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @WrapOperation(method = "write", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static DataResult superchunk$directPalettes(Codec codec, DynamicOps ops, Object input, Operation<DataResult> original) {
        if (ops != NbtOps.INSTANCE || !(input instanceof PalettedContainer<?> container)) {
            return original.call(codec, ops, input);
        }
        if (DirectPaletteEncoder.TIMING) {
            long start = System.nanoTime();
            DataResult result = superchunk$encode(codec, container, original);
            DirectPaletteEncoder.time(System.nanoTime() - start);
            return result;
        }
        return superchunk$encode(codec, container, original);
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataResult superchunk$encode(Codec codec, PalettedContainer<?> container, Operation<DataResult> original) {
        if (!SUPERCHUNK$ENABLED || !superchunk$unhooked()) {
            return original.call(codec, NbtOps.INSTANCE, container);
        }
        CompoundTag direct = null;
        if (codec == BLOCK_STATE_CODEC) {
            direct = DirectPaletteEncoder.blockStates((PalettedContainer<BlockState>) container);
        } else {
            Object[] last = SUPERCHUNK$BIOME_CODEC.get();
            if (codec == last[0]) {
                direct = DirectPaletteEncoder.biomes((PalettedContainer<Holder<Biome>>) container, (Registry<Biome>) last[1]);
            }
        }
        if (direct == null) {
            DirectPaletteEncoder.noteDeclined();
            return original.call(codec, NbtOps.INSTANCE, container);
        }
        DirectPaletteEncoder.noteDirect();
        if (DirectPaletteEncoder.VERIFY) {
            DataResult vanilla = original.call(codec, NbtOps.INSTANCE, container);
            DirectPaletteEncoder.verify(direct, vanilla.result().orElse(null));
            return vanilla;
        }
        return DataResult.success(direct);
    }

    @Unique
    private static boolean superchunk$unhooked() {
        byte state = superchunk$hooks;
        if (state == dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
            state = dev.superchunk.worldgen.ForeignHooks.state("Direct palette encoding (PalettedContainer.pack)",
                    "net.minecraft.world.level.chunk.PalettedContainer#pack",
                    "net.minecraft.world.level.chunk.HashMapPalette#idFor",
                    "net.minecraft.world.level.chunk.HashMapPalette#getEntries");
            if (state != dev.superchunk.worldgen.ForeignHooks.UNKNOWN) {
                superchunk$hooks = state;
            }
        }
        return state == dev.superchunk.worldgen.ForeignHooks.CLEAR;
    }
}
