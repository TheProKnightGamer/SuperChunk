package dev.superchunk.chunkio;

import dev.superchunk.net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.core.IdMapper;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Iterator;
import java.util.Random;

/** Compares the optimized serializer with the actual unmixed vanilla implementation. */
public final class FastPalettePackingTest {
    public static void main(String[] args) {
        IdMapper<Object> registry = new IdMapper<>();
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            Object value = new Object();
            values.add(value);
            registry.add(value);
        }
        Random random = new Random(0x5ca11L);
        int cases = 0;
        for (PalettedContainer.Strategy strategy : new PalettedContainer.Strategy[] {
                PalettedContainer.Strategy.SECTION_STATES, PalettedContainer.Strategy.SECTION_BIOMES}) {
            int edge = strategy == PalettedContainer.Strategy.SECTION_STATES ? 16 : 4;
            for (int count : new int[] {1, 2, 3, 8, 16, 17, 32, 128, 255, 256, 257, 511, 1024}) {
                for (int trial = 0; trial < 10; trial++) {
                    PalettedContainer<Object> container = new PalettedContainer<>(registry, values.get(0), strategy);
                    for (int y = 0; y < edge; y++) {
                        for (int z = 0; z < edge; z++) {
                            for (int x = 0; x < edge; x++) {
                                container.set(x, y, z, values.get(random.nextInt(count)));
                            }
                        }
                    }
                    check(container, registry, strategy);
                    // Leave unused palette entries, including a completely uniform
                    // section whose storage still has multiple bits per value.
                    for (int y = 0; y < edge; y++) {
                        for (int z = 0; z < edge; z++) {
                            for (int x = 0; x < edge; x++) {
                                container.set(x, y, z, values.get(trial % count));
                            }
                        }
                    }
                    check(container, registry, strategy);
                    cases += 2;
                }
            }
        }
        for (PalettedContainer.Strategy strategy : new PalettedContainer.Strategy[] {
                PalettedContainer.Strategy.SECTION_STATES, PalettedContainer.Strategy.SECTION_BIOMES}) {
            for (int count : new int[] {2, 16, 64, 256, 1024}) {
                int bits = 32 - Integer.numberOfLeadingZeros(count - 1);
                int[] indices = new int[strategy.size()];
                for (int i = 0; i < indices.length; i++) indices[i] = random.nextInt(count);
                PalettedContainer.Configuration<Object> configuration = new PalettedContainer.Configuration<>(
                        FastPalettePackingTest::createLithiumPalette, bits);
                PalettedContainer<Object> container = new PalettedContainer<>(registry, strategy, configuration,
                        new SimpleBitStorage(bits, strategy.size(), indices), values.subList(0, count));
                check(container, registry, strategy);
                cases++;
            }
        }
        checkNestedPacking(values);
        System.out.println("FastPalettePacking: " + cases + " vanilla differential cases, detached results and reentrant scratch passed");
    }

    private static <T> Palette<T> createLithiumPalette(int bits, IdMap<T> registry,
                                                      PaletteResize<T> resize, List<T> entries) {
        return new LithiumHashPalette<>(registry, bits, resize, entries);
    }

    private static void check(PalettedContainer<Object> container, IdMapper<Object> registry,
                              PalettedContainer.Strategy strategy) {
        PalettedContainerRO.PackedData<Object> expected = container.pack(registry, strategy);
        PalettedContainerRO.PackedData<Object> actual = FastPalettePacking.pack(container.data, registry, strategy, (bits, value) -> 0);
        require(actual != null, "stock container did not use fast pack");
        List<Object> expectedEntries = expected.paletteEntries();
        long[] expectedWords = expected.storage().map(s -> s.toArray()).orElse(null);
        // Reuse the same thread's scratch BEFORE consuming the output stream.
        PalettedContainer<Object> other = new PalettedContainer<>(registry, registry.byId(1), strategy);
        other.set(0, 0, 0, registry.byId(2));
        FastPalettePacking.pack(other.data, registry, strategy, (bits, value) -> 0);
        require(actual.paletteEntries().equals(expectedEntries), "palette order or contents changed");
        require(Arrays.equals(expectedWords, actual.storage().map(s -> s.toArray()).orElse(null)), "packed words changed");
        actual.paletteEntries().clear(); // Preserve vanilla's detached mutable result list.
        require(container.get(0, 0, 0) != null, "result list aliases the live palette");
    }

    private static void checkNestedPacking(List<Object> values) {
        class ReentrantRegistry implements IdMap<Object> {
            final IdMapper<Object> delegate = new IdMapper<>();
            Runnable callback;
            @Override public int getId(Object value) { return delegate.getId(value); }
            @Override public int size() { return delegate.size(); }
            @Override public Iterator<Object> iterator() { return delegate.iterator(); }
            @Override public Object byId(int id) {
                Runnable action = callback;
                callback = null;
                if (action != null) action.run();
                return delegate.byId(id);
            }
        }
        ReentrantRegistry registry = new ReentrantRegistry();
        values.forEach(registry.delegate::add);
        PalettedContainer.Strategy strategy = PalettedContainer.Strategy.SECTION_STATES;
        PalettedContainer<Object> outer = new PalettedContainer<>(registry, values.get(0), strategy);
        PalettedContainer<Object> nested = new PalettedContainer<>(registry, values.get(1), strategy);
        nested.set(0, 0, 0, values.get(2));
        for (int i = 0; i < 1024; i++) {
            outer.set(i & 15, i >> 8, (i >> 4) & 15, values.get(i));
        }
        PalettedContainerRO.PackedData<Object> expected = outer.pack(registry, strategy);
        registry.callback = () -> FastPalettePacking.pack(nested.data, registry, strategy, (bits, value) -> 0);
        PalettedContainerRO.PackedData<Object> actual = FastPalettePacking.pack(outer.data, registry, strategy, (bits, value) -> 0);
        require(registry.callback == null, "global palette did not exercise reentrant lookup");
        require(actual.paletteEntries().equals(expected.paletteEntries()), "nested packing corrupted palette");
        require(Arrays.equals(actual.storage().orElseThrow().toArray(), expected.storage().orElseThrow().toArray()),
                "nested packing corrupted values");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
