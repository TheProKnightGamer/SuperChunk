#!/usr/bin/env python3
"""Parity-check CompactConsume's actual Java loops without Minecraft/OpenCL startup.

Run with Java 21 on PATH: python3 tools/verify-compact-consume.py [--bench]
The optional timings are isolated loop microbenchmarks, not world-generation rates.
"""

import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--bench', action='store_true', help='also time baseline and optimized loops')
args = parser.parse_args()
source = (Path(__file__).resolve().parents[1] / 'src/main/java/dev/superchunk/gpu/dfc/CompactConsume.java').read_text()
classify = source[source.index('        int acc = 0;', source.index('private static Ctx prepare(')):source.index('        if ((acc & (PREP_ABSENT | PREP_UNKNOWN)) != 0)', source.index('private static Ctx prepare('))]
pack_start = source.index('            int firstId = ids[base] & 0x7F;', source.index('private static void writeSection('))
packed = source[pack_start:source.index('            sectionsBulk.increment();', pack_start)]
hist_start = source.index('            java.util.Arrays.fill(hist, 0);', source.index('private static void fastFill('))
histogram = source[hist_start:source.index('            int nonAirCount = 0;', hist_start)]
write_start = source.index('    private static void writeSection(')
write_end = source.index('\n    /**', write_start)
write_section = source[write_start:write_end]
fast_start = source.index('    private static final class FastScratch {')
fast_end = source.index('\n    /**', fast_start)
fast_fill = source[fast_start:fast_end]
height_start = source.index('    private static void heightmapsFromIds(')
height_end = source.index('\n    /**', height_start)
heightmaps = source[height_start:height_end]
baseline_heightmaps = heightmaps.replace('heightmapsFromIds(', 'baselineHeightmaps(')
for array in ('surfB', 'surfY', 'floorB', 'floorY'):
    baseline_heightmaps = baseline_heightmaps.replace('scratch.' + array, 'new int[256]')
