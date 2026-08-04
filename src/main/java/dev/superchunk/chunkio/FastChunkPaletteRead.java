package dev.superchunk.chunkio;

import com.mojang.serialization.DataResult;
import dev.superchunk.mixin.IPalettedContainerUnpackAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * SuperChunk: <b>direct chunk-load deserialize of the block-state {@code PalettedContainer}</b>,
 * bypassing the DataFixerUpper codec + {@code BlockState.CODEC} boxing that vanilla
 * {@code ChunkSerializer.read} runs for every section of every loaded chunk.
 *
 * <p>C2ME's serializer rewrite accelerates chunk <i>writing</i> only; <i>loading</i> still runs the
 * vanilla codec (moved off-thread but not made cheaper). Per section it decodes each palette entry
 * through {@code BlockState.CODEC} — a {@code ResourceLocation.tryParse} + registry lookup + a
 * {@code Properties} map decode, all boxed through {@code DataResult}/{@code Optional}/{@code Pair}.
 *
 * <p>This reads the {@code "palette"} list and {@code "data"} long array straight out of the NBT and
 * rebuilds the same {@code List<BlockState>} + {@code Optional<LongStream>}
 * ({@code PackedData}), then hands them to the <b>unchanged vanilla</b>
 * {@link PalettedContainer} {@code unpack} (via {@link IPalettedContainerUnpackAccess}) so the palette
 * order, {@code SimpleBitStorage} build, and global-palette handling are byte-identical.
 *
 * <p><b>Bit-parity.</b> BlockStates are interned singletons: for a clean vanilla-saved entry the
 * direct decode ({@code Name} &rarr; block default state &rarr; each saved {@code Properties} value
 * applied) yields the identical instance the codec would. Any anomaly — missing/odd {@code Name},
 * unknown block, unknown property, unparseable value, {@code "data"} of the wrong type, or an empty
 * palette — makes {@link #readBlockStates} return {@code null}, and the caller falls back to the
 * verbatim vanilla codec. So the fast path is only ever taken when it provably equals vanilla; the
 * {@code unpack} it reuses is the same code the codec uses, so any storage error is identical too.
 * Kill switch {@code -Dsuperchunk.io.fastPaletteRead=false}.
 */
public final class FastChunkPaletteRead {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("SuperChunk-FastPalette");

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("superchunk.io.fastPaletteRead", "true"));

    /**
     * Diagnostic (default OFF): run BOTH the fast read and the vanilla codec on every section, compare
     * the resulting containers block-by-block, log any disagreement as a BUG, and RETURN THE VANILLA
     * result so output stays bit-identical while parity is proven. {@code BUGS=0} across a real world
     * load is the parity gate. Enable with {@code -Dsuperchunk.io.fastPaletteRead.verify=true}.
     */
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.io.fastPaletteRead.verify");

    private static final java.util.concurrent.atomic.AtomicLong READS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong FELLBACK = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong BUGS = new java.util.concurrent.atomic.AtomicLong();
    private static final long LOG_EVERY = 20_000L;

    private FastChunkPaletteRead() {
    }

    /**
     * Fast path for the {@code "block_states"} container of a section, or {@code null} to signal the
     * caller to fall back to the vanilla codec.
     */
    public static DataResult<PalettedContainer<BlockState>> readBlockStates(Object input) {
        if (!(ENABLED || VERIFY) || !(input instanceof CompoundTag tag)) {
            return null;
        }
        if (!tag.contains("palette", Tag.TAG_LIST)) {
            return null;
        }
        ListTag paletteNbt = tag.getList("palette", Tag.TAG_COMPOUND);
        int n = paletteNbt.size();
        if (n == 0) {
            return null;
        }
        List<BlockState> palette = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockState state = parseEntry(paletteNbt.getCompound(i));
            if (state == null) {
                return null; // anomaly -> vanilla codec
            }
            palette.add(state);
        }

        Optional<LongStream> storage;
        if (tag.contains("data", Tag.TAG_LONG_ARRAY)) {
            storage = Optional.of(LongStream.of(tag.getLongArray("data")));
        } else if (tag.contains("data")) {
            return null; // "data" present but not a long array -> vanilla codec
        } else {
            storage = Optional.empty();
        }

        PalettedContainerRO.PackedData<BlockState> packed = new PalettedContainerRO.PackedData<>(palette, storage);
        return IPalettedContainerUnpackAccess.superchunk$unpack(
                Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES, packed);
    }

    /** Decodes one palette entry exactly as {@code BlockState.CODEC} would, or {@code null} to fall back. */
    private static BlockState parseEntry(CompoundTag e) {
        if (!e.contains("Name", Tag.TAG_STRING)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(e.getString("Name"));
        if (id == null) {
            return null;
        }
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) {
            return null;
        }
        BlockState state = block.get().defaultBlockState();
        if (e.contains("Properties", Tag.TAG_COMPOUND)) {
            CompoundTag props = e.getCompound("Properties");
            StateDefinition<Block, BlockState> def = block.get().getStateDefinition();
            for (String key : props.getAllKeys()) {
                Property<?> property = def.getProperty(key);
                if (property == null || !props.contains(key, Tag.TAG_STRING)) {
                    return null;
                }
                state = applyProperty(state, property, props.getString(key));
                if (state == null) {
                    return null;
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.isEmpty() ? null : state.setValue(property, parsed.get());
    }

    /**
     * VERIFY mode: compare the fast read against the vanilla codec result for one section, logging any
     * disagreement as a BUG. Called from the mixin only when {@link #VERIFY} is on; the mixin returns
     * the vanilla result regardless, so output is unaffected while parity is measured.
     */
    public static void verifyAgainst(Object input, DataResult<PalettedContainer<BlockState>> vanilla) {
        DataResult<PalettedContainer<BlockState>> fast = readBlockStates(input);
        if (fast == null) {
            FELLBACK.incrementAndGet(); // fast path would have deferred to vanilla here anyway
            maybeLog();
            return;
        }
        Optional<PalettedContainer<BlockState>> fastOpt = fast.result();
        Optional<PalettedContainer<BlockState>> vanillaOpt = vanilla.result();
        boolean bug = false;
        if (fastOpt.isPresent() != vanillaOpt.isPresent()) {
            bug = true; // one succeeded, the other errored
        } else if (fastOpt.isPresent()) {
            PalettedContainer<BlockState> f = fastOpt.get();
            PalettedContainer<BlockState> v = vanillaOpt.get();
            outer:
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (f.get(x, y, z) != v.get(x, y, z)) { // interned singletons -> reference-equal
                            bug = true;
                            break outer;
                        }
                    }
                }
            }
        }
        READS.incrementAndGet();
        if (bug) {
            long n = BUGS.incrementAndGet();
            if (n <= 50) {
                LOGGER.warn("[fast-palette] BUG #{}: fast read disagrees with the vanilla codec", n);
            }
        }
        maybeLog();
    }

    private static void maybeLog() {
        long total = READS.get() + FELLBACK.get();
        if (total == 1L || total % LOG_EVERY == 0L) {
            LOGGER.info("[fast-palette] verify: reads={} fellback={} bugs={}",
                    READS.get(), FELLBACK.get(), BUGS.get());
        }
    }
}
