package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.worldgen.SmallPaletteIndices;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.Consumer;

/**
 * SuperChunk: allocation-free {@code PalettedContainer.getAll} for small palettes.
 *
 * <p>Vanilla scans all 4096 storage entries into a fresh {@code IntArraySet} (linear
 * {@code findKey} per add — the profiled 1.6% {@code SimpleBitStorage.getAll} + 1.1%
 * {@code IntArraySet.findKey}) and then emits each distinct id. The hot caller is
 * {@code applyBiomeDecoration}'s 9-chunk biome collection (per chunk, per section).
 *
 * <p>Single-valued storage emits once without a scan. For palettes of up to eight
 * entries, distinct IDs are collected into a scalar in encounter order; scanning
 * stops once all palette entries have appeared. Neither path allocates a lambda or
 * scratch array, and collection completes before callbacks run, as in vanilla.
 *
 * <p>Vanilla's {@code IntArraySet} preserves insertion (= first-occurrence) order and its
 * {@code forEach} iterates in that order, so the consumer sees the identical call
 * sequence. Larger palettes and custom storage fall through to vanilla. Biome
 * containers use a global palette above eight values, so they already required the
 * vanilla path with the previous 64-value limit. Disable with
 * {@code -Dsuperchunk.worldgen.paletteGetAll=false}.
 */
@Mixin(PalettedContainer.class)
public abstract class MixinPalettedContainerGetAll<T> {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.worldgen.paletteGetAll", "true"));

    @Shadow
    public volatile PalettedContainer.Data<T> data;

    // @WrapMethod rather than a HEAD-cancel @Inject: same fast-path effect, but composes
    // with other mods' wraps/overwrites of getAll on the fallthrough paths (Lithium/Noisium
    // touch PalettedContainer siblings; a cancel-fight here would be silent feature loss).
    @WrapMethod(method = "getAll", require = 0)
    private void superchunk$fastGetAll(Consumer<T> consumer, Operation<Void> original) {
        if (!SUPERCHUNK$ENABLED) {
            original.call(consumer);
            return;
        }
        PalettedContainer.Data<T> d = this.data;
        Palette<T> palette = d.palette();
        BitStorage storage = d.storage();
        if (storage.getClass() == ZeroBitStorage.class) {
            if (storage.getSize() != 0) {
                consumer.accept(palette.valueFor(0));
            }
            return;
        }
        int paletteSize = palette.getSize();
        if (paletteSize <= 8 && storage.getClass() == SimpleBitStorage.class && storage.getBits() <= 6) {
            long ordered = SmallPaletteIndices.collect(storage.getRaw(), storage.getBits(), storage.getSize(), paletteSize);
            if (ordered == SmallPaletteIndices.INVALID) {
                original.call(consumer);
                return;
            }
            while (ordered != 0L) {
                consumer.accept(palette.valueFor(((int) ordered & 15) - 1));
                ordered >>>= 4;
            }
            return;
        }
        original.call(consumer);
    }
}
