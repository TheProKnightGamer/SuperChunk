package dev.superchunk.chunkio;

import dev.superchunk.net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette;
import net.minecraft.core.IdMap;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.chunk.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.LongStream;

/** Vanilla's serialization order and representation, without a new unpack buffer per section. */
public final class FastPalettePacking {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private FastPalettePacking() {
    }

    public static boolean supports(PalettedContainer.Data<?> data, PalettedContainer.Strategy strategy) {
        if (strategy != PalettedContainer.Strategy.SECTION_STATES
                && strategy != PalettedContainer.Strategy.SECTION_BIOMES) {
            return false;
        }
        int size = strategy.size();
        if (size != 4096 && size != 64) {
            return false;
        }
        Class<?> storage = data.storage().getClass();
        Class<?> palette = data.palette().getClass();
        return (storage == ZeroBitStorage.class || storage == SimpleBitStorage.class)
                && data.storage().getSize() == size
                && (palette == SingleValuePalette.class || palette == LinearPalette.class
                || palette == HashMapPalette.class || palette == GlobalPalette.class
                || palette == LithiumHashPalette.class);
    }

    /** Called under the container's normal acquire/release boundary; null means use the original method. */
    public static <T> PalettedContainerRO.PackedData<T> pack(PalettedContainer.Data<T> data, IdMap<T> registry,
                                                           PalettedContainer.Strategy strategy, PaletteResize<T> resize) {
        if (!supports(data, strategy)) {
            return null;
        }
        if (data.storage().getClass() == ZeroBitStorage.class
                && strategy.calculateBitsForSerialization(registry, 1) == 0) {
            // Vanilla's unpack+remap visits only ID zero and emits no storage words.
            // Keep its detached, mutable palette list, including the same valueFor call.
            ArrayList<T> entries = new ArrayList<>(1);
            entries.add(data.palette().valueFor(0));
            return new PalettedContainerRO.PackedData<>(entries, Optional.empty());
        }

        Scratch scratch = SCRATCH.get();
        boolean leased = !scratch.busy;
        int size = strategy.size();
        int[] values;
        if (leased) {
            scratch.busy = true;
            values = size == 4096 ? scratch.blocks : scratch.biomes;
        } else {
            // A mixin on a palette callback can reenter pack on the same thread.
            values = new int[size];
        }
        try {
            HashMapPalette<T> compacted = new HashMapPalette<>(registry, data.storage().getBits(), resize);
            data.storage().unpack(values);
            // Match vanilla swapPalette's encounter order and consecutive-ID shortcut.
            int lastId = -1;
            int lastPacked = -1;
            for (int i = 0; i < values.length; i++) {
                int id = values[i];
                if (id != lastId) {
                    lastId = id;
                    lastPacked = compacted.idFor(data.palette().valueFor(id));
                }
                values[i] = lastPacked;
            }
            int bits = strategy.calculateBitsForSerialization(registry, compacted.getSize());
            Optional<LongStream> storage = Optional.empty();
            if (bits != 0) {
                // SimpleBitStorage copies the values. No scratch memory escapes through
                // the lazy stream: each result exclusively owns its packed long array.
                SimpleBitStorage packed = new SimpleBitStorage(bits, size, values);
                storage = Optional.of(Arrays.stream(packed.getRaw()));
            }
            return new PalettedContainerRO.PackedData<>(compacted.getEntries(), storage);
        } finally {
            if (leased) {
                scratch.busy = false;
            }
        }
    }

    private static final class Scratch {
        final int[] blocks = new int[4096];
        final int[] biomes = new int[64];
        boolean busy;
    }
}
