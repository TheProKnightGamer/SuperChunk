package dev.superchunk.chunkio;

import dev.superchunk.net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * SuperChunk: <b>direct chunk-save encode of a section's {@code block_states} and {@code biomes}</b>,
 * the write-side twin of {@link FastChunkPaletteRead}.
 *
 * <p>{@code ChunkSerializer.write} encodes both containers of every section through
 * DataFixerUpper: {@code PalettedContainer.pack} (a {@code HashMapPalette} sized by the section's
 * storage bits, then its filtered iterator), a record codec, and per palette entry the block-state
 * dispatch codec with one map codec per property, each wrapped in {@code DataResult}s and NbtOps
 * builders. This builds the same tags directly:
 * <ul>
 *   <li>The same packing, read and written as packed words: the ids in index order, each first-met
 *       id's value through the section's own {@code valueFor}, values numbered by identity in the
 *       order they are first met (as the {@code HashMapPalette} numbers them), the same
 *       {@code calculateBitsForSerialization}, and the words {@code SimpleBitStorage} would pack.
 *       Where every value keeps its number at the same width, those are the section's own words
 *       with the unused bits cleared.</li>
 *   <li>The same tags: {@code {palette: [...], data: long[]}} with {@code data} left out when the
 *       bits are 0. A block entry is {@code {Properties: {...}, Name: "ns:id"}}, values named by
 *       {@code Property.getName}; a block without properties has only {@code Name}. A biome entry
 *       is the holder's key. Keys go in in the codec's order (DFU's dispatch codec adds
 *       {@code Properties} before {@code Name}, and the properties come last to first), because a
 *       compound's map writes colliding keys in the order they were put.</li>
 * </ul>
 * The strings are built once per state (and per biome holder) and their immutable
 * {@link StringTag}s reused; every compound and list is new for each save, as before.
 *
 * <p>Anything else takes the codec: an unknown palette or storage class, another strategy, a block
 * the registry does not name, a biome holder that is not a bound reference of the registry. Lookup errors
 * ({@code valueFor} of a missing id) throw the same exception as vanilla's {@code pack}.
 * Kill switch {@code -Dsuperchunk.io.directPaletteEncode=false};
 * {@code -Dsuperchunk.io.directPaletteEncode.verify=true} also runs the codec, compares the written
 * bytes and keeps the codec's tag.
 */
public final class DirectPaletteEncoder {
    /** Implemented on {@code BlockState} by {@code MixinBlockStateEncodedEntry}: the state's cached palette strings. */
    public interface EncodedEntryHolder {
        Object superchunk$encodedEntry();

        void superchunk$setEncodedEntry(Object entry);
    }

    /** Also run the codec, compare the written bytes, keep the codec's tag. */
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.io.directPaletteEncode.verify");
    /** Time every save-path container encode, direct or codec (the kill switch picks which). */
    public static final boolean TIMING = Boolean.getBoolean("superchunk.io.directPaletteEncode.timing");
    private static final boolean STATS = VERIFY || TIMING || Boolean.getBoolean("superchunk.io.directPaletteEncode.stats");
    private static final LongAdder COPIED = new LongAdder();
    private static final LongAdder REPACKED = new LongAdder();
    private static final LongAdder RESIZED = new LongAdder();
    private static final LongAdder TIMED = new LongAdder();
    private static final LongAdder TIMED_NANOS = new LongAdder();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SuperChunk-FastPalette");
    private static final LongAdder DIRECT = new LongAdder();
    private static final LongAdder DECLINED = new LongAdder();
    private static final LongAdder CHECKED = new LongAdder();
    private static final LongAdder MISMATCHES = new LongAdder();
    private static final AtomicInteger REPORTED = new AtomicInteger();
    private static volatile boolean announced;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final String PALETTE = "palette";
    private static final String DATA = "data";
    private static final String NAME = "Name";
    private static final String PROPERTIES = "Properties";
    /** A block whose name the registry cannot give: the codec handles (and reports) it. */
    private static final Object UNNAMED = new Object();

    private DirectPaletteEncoder() {
    }

