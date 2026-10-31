# Chunk saves: block and biome palettes encoded directly (2026-10-30)

## What it replaces

Every chunk save runs vanilla `ChunkSerializer.write`. C2ME's gc-free serializer stays off in
SuperChunk because it drops NeoForge's chunk attachments and `ChunkDataEvent.Save`. For each
section, `write` encodes two `PalettedContainer`s through DataFixerUpper:

- `PalettedContainer.pack`:
  - unpacks the 4096 (or 64) ids into an `int[]`;
  - renumbers them through a new `HashMapPalette`, which is sized by the section's *storage* bits and
    read back through Guava's filtered iterator;
  - repacks them into a `SimpleBitStorage`.
- A record codec builds `{palette, data}`, with `data` as a `LongStream` copied into a new array.
- Per palette entry, the block-state dispatch codec encodes `Name` plus a map codec with one pair codec
  per property. Every step goes through `DataResult`s and NbtOps builders.

## What it does now

`MixinChunkSerializerDirectEncode` wraps the `encodeStart` calls in `write`. It takes only two of them:
- the one whose codec is vanilla's `BLOCK_STATE_CODEC`;
- the one whose codec is the biome codec that `makeBiomeCodec` last returned on this thread.

`chunkio/DirectPaletteEncoder` builds those two tags directly.

**The packing reads and writes packed words.** It runs in two passes over the section's own long array.
- **Pass 1** numbers the ids in index order. Each first-met id is looked up through the section's
  `valueFor`, and values are numbered by identity, as `HashMapPalette` numbers them.
- **Pass 2** writes the numbers straight into the new words:
  - at the same width, word for word;
  - at another width, value by value.
- If every value keeps its number at the same width, the words are copied, with the bits past each
  word's last value cleared as a repack leaves them.
- Uniform stone, deepslate and air rows repeat whole words. A 128-slot table keyed by a hash of the
  word remembers the words already met in the section:
  - pass 1 skips them, since they hold no new id;
  - same-width pass 2 reuses their output word.

**The tags are built from cached strings.** Each `BlockState` builds its `Name` and property strings
once, in a field added by `MixinBlockStateEncodedEntry`, and each biome holder builds its key once.
Their immutable `StringTag`s are reused. Compounds and lists are new on every save.

**Keys go in in the codec's order.** A compound's map writes colliding keys in insertion order, so:
- DFU's dispatch codec adds `Properties` before `Name`;
- a pair codec encodes its second half first, so properties go in last to first.

The first verify run missed the property order: 8,010 of 1.19M containers differed in bytes, but
not as SNBT.

**Anything else takes the codec unchanged:**
- an unknown palette or storage class;
- a block the registry does not name;
- a biome holder that is not a registered reference;
- a re-entrant call.

The encoder stands down when another mod hooks `PalettedContainer.pack` or `HashMapPalette`. The probe
mixin now covers `HashMapPalette`, and Forge has the SRG names.

## Exactness

`-Dsuperchunk.io.directPaletteEncode.verify=true` also runs the codec, compares the written bytes of
both tags, and keeps the codec's.

| Run (Chunky, 11 workers) | Containers compared | Mismatches |
|---|---|---|
| Neo r1024 overworld, GPU off | 1,194,192 | 0 |
| Neo r1024 overworld, GPU on | 1,174,704 | 0 |
| Neo r512 overworld + r384 nether + r384 end, GPU off | 642,192 | 0 |
| Forge 1.20.1 r1024 overworld + r384 nether + r384 end, GPU off | 801,200 | 0 |
| Forge 1.20.1, same, GPU on | 1,161,872 | 0 |

Every container went the direct way; none fell back to the codec.

The counts are the ones reported at server stop. The saves at shutdown come after the report, but
each run's whole log has no mismatch line, so those saves matched too.

How the multi-value containers were packed:
- words copied: about 15,000;
- renumbered at the same width: about 182,000;
- at another width: about 87,000.

The other roughly 900,000 hold a single value. At another width, the section's palette still lists
blocks that generation later replaced.

## Speed

`-Dsuperchunk.io.directPaletteEncode.timing=true` times every container encoded on the save path. The
encoder's kill switch `-Dsuperchunk.io.directPaletteEncode=false` selects the codec, so one jar times
both paths.

Mean per container, r1024, 11 workers. The legs alternated in one session; the box was shared, so
single legs vary by about ±10%.

| | Codec | Direct | Change |
|---|---|---|---|
| GPU off | 4.58–5.03 µs (4 legs) | 2.82–3.40 µs (4 legs, mean 2.97) | about −38% |
| GPU on | 5.14–5.64 µs (3 legs) | 2.97 µs | about −45% |

The steps:

| Version | GPU off | GPU on |
|---|---|---|
| Codec | 4.6–5.0 µs | 5.1–5.6 µs |
| Direct, vanilla's unpack/renumber/repack on an `int[]` | 4.06–4.33 µs | 4.34 µs |
| Two passes over the packed words | 3.10 µs | 3.50 µs |
| Plus the word table | 2.8–3.0 µs | 2.97 µs |

Each generated chunk is saved with about 72 containers, re-saves of the partly generated ring
included. That makes about 120 µs less CPU per chunk with the GPU off (about 0.6% of 19.6 ms) and
about 175 µs with it on (about 1.1% of 16 ms).

In a JFR profile with `-XX:+DebugNonSafepoints`, the whole encoder is 1.46% of samples:
- pass 1 about 0.9%;
- pass 2 about 0.3%;
- building the tags about 0.05%.

With the GPU off, `ChunkSerializer.write` as a whole is 2.4%.

Not tried: replacing the NBT tree itself with bytes written straight to the region stream, as C2ME's
gc-free serializer does. That serializer stays off because it loses NeoForge's chunk data attachments
and `ChunkDataEvent.Save`. Anything that does the same has to keep both.
