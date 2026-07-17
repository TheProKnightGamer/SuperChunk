package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
 * <p>For palettes of size ≤ 64 (bits ≤ 6, so every encodable id ≤ 63) a 64-bit seen-mask
 * replaces the set: emit each distinct id at FIRST occurrence during the same scan.
 * Vanilla's {@code IntArraySet} preserves insertion (= first-occurrence) order and its
 * {@code forEach} iterates in that order, so the consumer sees the IDENTICAL call
 * sequence. Larger palettes (impossible for biome containers, rare for blocks) fall
 * through to vanilla. Disable with {@code -Dsuperchunk.worldgen.paletteGetAll=false}.
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
        if (palette.getSize() > 64) {
            original.call(consumer); // vanilla path
            return;
        }
        long[] seen = {0L};
        d.storage().getAll(id -> {
            long bit = 1L << id; // id <= 63 because bits <= 6 for size <= 64
            if ((seen[0] & bit) == 0L) {
                seen[0] |= bit;
                consumer.accept(palette.valueFor(id));
            }
        });
    }
}