    /** The {@code block_states} tag of {@code container}, or {@code null} to use the codec. */
    public static CompoundTag blockStates(PalettedContainer<BlockState> container) {
        Scratch scratch = SCRATCH.get();
        if (scratch.busy) {
            return null; // reentered from a palette callback: leave it to the codec
        }
        scratch.busy = true;
        container.acquire();
        try {
            if (!pack(container.data, Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES, scratch)) {
                return null;
            }
            Object[] entries = scratch.entries;
            int count = scratch.count;
            Entry[] encoded = scratch.blockEntries(count);
            for (int i = 0; i < count; i++) {
                Entry entry = entryFor((BlockState) entries[i]);
                if (entry == null) {
                    return null;
                }
                encoded[i] = entry;
            }
            ListTag palette = new ListTag();
            for (int i = 0; i < count; i++) {
                palette.add(encoded[i].tag());
            }
            return containerTag(palette, scratch.raw);
        } finally {
            scratch.clear();
            container.release();
            scratch.busy = false;
        }
    }

    /** The {@code biomes} tag of {@code container}, or {@code null} to use the codec. */
    public static CompoundTag biomes(PalettedContainerRO<Holder<Biome>> container, Registry<Biome> registry) {
        if (!(container instanceof PalettedContainer<Holder<Biome>> biomes)) {
            return null;
        }
        Scratch scratch = SCRATCH.get();
        if (scratch.busy) {
            return null;
        }
        scratch.busy = true;
        biomes.acquire();
        try {
            if (!pack(biomes.data, registry.asHolderIdMap(), PalettedContainer.Strategy.SECTION_BIOMES, scratch)) {
                return null;
            }
            Object[] entries = scratch.entries;
            int count = scratch.count;
            StringTag[] names = scratch.biomeNames(count);
            for (int i = 0; i < count; i++) {
                @SuppressWarnings("unchecked")
                StringTag name = scratch.biomeName((Holder<Biome>) entries[i], registry);
                if (name == null) {
                    return null;
                }
                names[i] = name;
            }
            ListTag palette = new ListTag();
            for (int i = 0; i < count; i++) {
                palette.add(names[i]);
            }
            return containerTag(palette, scratch.raw);
        } finally {
            scratch.clear();
            biomes.release();
            scratch.busy = false;
        }
    }

    /** A container the codec did not have to encode. */
    public static void noteDirect() {
        if (!announced) {
            announced = true;
            LOGGER.info("[SuperChunk-FastPalette] chunk saves encode block and biome palettes directly (no codec).");
        }
        if (STATS) {
            DIRECT.increment();
        }
    }

    /** A container left to the codec. */
    public static void noteDeclined() {
        if (STATS) {
            DECLINED.increment();
        }
    }

    /** Verify mode: {@code direct} must write the same bytes as the codec's tag {@code expected}. */
    public static void verify(CompoundTag direct, Object expected) {
        boolean same = expected instanceof Tag tag && Arrays.equals(bytes(direct), bytes(tag));
        CHECKED.increment();
        if (!same) {
            MISMATCHES.increment();
            if (REPORTED.getAndIncrement() < 10) {
                LOGGER.warn("[SuperChunk-FastPalette] direct palette encode VERIFY MISMATCH: direct={} codec={}", direct, expected);
            }
        }
    }

    /** Timing mode: one container encoded, either way. */
    public static void time(long nanos) {
        TIMED.increment();
        TIMED_NANOS.add(nanos);
    }

    /** At server stop: how many containers went which way, and the verify result. */
    public static void report() {
        if (!STATS) {
            return;
        }
        LOGGER.info("[SuperChunk-FastPalette] direct palette encode: direct={} codec={} (words copied={} renumbered={} resized={})",
                DIRECT.sum(), DECLINED.sum(), COPIED.sum(), REPACKED.sum(), RESIZED.sum());
        if (TIMING) {
            long timed = TIMED.sum();
            LOGGER.info("[SuperChunk-FastPalette] palette encode timing: containers={} mean={} ns",
                    timed, timed == 0 ? 0 : TIMED_NANOS.sum() / timed);
        }
        if (VERIFY) {
            long mismatches = MISMATCHES.sum();
            LOGGER.info("[SuperChunk-FastPalette] direct palette encode VERIFY: checked={} mismatches={} -> {}",
                    CHECKED.sum(), mismatches, mismatches == 0 ? "PASS" : "FAIL");
        }
    }

