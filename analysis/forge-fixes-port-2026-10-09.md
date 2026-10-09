# Fixes ported from forge-1.20.1 — 2026-10-09

forge-1.20.1 fixed these between 2026-10-06 and 10-07 (see that tree's `PORT-NOTES.md`). They are
all in code the two ports share, so neo had the same defects.

## Worldgen output now matches vanilla

- **Noisium `ChunkSectionMixin` changed biomes.**
  - Cause: it reorders `fillBiomesFromNoise` from x,y,z to y,z,x. `Climate.RTree.search` warm-starts
    from the thread's previous result and keeps it on an exact distance tie, so lookup order is
    part of the output.
  - Fix: removed from `noisium.mixins.json`. The class stays, marked do-not-re-add.
  - Its speedup is replaced, with vanilla's output, by `worldgen.ClimateColumns`:
    - The climate functions that ignore y (decided per noise router from the vanilla density
      functions) are computed once per quart column during biome fill.
    - Lookups stay one per cell, in vanilla order.
    - Kill switch: `-Dsuperchunk.worldgen.climateColumns=false`. Verify mode:
      `-Dsuperchunk.worldgen.climateColumns.verify=true`.
- **C2ME `SimplifiedAtomicSimpleRandom(long)` stored the raw seed.** Vanilla's
  `LegacyRandomSource(long)` scrambles it with `setSeed`. `GeodeFeature` builds its noise straight
  from it, so every amethyst geode had a different shape than vanilla. The constructor now calls
  `setSeed`.

Measured on a fresh world (seed -987654321), Chunky radius 256 at (2048, 2048), 1,089 chunks; each
world compared to a SuperChunk-free NeoForge world:

| build | chunks with biomes ≠ vanilla | geode blocks (vanilla: 37,473) |
|---|---|---|
| 0.4.1 | 32 (100 quart cells) | 13,439 same, 24,034 missing, 26,613 extra |
| this change, CPU | 0 | identical |
| this change, GPU offload on | 0 | identical |

ClimateColumns verify mode on the same leg: 2,595,840 samples checked, 0 mismatches. It reuses
temperature, humidity, continentalness, erosion and weirdness per column; depth stays per cell.

## GPU: refuse OpenCL programs that inline too large to compile

- Tectonic's router density functions produce programs whose call DAG the NVIDIA compiler inlines
  whole: 1.9–11.7 GB of source. On forge, building one took the server past 20 GB and it was
  OOM-killed.
- `CLProgramCache.getOrBuild` now measures cold builds first (`KernelInlineEstimate`) and refuses
  anything over `-Dsuperchunk.gpu.maxInlinedKernelMB` (default 500). Those density functions use
  the existing per-function CPU fallback.
- Vanilla's largest program is 214 MB. Here, a vanilla GPU leg refused 0 programs, the fused and
  compact-id GPU paths were live, and biomes and geodes were identical to vanilla.

## `/save-all flush` saves full chunks that have left the holder map

A holder leaves vanilla's holder map as soon as it drops below SERVER_ACCESSIBLE, but keeps its
full chunk until the unload step saves it. A flush in that window skipped the chunk.
`TheChunkSystem.saveFullChunksOutsideHolderMap()` runs before `flushWorker()` in
`saveAllChunks(true)` and saves those chunks on the main thread.

On forge, a SIGKILL right after the flush lost 25–60% of fresh chunks. Neo measured clean even
before the fix: 441/441 and 1,681/1,681 on 0.4.1, the same after. Its async unload saves leave a
much shorter window. The fix is kept as defensive parity: flush-only, nothing else changes.

## Smaller fixes

- **`LithiumMixinPlugin`:** `STANDALONE_LITHIUM` was initialised before the
  `LITHIUM_EQUIVALENT_MODIDS` array it reads. The mod-id check therefore always threw (swallowed)
  and fell through to the class probes. The array is now declared first.
- **`FeatureRegionReads.outsideRegion`:** also recognises a Lithium fork's
  `NullPointerException("No chunk exists at …")` (Radium/Canary `gen.chunk_region`). New
  regression program `FeatureRegionReadsTest`, 10 cases.
- **`BiomeQuartCache.UNCACHEABLE`:** was `Long.MIN_VALUE`, which is also the key of quart (0,0,0)
  under generation 8, so that cell was never cached for 1 manager in 15. It is now `0`; real keys
  always carry a generation of 1–15 in their top four bits. A regression check was added.
- **`GcAdvisory`:**
  - The hint file is written under `FMLPaths.CONFIGDIR`, not a cwd-relative `./config`.
  - It no longer claims dedicated servers are auto-configured: that is opt-in with
    `-Dsuperchunk.gc.autoConfig=true`.
- **`tools/verify-skein-server.py`:** accepts current Skein's reload reply and its
  `name (blocked)` phase list.

## Performance

Concurrent pairs: 0.4.1 and this change pregenerate identical terrain at the same time on two
servers, so box noise hits both. Fresh worlds, seed -987654321, Chunky radius 1536 at
(20000, 20000), 37,249 chunks. CPU mode, 6 GB heap each; the two server dirs swap each pair.

| pair | 0.4.1 CPU-s / wall | this change CPU-s / wall |
|---|---|---|
| 1 | 1,115 / 113 s | 1,106 / 111 s |
| 2 | 1,152 / 133 s | 1,142 / 133 s |
| 3 | 1,150 / 123 s | 1,111 / 120 s |
| 4 | 1,144 / 122 s | 1,131 / 120 s |
| mean | 1,140 (30.6 ms/chunk) / 122.75 s | 1,123 (30.1 ms/chunk) / 121.0 s |

This change used less CPU in every pair: −1.6% CPU and −1.4% wall on average (forge's ClimateColumns
A/B was −1.0%). A sequential run beforehand drifted 13% from its first leg to its last, too much to
read a 1–2% difference, hence the concurrent design.

## Verification

`./gradlew build`: all 13 regression programs pass.
