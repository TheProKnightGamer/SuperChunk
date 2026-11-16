package dev.superchunk.mixin;

import net.minecraft.util.BitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Palette resizes without a palette lookup per entry.
 *
 * <p>When a section's palette runs out of ids, {@code PalettedContainer.onResize} builds the next
 * size and {@code Data.copyFrom} moves every entry across (4,096 for blocks): read the old id, look
 * its value up, ask the new palette for that value's id (a scan or a hash probe), write it. Worldgen
 * does this constantly: every section starts as one value (air), so the first block placed in it
 * copies 4,096 entries that are all the same, and a busy section resizes again at 17 values.
 *
 * <p>The new palette is asked once per distinct old id instead, at that id's first entry, so it
 * sees the same values in the same order and hands out the same ids; later entries reuse the
 * answer, which is what the palette would return (asking for a value it holds changes nothing).
 * A one-value source needs a single question. If its answer is id 0 and the new storage holds
 * only zeros, as a fresh one does, the writes are skipped too: they would store zeros.
 *
 * <p>Sources with more than 8 bits (the registry-wide palette), a size mismatch and copying a
 * storage onto itself take vanilla's loop. Kill switch
 * {@code -Dsuperchunk.worldgen.paletteCopy=false}.
 */
@Mixin(PalettedContainer.Data.class)
public abstract class MixinPalettedContainerDataCopy<T> {

    @Unique
    private static final boolean SUPERCHUNK$ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.paletteCopy", "true"));

    /**
     * {@code -Dsuperchunk.worldgen.paletteCopy.verify=true}: after each fast copy into a palette
     * that started empty, checks every entry's value against the source and that the ids follow
     * the order the values first appear in, which is the order vanilla's loop assigns them.
     */
    @Unique
    private static final boolean SUPERCHUNK$VERIFY = Boolean.getBoolean("superchunk.worldgen.paletteCopy.verify");
    @Unique
    private static final java.util.concurrent.atomic.AtomicLong SUPERCHUNK$CHECKED = new java.util.concurrent.atomic.AtomicLong();
    @Unique
    private static final java.util.concurrent.atomic.AtomicLong SUPERCHUNK$BAD = new java.util.concurrent.atomic.AtomicLong();

    @Shadow @Final private BitStorage storage;
    @Shadow @Final private Palette<T> palette;

    @Inject(method = "copyFrom", at = @At("HEAD"), cancellable = true, require = 0)
    private void superchunk$copyByDistinctId(Palette<T> from, BitStorage fromStorage, CallbackInfo ci) {
        if (!SUPERCHUNK$ENABLED || (dev.superchunk.diag.StageAb.PALETTE_COPY && dev.superchunk.diag.StageAb.off())) {
            return;
        }
        final BitStorage to = this.storage;
        final int size = fromStorage.getSize();
        if (to == fromStorage || size <= 0 || size != to.getSize()) {
            return;
        }
        final boolean verify = SUPERCHUNK$VERIFY && this.palette.getSize() == 0;
        if (fromStorage instanceof ZeroBitStorage) {
            final int id = this.palette.idFor(from.valueFor(0));
            if (id != 0 || !superchunk$allZero(to)) {
                for (int i = 0; i < size; i++) {
                    to.set(i, id);
                }
            }
            if (verify) {
                superchunk$verify(from, fromStorage);
            }
            ci.cancel();
            return;
        }
        final int bits = fromStorage.getBits();
        if (bits < 1 || bits > 8) {
            return;
        }
        final int[] ids = new int[1 << bits];
        Arrays.fill(ids, -1);
        for (int i = 0; i < size; i++) {
            final int old = fromStorage.get(i);
            int id = ids[old];
            if (id < 0) {
                id = this.palette.idFor(from.valueFor(old));
                ids[old] = id;
            }
            to.set(i, id);
        }
        if (verify) {
            superchunk$verify(from, fromStorage);
        }
        ci.cancel();
    }

    @Unique
    private void superchunk$verify(Palette<T> from, BitStorage fromStorage) {
        boolean ok = true;
        int next = 0;
        final java.util.IdentityHashMap<Object, Integer> seen = new java.util.IdentityHashMap<>();
        for (int i = 0; i < fromStorage.getSize() && ok; i++) {
            final T value = from.valueFor(fromStorage.get(i));
            final int id = this.storage.get(i);
            Integer expected = seen.get(value);
            if (expected == null) {
                expected = next++;
                seen.put(value, expected);
            }
            ok = id == expected && this.palette.valueFor(id) == value;
        }
        ok = ok && this.palette.getSize() == next;
        final long n = SUPERCHUNK$CHECKED.incrementAndGet();
        if (!ok && SUPERCHUNK$BAD.incrementAndGet() <= 10) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Worldgen").error(
                    "[palette-copy] VERIFY MISMATCH copying {} entries from {} to {}", fromStorage.getSize(),
                    from.getClass().getSimpleName(), this.palette.getClass().getSimpleName());
        }
        if ((n & 0xFFFF) == 0) {
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Worldgen").info(
                    "[palette-copy] VERIFY: copies checked={} MISMATCHES={}", n, SUPERCHUNK$BAD.get());
        }
    }

    @Unique
    private static boolean superchunk$allZero(BitStorage storage) {
        if (storage instanceof ZeroBitStorage) {
            return true;
        }
        for (long word : storage.getRaw()) {
            if (word != 0L) {
                return false;
            }
        }
        return true;
    }
}