    private static byte[] bytes(Tag tag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        try (DataOutputStream data = new DataOutputStream(out)) {
            tag.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static CompoundTag containerTag(ListTag palette, long[] raw) {
        CompoundTag tag = new CompoundTag();
        tag.put(PALETTE, palette);
        if (raw != null) {
            // Our own SimpleBitStorage's words: nothing else holds them (the codec copies its stream into a new array).
            tag.put(DATA, new LongArrayTag(raw));
        }
        return tag;
    }

    /**
     * Vanilla's {@code pack} into {@code scratch}: {@code entries[0..count)} in first-met order and
     * {@code raw} the storage words, or null when the bits are 0. False for an unknown layout.
     */
    private static <T> boolean pack(PalettedContainer.Data<T> data, IdMap<T> registry, PalettedContainer.Strategy strategy,
                                    Scratch scratch) {
        int size = strategy.size();
        Class<?> storageClass = data.storage().getClass();
        Class<?> paletteClass = data.palette().getClass();
        if ((storageClass != ZeroBitStorage.class && storageClass != SimpleBitStorage.class)
                || data.storage().getSize() != size
                || (paletteClass != SingleValuePalette.class && paletteClass != LinearPalette.class
                && paletteClass != HashMapPalette.class && paletteClass != GlobalPalette.class
                && paletteClass != LithiumHashPalette.class)) {
            return false;
        }
        if (size != 4096 && size != 64) {
            return false;
        }
        int generation = scratch.nextGeneration();
        Palette<T> palette = data.palette();
        if (storageClass == ZeroBitStorage.class) {
            // Every index holds id 0: one lookup, one value.
            scratch.remap(0, generation, scratch.numberOf(palette.valueFor(0)));
            int bits = strategy.calculateBitsForSerialization(registry, scratch.count);
            scratch.raw = bits == 0 ? null : new long[(size + 64 / bits - 1) / (64 / bits)]; // all zeros, as packed
            return true;
        }
        SimpleBitStorage storage = (SimpleBitStorage) data.storage();
        long[] words = storage.getRaw();
        int bits = storage.getBits();
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        // Pass 1: number the values in index order, looking each id up the first time it is met
        // (vanilla looks it up at every change of id; the lookup has no side effects).
        boolean identity = true;
        int lastId = -1;
        int index = 0;
        long[] seenWords = scratch.seenWords;
        int[] seenStamps = scratch.seenStamps;
        for (int w = 0; w < words.length; w++) {
            long word = words[w];
            int n = Math.min(perLong, size - index);
            if (n == perLong) {
                // A word met before in this section (uniform rows repeat whole words) has no new id.
                // lastId may then be an older id: it only saves a lookup, and that id was seen too.
                int slot = Scratch.slot(word);
                if (seenStamps[slot] == generation && seenWords[slot] == word) {
                    index += n;
                    continue;
                }
                seenWords[slot] = word;
                seenStamps[slot] = generation;
            }
            for (int k = 0; k < n; k++) {
                int id = (int) (word & mask);
                word >>>= bits;
                if (id != lastId) {
                    lastId = id;
                    if (!scratch.seen(id, generation)) {
                        int number = scratch.numberOf(palette.valueFor(id));
                        scratch.remap(id, generation, number);
                        identity &= number == id;
                    }
                }
            }
            index += n;
        }
        int outBits = strategy.calculateBitsForSerialization(registry, scratch.count);
        if (outBits == 0) {
            scratch.raw = null;
        } else if (identity && outBits == bits) {
            // Every value keeps its number at the same width: the words are the repacked ones, but for
            // bits past each word's last value, which a repack leaves clear.
            long[] raw = new long[words.length];
            long full = perLong * bits == 64 ? -1L : (1L << (perLong * bits)) - 1L;
            for (int w = 0; w < words.length - 1; w++) {
                raw[w] = words[w] & full;
            }
            int lastValues = size - (words.length - 1) * perLong;
            raw[words.length - 1] = words[words.length - 1] & (lastValues * bits == 64 ? -1L : (1L << (lastValues * bits)) - 1L);
            scratch.raw = raw;
            if (STATS) {
                COPIED.increment();
            }
        } else if (outBits == bits) {
            // Pass 2 at the same width: each word maps to the word at the same place, value by value
            // (SimpleBitStorage's layout: value k of a word at bit k * bits), and a word met before to
            // the same new word.
            long[] raw = new long[words.length];
            int[] remap = scratch.remap;
            long[] inWords = scratch.mappedIn;
            long[] outWords = scratch.mappedOut;
            int[] mappedStamps = scratch.mappedStamps;
            index = 0;
            for (int w = 0; w < words.length; w++) {
                long word = words[w];
                int n = Math.min(perLong, size - index);
                index += n;
                int slot = Scratch.slot(word);
                if (n == perLong && mappedStamps[slot] == generation && inWords[slot] == word) {
                    raw[w] = outWords[slot];
                    continue;
                }
                long packed = 0L;
                long rest = word;
                for (int k = 0; k < n; k++) {
                    packed |= (long) remap[(int) (rest & mask)] << (k * bits);
                    rest >>>= bits;
                }
                raw[w] = packed;
                if (n == perLong) {
                    inWords[slot] = word;
                    outWords[slot] = packed;
                    mappedStamps[slot] = generation;
                }
            }
            scratch.raw = raw;
            if (STATS) {
                REPACKED.increment();
            }
        } else {
            // Pass 2 at another width: write each value's number straight into the new words
            // (SimpleBitStorage's layout: value i in word i / perLong, at bit (i % perLong) * outBits).
            int outPerLong = 64 / outBits;
            long[] raw = new long[(size + outPerLong - 1) / outPerLong];
            int[] remap = scratch.remap;
            int out = 0;
            int filled = 0;
            long packed = 0L;
            index = 0;
            for (long word : words) {
                int n = Math.min(perLong, size - index);
                for (int k = 0; k < n; k++) {
                    packed |= (long) remap[(int) (word & mask)] << (filled * outBits);
                    word >>>= bits;
                    if (++filled == outPerLong) {
                        raw[out++] = packed;
                        packed = 0L;
                        filled = 0;
                    }
                }
                index += n;
            }
            if (filled > 0) {
                raw[out] = packed;
            }
            scratch.raw = raw;
            if (STATS) {
                RESIZED.increment();
            }
        }
        return true;
    }

    private static Entry entryFor(BlockState state) {
        EncodedEntryHolder holder = (EncodedEntryHolder) (Object) state;
        Object cached = holder.superchunk$encodedEntry();
        if (cached == null) {
            cached = buildEntry(state);
            holder.superchunk$setEncodedEntry(cached); // immutable; any thread may build the same one
        }
        return cached == UNNAMED ? null : (Entry) cached;
    }

    private static Object buildEntry(BlockState state) {
        Optional<ResourceKey<Block>> key = BuiltInRegistries.BLOCK.getResourceKey(state.getBlock());
        if (key.isEmpty()) {
            return UNNAMED;
        }
        StringTag name = StringTag.valueOf(key.get().location().toString());
        if (state.getValues().isEmpty()) {
            return new Entry(name, null, null);
        }
        // The codec nests one pair codec per property, in the definition's (name) order, and a pair
        // codec encodes its second half first: the properties are added last to first.
        Collection<Property<?>> properties = state.getBlock().getStateDefinition().getProperties();
        String[] keys = new String[properties.size()];
        StringTag[] values = new StringTag[properties.size()];
        int i = properties.size();
        for (Property<?> property : properties) {
            i--;
            keys[i] = property.getName();
            values[i] = StringTag.valueOf(valueName(state, property));
        }
        return new Entry(name, keys, values);
    }

    private static <V extends Comparable<V>> String valueName(BlockState state, Property<V> property) {
        return property.getName(state.getValue(property));
    }

    /** A block state's palette strings. */
    private record Entry(StringTag name, String[] keys, StringTag[] values) {
        CompoundTag tag() {
            CompoundTag tag = new CompoundTag();
            if (keys != null) {
                CompoundTag properties = new CompoundTag();
                for (int i = 0; i < keys.length; i++) {
                    properties.put(keys[i], values[i]);
                }
                tag.put(PROPERTIES, properties);
            }
            tag.put(NAME, name);
            return tag;
        }
    }

    private static final class Scratch {
        static final int WORD_SLOT_BITS = 7;
        static final int WORD_SLOTS = 1 << WORD_SLOT_BITS;
        /** Per source id: {@code remap[id]} is valid when {@code stamp[id] == generation}. */
        int[] stamp = new int[256];
        int[] remap = new int[256];
        int generation;
        Object[] entries = new Object[64];
        int count;
        long[] raw;
        /** Value to number, once a section has more values than a scan is cheap for. */
        Reference2IntOpenHashMap<Object> numbers;
        Entry[] blockEntries = new Entry[64];
        StringTag[] biomeNames = new StringTag[16];
        Registry<Biome> biomeRegistry;
        final Reference2ObjectOpenHashMap<Holder<Biome>, StringTag> biomeTags = new Reference2ObjectOpenHashMap<>();
        boolean busy;

        /** Recently met words of the section being packed (direct-mapped by a hash of the word). */
        final long[] seenWords = new long[WORD_SLOTS];
        final int[] seenStamps = new int[WORD_SLOTS];
        /** Same-width pass 2: a word's renumbered word. */
        final long[] mappedIn = new long[WORD_SLOTS];
        final long[] mappedOut = new long[WORD_SLOTS];
        final int[] mappedStamps = new int[WORD_SLOTS];

        static int slot(long word) {
            return (int) ((word * 0x9E3779B97F4A7C15L) >>> (64 - WORD_SLOT_BITS));
        }

        int nextGeneration() {
            if (++generation == Integer.MAX_VALUE) {
                Arrays.fill(stamp, 0);
                Arrays.fill(seenStamps, 0);
                Arrays.fill(mappedStamps, 0);
                generation = 1;
            }
            count = 0;
            numbers = null;
            return generation;
        }

        boolean seen(int id, int generation) {
            return id < stamp.length && stamp[id] == generation;
        }

        void remap(int id, int generation, int number) {
            if (id >= stamp.length) {
                int length = Math.max(id + 1, stamp.length * 2);
                stamp = Arrays.copyOf(stamp, length);
                remap = Arrays.copyOf(remap, length);
            }
            stamp[id] = generation;
            remap[id] = number;
        }

        /** The value's number: the next one if this is the first time it is met (by identity, as HashMapPalette numbers). */
        int numberOf(Object value) {
            if (numbers != null) {
                int number = numbers.getOrDefault(value, -1);
                if (number < 0) {
                    number = add(value);
                    numbers.put(value, number);
                }
                return number;
            }
            for (int i = 0; i < count; i++) {
                if (entries[i] == value) {
                    return i;
                }
            }
            int number = add(value);
            if (count > 32) {
                numbers = new Reference2IntOpenHashMap<>(count * 2);
                for (int i = 0; i < count; i++) {
                    numbers.put(entries[i], i);
                }
            }
            return number;
        }

        private int add(Object value) {
            if (count == entries.length) {
                entries = Arrays.copyOf(entries, count * 2);
            }
            entries[count] = value;
            return count++;
        }

        Entry[] blockEntries(int needed) {
            if (blockEntries.length < needed) {
                blockEntries = new Entry[Math.max(needed, blockEntries.length * 2)];
            }
            return blockEntries;
        }

        StringTag[] biomeNames(int needed) {
            if (biomeNames.length < needed) {
                biomeNames = new StringTag[Math.max(needed, biomeNames.length * 2)];
            }
            return biomeNames;
        }

        /**
         * The holder's key as the registry's name codec writes it (its {@code unwrapKey}, with the
         * holder's lifecycle looked up through its value), or null for a holder that is not a bound
         * reference of this registry.
         */
        StringTag biomeName(Holder<Biome> holder, Registry<Biome> registry) {
            if (registry != biomeRegistry || biomeTags.size() > 4096) {
                biomeTags.clear();
                biomeRegistry = registry;
            }
            StringTag tag = biomeTags.get(holder);
            if (tag == null) {
                if (!(holder instanceof Holder.Reference<Biome> reference) || !reference.isBound()
                        || !reference.canSerializeIn(registry.holderOwner())) {
                    return null;
                }
                Optional<ResourceKey<Biome>> key = reference.unwrapKey();
                if (key.isEmpty()) {
                    return null;
                }
                tag = StringTag.valueOf(key.get().location().toString());
                biomeTags.put(holder, tag);
            }
            return tag;
        }

        void clear() {
            Arrays.fill(entries, 0, count, null);
            Arrays.fill(blockEntries, 0, Math.min(count, blockEntries.length), null);
            Arrays.fill(biomeNames, 0, Math.min(count, biomeNames.length), null);
            raw = null;
            count = 0;
            numbers = null;
        }
    }
}