marks_start = source.index('    private static void marksFromIds(')
marks_end = source.index('\n    // ===', marks_start)
marks = source[marks_start:marks_end]
program = '''
import java.lang.invoke.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

public class CompactConsumeCheck {
    static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    static final byte[] PREP_CLASS = new byte[256];
    static final int PREP_SENTINEL = 4;
    static volatile long sink;
    static {
        for (int raw = 0; raw < 256; raw++) {
            int id = raw & 0x7f;
            PREP_CLASS[raw] = (byte) (raw == 255 ? 1 : id > 10 || (id == 10 && raw != 10) ? 2 : id == 10 ? 4 : 0);
        }
    }
    static int oldScan(byte[] ids) {
        int acc = 0;
        for (byte b : ids) acc |= PREP_CLASS[b & 255];
        return acc;
    }
    static int newScan(byte[] ids) {
''' + classify + '''
        return acc;
    }
    static void oldPack(byte[] ids, int base, int[] hist, int[] pid, long[] raw) {
        for (int y = 0; y < 16; y++) {
            int yRow = base + y * 256;
            for (int z = 0; z < 16; z++) {
                long word = 0;
                for (int x = 15; x >= 0; x--) word = (word << 4) | pid[ids[yRow + x * 16 + z] & 0x7f];
                raw[y * 16 + z] = word;
            }
        }
    }
    static void newPack(byte[] ids, int base, int[] hist, int[] pid, long[] raw) {
''' + packed + '''
    }
    static void newHistogram(byte[] ids, int base, int[] hist) {
''' + histogram + '''
    }
    static void oldHistogram(byte[] ids, int base, int[] hist) {
        Arrays.fill(hist, 0);
        for (int i = 0; i < 4096; i++) hist[ids[base + i] & 0x7f]++;
    }
    // Minimal deterministic section/palette models let the production writer run
    // unchanged. This exercises counters and fallback control flow; a real-world
    // generation/hash comparison is still needed for Minecraft integration parity.
    record BlockState(boolean air, boolean fluid, boolean ticking, int flags) {}
    static final BlockState AIR = new BlockState(true, false, false, 1);
    static final BlockState[] ALPHABET = {
        new BlockState(false, false, false, 2), AIR,
        new BlockState(false, true, false, 4), new BlockState(false, true, true, 8),
        new BlockState(false, false, true, 3), new BlockState(false, false, false, 5),
        new BlockState(false, false, false, 6), new BlockState(false, false, false, 7),
        new BlockState(false, false, true, 9), new BlockState(false, false, false, 10), null
    };
    static final LongAdder sectionsSlowWrite = new LongAdder();
    static final LongAdder sectionsBulk = new LongAdder();
    static final LongAdder sectionsSetLoop = new LongAdder();
    static class Mapping {
        final BlockState[] states = ALPHABET.clone();
        final boolean[] nonAir = new boolean[11], fluid = new boolean[11], ticking = new boolean[11];
        final boolean[] surf = new boolean[11], floor = new boolean[11];
        Mapping(BlockState defaultBlock) {
            states[0] = defaultBlock;
            for (int i = 0; i < 10; i++) {
                nonAir[i] = states[i] != AIR;
                fluid[i] = nonAir[i] && states[i].fluid;
                ticking[i] = nonAir[i] && states[i].ticking;
                surf[i] = nonAir[i];
                floor[i] = nonAir[i] && !states[i].fluid;
            }
        }
    }
    interface BitStorage {
        int getBits(); long[] getRaw(); int get(int i); void set(int i, int value);
    }
    static class SimpleBitStorage implements BitStorage {
        final long[] raw = new long[256];
        public int getBits() { return 4; }
        public long[] getRaw() { return raw; }
        public int get(int i) { return (int)(raw[i >>> 4] >>> ((i & 15) << 2)) & 15; }
        public void set(int i, int value) {
            int shift = (i & 15) << 2;
            raw[i >>> 4] = (raw[i >>> 4] & ~(15L << shift)) | ((long)value << shift);
        }
    }
    static class OtherBitStorage implements BitStorage {
        final int bits; final int[] values = new int[4096];
        OtherBitStorage(int bits) { this.bits = bits; }
        public int getBits() { return bits; }
        public long[] getRaw() { throw new AssertionError("not a 4-bit storage"); }
        public int get(int i) { return values[i]; }
        public void set(int i, int value) {
            if (bits == 0 && value != 0) throw new AssertionError("palette resize did not replace data");
            values[i] = value;
        }
    }
    static class PalettedContainer<T> {
        record Data<T>(BitStorage storage, Palette<T> palette) {}
        static class Strategy {
            int getIndex(int x, int y, int z) { return (y << 8) | (z << 4) | x; }
        }
        static class Palette<T> {
            final PalettedContainer<T> owner;
            final List<T> values = new ArrayList<>();
            Palette(PalettedContainer<T> owner, T initial) { this.owner = owner; values.add(initial); }
            T valueFor(int id) { return values.get(id); }
            int idFor(T state) {
                for (int i = 0; i < values.size(); i++) if (values.get(i) == state) return i;
                if (owner.data.storage.getBits() == 0) {
                    owner.data = new Data<>(owner.wide ? new OtherBitStorage(5) : new SimpleBitStorage(), this);
                }
                values.add(state);
                return values.size() - 1;
            }
        }
        Data<T> data;
        final Strategy strategy = new Strategy();
        final boolean wide;
        PalettedContainer(T initial, boolean wide) {
            this.wide = wide;
            data = new Data<>(new OtherBitStorage(0), new Palette<>(this, initial));
        }
    }
    static class LevelChunkSection {
        final PalettedContainer<BlockState> states;
        short nonEmptyBlockCount, tickingFluidCount, tickingBlockCount;
        final int[] flags = new int[4];
        LevelChunkSection(boolean wide) {
            states = new PalettedContainer<>(AIR, wide);
            for (int f = 0; f < 4; f++) if ((AIR.flags & (1 << f)) != 0) flags[f] = 4096;
        }
        BlockState getBlockState(int x, int y, int z) {
            return states.data.palette().valueFor(states.data.storage().get(states.strategy.getIndex(x, y, z)));
        }
        void setBlockState(int x, int y, int z, BlockState state, boolean lock) {
            BlockState before = getBlockState(x, y, z);
            int pid = states.data.palette().idFor(state);
            states.data.storage().set(states.strategy.getIndex(x, y, z), pid);
            nonEmptyBlockCount += (short)((state != AIR ? 1 : 0) - (before != AIR ? 1 : 0));
            tickingFluidCount += (short)((state.fluid ? 1 : 0) - (before.fluid ? 1 : 0));
            tickingBlockCount += (short)((state.ticking ? 1 : 0) - (before.ticking ? 1 : 0));
            if (LithiumBlockTracking.active()) LithiumBlockTracking.trackBulk(this, state, before, 1);
        }
    }
    static class LithiumBlockTracking {
        static boolean enabled = true;
        static boolean active() { return enabled; }
        static void trackBulk(LevelChunkSection section, BlockState state, BlockState before, int count) {
            for (int f = 0; f < 4; f++) {
                section.flags[f] += count * (((state.flags >>> f) & 1) - ((before.flags >>> f) & 1));
            }
        }
    }
''' + write_section + '''
    static class Ctx {
        byte[] ids;
        int ox = -32, oy = -64, oz = 48, fullY;
        Ctx(byte[] ids) { this.ids = ids; fullY = ids.length / 256; }
    }
    record Update(Heightmap.Types type, int x, int y, int z, BlockState state) {}
    record Mark(int x, int y, int z) {}
    static class Heightmap {
        enum Types { OCEAN_FLOOR_WG, WORLD_SURFACE_WG }
        final Types type;
        final List<Update> updates;
        Runnable beforeUpdate;
        boolean record = true;
        long checksum;
        Heightmap(Types type, List<Update> updates) { this.type = type; this.updates = updates; }
        void update(int x, int y, int z, BlockState state) {
            Runnable callback = beforeUpdate;
            beforeUpdate = null;
            if (callback != null) callback.run();
            if (record) updates.add(new Update(type, x, y, z, state));
            checksum += x + y * 17L + z * 257L + state.flags;
        }
    }
    static class BlockPos {
        static class MutableBlockPos {
            int x, y, z;
            void set(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        }
    }
    static class ChunkAccess {
        final LevelChunkSection[] sections;
        final int minY;
        final List<Update> updates = new ArrayList<>();
        final List<Mark> marks = new ArrayList<>();
        final Heightmap floor = new Heightmap(Heightmap.Types.OCEAN_FLOOR_WG, updates);
        final Heightmap surf = new Heightmap(Heightmap.Types.WORLD_SURFACE_WG, updates);
        boolean record = true;
        long markChecksum;
        ChunkAccess(Ctx ctx) {
            minY = ctx.oy;
            sections = new LevelChunkSection[ctx.fullY / 16];
            for (int i = 0; i < sections.length; i++) sections[i] = new LevelChunkSection(false);
        }
        Heightmap getOrCreateHeightmapUnprimed(Heightmap.Types type) { return type == floor.type ? floor : surf; }
        int getSectionIndex(int y) { return (y - minY) >> 4; }
        LevelChunkSection getSection(int i) { return sections[i]; }
        void markPosForPostprocessing(BlockPos.MutableBlockPos pos) {
            if (record) marks.add(new Mark(pos.x, pos.y, pos.z));
            markChecksum += pos.x + pos.y * 17L + pos.z * 257L;
        }
    }
''' + fast_fill + heightmaps + baseline_heightmaps + marks + '''
    static void referenceSideEffects(ChunkAccess chunk, Ctx ctx, Mapping m) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockState surface = null;
            int surfaceY = 0;
            for (int y = ctx.fullY - 1; y >= 0; y--) {
                int b = ctx.ids[y * 256 + x * 16 + z] & 0x7f;
                if (surface == null && m.surf[b]) { surface = m.states[b]; surfaceY = ctx.oy + y; }
                if (m.floor[b]) { chunk.floor.update(x, ctx.oy + y, z, m.states[b]); break; }
            }
            if (surface != null) chunk.surf.update(x, surfaceY, z, surface);
        }
        for (int i = 0; i < ctx.ids.length; i++) {
            int b = ctx.ids[i] & 0x7f;
            if (ctx.ids[i] < 0 && m.nonAir[b] && m.fluid[b]) {
                chunk.marks.add(new Mark(ctx.ox + ((i >> 4) & 15), ctx.oy + (i >> 8), ctx.oz + (i & 15)));
            }
        }
    }
    static void checkFastFill(Random r) {
        for (int trial = 0; trial < 120; trial++) {
            int height = trial % 3 == 0 ? 384 : trial % 3 == 1 ? 64 : 16;
            byte[] ids = new byte[height * 256];
            for (int i = 0; i < ids.length; i++) {
                int y = i >> 8;
                int b = switch (trial % 6) {
                    case 0 -> 1; // entirely air, including scheduled AIR bytes
                    case 1 -> y < height / 2 ? 2 : 1; // water only, no floor hit
                    case 2 -> y < height / 3 || y >= height * 2 / 3 ? 1 : r.nextInt(10);
                    case 3 -> y >= 96 ? 1 : r.nextInt(10);
                    case 4 -> r.nextInt(10);
                    default -> 0; // default block can itself be air or fluid
                };
                ids[i] = (byte)(b | (r.nextBoolean() ? 128 : 0));
            }
            Ctx ctx = new Ctx(ids);
            Mapping mapping = new Mapping(ALPHABET[trial % 10]);
            ChunkAccess expected = new ChunkAccess(ctx), actual = new ChunkAccess(ctx);
            referenceSideEffects(expected, ctx, mapping);
            fastFill(actual, ctx, mapping);
            if (!expected.updates.equals(actual.updates)) throw new AssertionError("heightmap call/order mismatch " + trial);
            if (!expected.marks.equals(actual.marks)) throw new AssertionError("postprocess mark/order mismatch " + trial);
            if (FAST_SCRATCH.get().inUse) throw new AssertionError("scratch remained borrowed " + trial);
        }
        byte[] outerIds = new byte[64 * 256];
        Arrays.fill(outerIds, (byte)1);
        Arrays.fill(outerIds, 0, 25 * 256, (byte)0);
        Ctx outer = new Ctx(outerIds), inner = new Ctx(new byte[16 * 256]);
        Mapping mapping = new Mapping(ALPHABET[0]);
        ChunkAccess expected = new ChunkAccess(outer), actual = new ChunkAccess(outer);
        referenceSideEffects(expected, outer, mapping);
        actual.floor.beforeUpdate = () -> {
            if (!FAST_SCRATCH.get().inUse) throw new AssertionError("outer scratch not protected");
            fastFill(new ChunkAccess(inner), inner, mapping);
            if (!FAST_SCRATCH.get().inUse) throw new AssertionError("nested fill released outer scratch");
        };
        fastFill(actual, outer, mapping);
        if (!actual.updates.equals(expected.updates)) throw new AssertionError("nested fill overwrote outer heights");
        ChunkAccess failing = new ChunkAccess(outer);
        failing.floor.beforeUpdate = () -> { throw new IllegalStateException("deliberate callback failure"); };
        try { fastFill(failing, outer, mapping); throw new AssertionError("callback failure not propagated"); }
        catch (IllegalStateException expectedFailure) { /* finally must release scratch */ }
        if (FAST_SCRATCH.get().inUse) throw new AssertionError("exception leaked scratch lease");
        ChunkAccess retry = new ChunkAccess(outer);
        fastFill(retry, outer, mapping);
        if (!retry.updates.equals(expected.updates)) throw new AssertionError("stale scratch after failure");
    }
    static void checkSections(Random r) {
        int[] hist = new int[11], pid = new int[11];
        byte[] ids = new byte[8192];
        for (int trial = 0; trial < 500; trial++) {
            Mapping mapping = new Mapping(ALPHABET[trial % 10]);
            LithiumBlockTracking.enabled = trial % 5 != 0;
            LevelChunkSection expected = new LevelChunkSection(trial % 4 == 0);
            LevelChunkSection actual = new LevelChunkSection(trial % 4 == 0);
            if (trial % 3 == 0) {
                for (int i = 0; i < 100; i++) {
                    int x = r.nextInt(16), y = r.nextInt(16), z = r.nextInt(16);
                    BlockState state = ALPHABET[r.nextInt(10)];
                    expected.setBlockState(x, y, z, state, false);
                    actual.setBlockState(x, y, z, state, false);
                }
            }
            int fixed = (trial / 2) % 10;
            for (int i = 4096; i < 8192; i++) {
                ids[i] = (byte)((trial % 2 == 0 ? fixed : r.nextInt(10)) | (r.nextBoolean() ? 128 : 0));
            }
            newHistogram(ids, 4096, hist);
            int nonAirCount = 0;
            for (int b = 0; b < 10; b++) if (mapping.nonAir[b]) nonAirCount += hist[b];
            // The production caller skips all-air sections before calling writeSection.
            if (nonAirCount != 0) writeSection(actual, ids, 4096, hist, pid, mapping, nonAirCount);
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int b = ids[4096 + (y * 16 + x) * 16 + z] & 0x7f;
                if (mapping.nonAir[b]) expected.setBlockState(x, y, z, mapping.states[b], false);
            }
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                if (actual.getBlockState(x, y, z) != expected.getBlockState(x, y, z)) throw new AssertionError("section block mismatch " + trial);
            }
            if (actual.nonEmptyBlockCount != expected.nonEmptyBlockCount
                    || actual.tickingBlockCount != expected.tickingBlockCount
                    || actual.tickingFluidCount != expected.tickingFluidCount
                    || !Arrays.equals(actual.flags, expected.flags)) throw new AssertionError("section counter mismatch " + trial);
        }
        if (sectionsSlowWrite.sum() == 0 || sectionsBulk.sum() == 0 || sectionsSetLoop.sum() == 0) {
            throw new AssertionError("section writer branch missing from test");
        }
    }
    static void check(byte[] ids) {
        if (oldScan(ids) != newScan(ids)) throw new AssertionError("scan mismatch " + Arrays.toString(ids));
    }
    static long benchScan(byte[][] chunks, boolean opt, int reps) {
        long result = 0;
        long start = System.nanoTime();
        for (int i = 0; i < reps; i++) for (byte[] ids : chunks) result += opt ? newScan(ids) : oldScan(ids);
        long elapsed = System.nanoTime() - start;
        sink = result;
        return elapsed / (chunks.length * reps);
    }
    static long benchPack(byte[][] sections, boolean opt, int reps, int[][] hist, int[] pid, long[] raw) {
        long result = 0;
        long start = System.nanoTime();
        for (int i = 0; i < reps; i++) for (int k = 0; k < sections.length; k++) {
            if (opt) newPack(sections[k], 0, hist[k], pid, raw); else oldPack(sections[k], 0, hist[k], pid, raw);
            result += raw[i & 255];
        }
        long elapsed = System.nanoTime() - start;
        sink = result;
        return elapsed / (sections.length * reps);
    }
    static long[] benchSideEffects(Ctx[] contexts, ChunkAccess[] chunks, Mapping mapping, boolean opt, int reps) {
        com.sun.management.ThreadMXBean thread = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        FastScratch scratch = FAST_SCRATCH.get();
        long allocated = thread.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long start = System.nanoTime();
        for (int i = 0; i < reps; i++) for (int k = 0; k < contexts.length; k++) {
            Ctx ctx = contexts[k]; ChunkAccess chunk = chunks[k];
            if (opt) heightmapsFromIds(ctx, mapping, chunk.surf, chunk.floor, 0, 144, scratch);
            else baselineHeightmaps(ctx, mapping, chunk.surf, chunk.floor, 0, ctx.fullY, null);
            marksFromIds(chunk, ctx, mapping, 0, opt ? 144 : ctx.fullY);
        }
        long elapsed = System.nanoTime() - start;
        allocated = thread.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated;
        sink = chunks[0].surf.checksum + chunks[0].floor.checksum + chunks[0].markChecksum;
        return new long[]{elapsed / (contexts.length * reps), allocated / (contexts.length * reps)};
    }
    public static void main(String[] args) {
        Random r = new Random(214711);
        byte[] word = new byte[8];
        // Every raw byte in every lane, with valid bytes in all adjacent lanes.
        for (int lane = 0; lane < 8; lane++) for (int raw = 0; raw < 256; raw++) {
            Arrays.fill(word, (byte) 137); word[lane] = (byte) raw; check(word);
        }
        // Every adjacent raw-byte pair, to detect cross-byte carry mistakes.
        for (int lane = 0; lane < 7; lane++) for (int pair = 0; pair < 65536; pair++) {
            Arrays.fill(word, (byte) 0); word[lane] = (byte) pair; word[lane + 1] = (byte) (pair >>> 8); check(word);
        }
        for (int trial = 0; trial < 50000; trial++) {
            byte[] ids = new byte[(r.nextInt(128) + 1) * 8]; r.nextBytes(ids); check(ids);
        }
        int[] hist = new int[11]; int[] pid = new int[11];
        int[] histogramActual = new int[11];
        long[] expected = new long[256], actual = new long[256];
        byte[] ids = new byte[8192];
        for (int trial = 0; trial < 3000; trial++) {
            Arrays.fill(hist, 0);
            int fixed = r.nextInt(10);
            for (int i = 4096; i < 8192; i++) {
                int id = trial % 2 == 0 ? fixed : r.nextInt(10);
                ids[i] = (byte) (id | (r.nextBoolean() ? 128 : 0)); hist[id]++;
            }
            for (int b = 0; b < 11; b++) pid[b] = r.nextInt(16);
            newHistogram(ids, 4096, histogramActual);
            if (!Arrays.equals(hist, histogramActual)) throw new AssertionError("histogram mismatch");
            oldPack(ids, 4096, hist, pid, expected); newPack(ids, 4096, hist, pid, actual);
            if (!Arrays.equals(expected, actual)) throw new AssertionError("packing mismatch");
        }
        // Cover every prefix/suffix boundary, including the last seven bytes.
        for (int split = 0; split <= 4096; split++) {
            for (int i = 4096; i < 8192; i++) ids[i] = (byte) ((i - 4096 < split ? 0 : 1) | (i % 2 == 0 ? 128 : 0));
            oldHistogram(ids, 4096, hist); newHistogram(ids, 4096, histogramActual);
            if (!Arrays.equals(hist, histogramActual)) throw new AssertionError("prefix histogram mismatch " + split);
        }
        checkSections(r);
        checkFastFill(r);
        System.out.println("PASS: 510,800 scanner, 7,097 histogram, 3,000 packed-section and 500 section-write parity cases (source-extracted implementation).");
        System.out.println("PASS: 120 complete fill heightmap/mark cases, nested fill isolation and exception cleanup.");
        if (args.length == 0) return;
        Ctx[] contexts = new Ctx[32]; ChunkAccess[] sideChunks = new ChunkAccess[contexts.length];
        for (int k = 0; k < contexts.length; k++) {
            byte[] chunkIds = new byte[384 * 256];
            Arrays.fill(chunkIds, (byte)1);
            for (int i = 0; i < 144 * 256; i++) chunkIds[i] = (byte)(r.nextInt(10) | (r.nextInt(1000) == 0 ? 128 : 0));
            contexts[k] = new Ctx(chunkIds);
            sideChunks[k] = new ChunkAccess(contexts[k]);
            sideChunks[k].record = sideChunks[k].surf.record = sideChunks[k].floor.record = false;
        }
        Mapping standard = new Mapping(ALPHABET[0]);
        benchSideEffects(contexts, sideChunks, standard, false, 50);
        benchSideEffects(contexts, sideChunks, standard, true, 50);
        long[] sideOld = new long[7], sideNew = new long[7];
        long oldBytes = 0, newBytes = 0;
        for (int round = 0; round < 7; round++) {
            long[] a = benchSideEffects(contexts, sideChunks, standard, false, 30);
            long[] b = benchSideEffects(contexts, sideChunks, standard, true, 30);
            sideOld[round] = a[0]; sideNew[round] = b[0]; oldBytes = a[1]; newBytes = b[1];
        }
        Arrays.sort(sideOld); Arrays.sort(sideNew);
        System.out.printf("heightmaps/marks old=%d ns/chunk new=%d ns/chunk speedup=%.2fx allocated=%d -> %d B/chunk%n", sideOld[3], sideNew[3], (double)sideOld[3]/sideNew[3], oldBytes, newBytes);
        for (String type : new String[]{"ordinary", "sparse-sentinel", "all-sentinel"}) {
            byte[][] chunks = new byte[32][98304];
            for (byte[] chunk : chunks) for (int i = 0; i < chunk.length; i++) {
                int b = r.nextInt(10) | (r.nextBoolean() ? 128 : 0);
                if (type.equals("all-sentinel") || (type.equals("sparse-sentinel") && i % 1024 == 0)) b = 10;
                chunk[i] = (byte) b;
            }
            benchScan(chunks, false, 50); benchScan(chunks, true, 50);
            long[] oldNs = new long[7], newNs = new long[7];
            for (int round = 0; round < 7; round++) {
                oldNs[round] = benchScan(chunks, false, 30); newNs[round] = benchScan(chunks, true, 30);
            }
            Arrays.sort(oldNs); Arrays.sort(newNs);
            System.out.printf("scan %-16s old=%d ns/chunk new=%d ns/chunk speedup=%.2fx%n", type, oldNs[3], newNs[3], (double)oldNs[3] / newNs[3]);
        }
        for (String type : new String[]{"uniform", "mixed"}) {
            byte[][] sections = new byte[64][4096]; int[][] hists = new int[64][11];
            for (int k = 0; k < sections.length; k++) for (int i = 0; i < 4096; i++) {
                int b = type.equals("uniform") ? k % 10 : r.nextInt(10);
                sections[k][i] = (byte) (b | (r.nextBoolean() ? 128 : 0)); hists[k][b]++;
            }
            benchPack(sections, false, 200, hists, pid, actual); benchPack(sections, true, 200, hists, pid, actual);
            long[] oldNs = new long[7], newNs = new long[7];
            for (int round = 0; round < 7; round++) {
                oldNs[round] = benchPack(sections, false, 100, hists, pid, actual);
                newNs[round] = benchPack(sections, true, 100, hists, pid, actual);
            }
            Arrays.sort(oldNs); Arrays.sort(newNs);
            System.out.printf("pack %-16s old=%d ns/section new=%d ns/section speedup=%.2fx%n", type, oldNs[3], newNs[3], (double)oldNs[3] / newNs[3]);
        }
    }
}
'''

with tempfile.TemporaryDirectory(prefix='superchunk-compact-check-') as directory:
    out = Path(directory) / 'CompactConsumeCheck.java'
    out.write_text(program)
    subprocess.run(['java', str(out)] + (['--bench'] if args.bench else []), check=True)
