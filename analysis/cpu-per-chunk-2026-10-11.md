# CPU per chunk — 2026-10-11

Goal: bring the CPU cost of generating a chunk (GPU off) from about 29 ms into the 20–25 ms range,
with world output unchanged.

## How it is measured

`cpu_ms_per_chunk` from `tools/run-worldgen-benchmark.py`: process CPU time (all threads: workers,
GC, JIT, server thread) over the generation window, divided by the chunks generated. Fresh world,
seed -987654321, Chunky radius 1024 (16,641 chunks), 6 C2ME workers, 6 GiB heap, Generational ZGC.
Two servers run at the same time, one per jar, sides alternating which starts first. Load from the
rest of the box hits both equally. Single sequential runs drift more than most of these effects;
see `superchunk-bench-harness` notes.

Under that two-server load a chunk cost **29–32 ms** before this pass (load-dependent: 29.2 and
28.8 on a quieter box, 30–32 with other jobs running). Rebuilding the 09-26 and 10-05 commits and
pairing them against HEAD showed no regression since the 25.5 ms of
`analysis/optimization-2026-09-24.md`; that figure came from a quieter box.

Where it went (JFR, baseline, worker threads = 82% of process CPU; GC 11%, JIT 3%):

| Phase (inclusive, share of worker samples) | Baseline |
|---|---:|
| Noise fill (`doFill`) | 47.8% |
| — per-block final density in `selectCellYZ` (cell cache fill + beardifier) | 16.5% |
| — cell-corner columns (`fillSlice`) | 14.4% |
| — aquifer air predicate / column ceilings | 4.0% |
| Features | 16.9% |
| Surface rules | 13.4% |
| Biomes | 4.2% |

## Changes

All exact: each returns what the code it replaces returns. Each has a kill switch, and each
that can be compared in lockstep has a verify mode.

### 1. Cell sign skip (`worldgen.CellSignSkip`, `MixinNoiseChunkCellSign`)

`selectCellYZ` fills the cell cache with the final density of all 128 blocks of a 4×8×4 cell. In
vanilla routers the per-block part of that expression is small arithmetic (`min`, `squeeze`,
`range_choice`, `abs`, constants) over interpolated values. Inside a cell an interpolated value is
a trilinear blend of the cell's 8 corners, so it lies between their minimum and maximum, give or
take the lerp's rounding (the bound widens for it). Evaluating the expression over intervals bounds
every block of the cell at once.

- The program is built once per compiled function, from C2ME's AST. Leaves are the chunk's
  interpolators, bound by field index. A tree marker can be an equal copy of the field's marker;
  equal markers bind to the same per-chunk interpolator.
- Every op is bounded soundly in floating point. Add, multiply, min and max use corner and endpoint
  values, and rounding is monotonic. Squeeze, the y gradient and the lerp are widened by fixed
  slack.
- The beardifier must be provably 0 in the cell: the cell lies outside the existing per-chunk
  beard box.
- Only the sign matters:
  - Entirely positive: `computeSubstance` returns null for any density > 0 without reading it.
  - Non-positive, and the aquifer's air predicate holds for the cell: every block is AIR through
    the adaptive air branch, which reads no value either. The air-cell skip then answers the cell.
  - Either way the cell cache is filled with the bound, so the value has the proven sign.
- A proven-solid cell whose ore-vein toggle bound stays under 0.4 is answered the default block
  without the per-block path. Vanilla's vein filler returns null below 0.4 before reading anything
  else. This also covers cells outside the vein y ranges, where the toggle is 0. The per-block
  interpolator updates are skipped too, as for air cells.
- Stands down when:
  - blending is active;
  - the chunk has more than one cell cache;
  - the aquifer is not vanilla's noise aquifer;
  - another mod hooks the fill (`BlockFillHooks`);
  - on-device interpolation is on;
  - a node has no bound.

Overworld, radius 512: 88.2% of cells decided (28% solid, 60% air); 76% of the solid ones are
also vein-free.

Switches: `-Dsuperchunk.worldgen.cellSignSkip=false`, `.verify=true` (decides, skips nothing,
checks every decided cell's real values and every solid-answered block's material-rule result),
`.metrics=true`.

### 2. Shared preliminary surface levels (`PreliminarySurfaceLevels`, `MixinNoiseChunkCellCache`)

`computePreliminarySurfaceLevel` scans `initial_density_without_jaggedness` down a quart column.
The aquifer asks about columns up to 25 blocks outside its chunk, so neighbours recompute the same
ones. Wherever the aquifer cell cache exists, `AquiferCellSharing` has proven exactly this
function chunk-independent down such columns. A per-`RandomState` lock-free table (one `long` per
entry: tag and level) now serves them. Unblended chunks only, keyed on the noise settings. Hit
rate 88%.

Switches: `-Dsuperchunk.worldgen.sharedSurfaceLevels=false`, `.verify=true`, `.metrics=true`.

### 3. Shared cell-corner columns (`CornerColumns`, `MixinNoiseChunkCornerColumns`)

A chunk fills 25 corner columns per interpolator, but 9 are shared with neighbours.
`AquiferCellSharing.cornerReason` walks the vanilla router once per `RandomState`. Every
`interpolated` function must be chunk-independent down a quart-aligned column:

- no beardifier;
- no `flat_cache` or `cache_2d` over a y-dependent function;
- nothing unknown anywhere in the router.

`fillSlice` then copies a column another chunk computed and publishes the ones it computes. The
table is direct-mapped and holds immutable entries (key and values) with release/acquire
publication. It is sized from the heap: 2^16 entries (≤ 29 MB) from 6 GB. Unblended chunks only;
GPU routing is allowed (see "GPU mode" below).

32.5% of columns were shared at radius 512 (the ceiling is 36%).

Switches: `-Dsuperchunk.worldgen.cornerColumns=false`, `.verify=true` (recomputes every shared
column and compares bit for bit), `.metrics=true`, `cornerColumnsSize`.

### 4. Region center chunk (`MixinWorldGenRegionCenterChunk`)

`WorldGenRegion.getChunk` answers its own center from the field instead of the chunk holder (a chain
of dependent loads, 2.3% of worker CPU). The center is the chunk being generated by this step and
stays the holder's chunk throughout. About 30,000 lookups per chunk are the center.

Switches: `-Dsuperchunk.worldgen.regionCenterChunk=false`, `.verify=true`.

### 5. Lean surface writes (`ProtoChunkWrites`, in `MixinSurfaceColumnMemo`)

Surface rules write about 16,000 blocks a chunk, nearly all of them the deepslate below y=0.
`ProtoChunk.setBlockState` looked up the status's heightmap set and each heightmap twice per write;
its self time was 2.6%. Before `INITIALIZE_LIGHT`, the write is the section write plus the
heightmap updates. Those heightmaps are now resolved once per chunk. Only for exact-class
`ProtoChunk`s with primed heightmaps and no other mod hooking the method.

Switch: `-Dsuperchunk.worldgen.leanSurfaceWrites=false`.

### 6. Allocation

- Surface rules ask the biome cell path directly (`BiomeCellLookup`, `MixinSurfaceSystemCellBiome`).
  `getBiome`'s `@WrapMethod` allocated an `Operation` per call: 3.6 GB per radius-1024 pregen,
  5% of all worldgen allocation, now 1%.
- NBT compounds start with room for 4 entries instead of fastutil's default 16: two 9-slot arrays
  instead of two 33-slot ones. That was 7% of worldgen allocation (chunk saving).

## Results

Clean baseline (git HEAD 34b1a48) against this build, four concurrent pairs, radius 1024:

| Pair | Baseline ms/chunk | This build | Change | Steady chunks/s |
|---|---:|---:|---:|---:|
| 1 | 31.57 | 26.74 | −15.3% | +23.4% |
| 2 | 29.73 | 25.66 | −13.7% | +20.1% |
| 3 | 27.57 | 24.03 | −12.8% | +23.6% |
| 4 | 28.20 | 24.61 | −12.7% | +14.3% |
| mean | 29.27 | **25.26** | **−13.6%** | |

Box load varied a lot between pairs (load average 13–34), so compare within a pair. Per thread
group, pair 3:

| Thread group | Baseline | This build |
|---|---:|---:|
| Workers | 21.9 ms/chunk | 18.3 ms/chunk (−16.5%) |
| GC | ~2.9 ms/chunk | ~2.9 ms/chunk |

GC is now 12–13% of process CPU.

Step by step, each a separate set of concurrent pairs (previous build against the next):

| Step | Pairs | Change in CPU per chunk |
|---|---:|---|
| Cell sign skip alone (kill switch off vs on, same jar) | 3 | −8.0%, −4.7%, −6.8% |
| Baseline → + solid answer, shared surface levels, biome lookup | 3 | −11.2%, −9.5%, −9.7% |
| + region center chunk, NBT sizing | 3 | −3.7%, −0.4%, −2.7% |
| + shared corner columns | 3 | −3.5%, −2.4%, −3.2% |
| + lean surface writes | — | inside the final pairs; parity-checked, not isolated |

On the quietest stretch of the day (pair 1 of the region-center step) the previous build already
measured 24.76 ms; the final build's best pair is 24.03 ms. Both servers of a pair share the box
(12 workers plus GC on 28 hardware threads), so a server running alone costs less per chunk.

## Parity

Radius-512 fresh worlds (4,225 chunks plus spawn), every verify flag on, and the noise-fill and
surface hashes (`-Dsuperchunk.debug.noiseFillHash`, new `-Dsuperchunk.debug.surfaceHash`) compared
across runs with `tools/noisehashdiff.py`:

| Check | Compared | Mismatches |
|---|---:|---:|
| Cell sign: decided cells' real final densities | 426,637,056 blocks | 0 |
| Cell sign: solid-answered blocks' material rules | 103,870,720 blocks | 0 |
| Air cells (existing verify) | 287,998,080 blocks | 0 |
| Shared surface levels, recomputed | 861,572 columns | 0 |
| Shared corner columns, recomputed bit for bit | 324,837 columns | 0 |
| Region center chunk | 153,107,189 lookups | 0 |
| Biome fiddle cache / cell path (existing verify) | 298,057,679 | 0 |
| Noise-fill hash, all switches on vs off | 4,882 chunks (691 with structure adaptation) | 0 |
| Surface hash, all switches on vs off | 4,882 chunks | 0 |

## Also found

`.gitignore`'s bare `run` pattern ignored `src/.../lithium/mixin/ai/task/run/`. That package has
never been in git, so a jar built from the repository fails to boot: the Lithium mixin config names
`LongJumpToRandomPosMixin`. The patterns are now anchored (`/run`, `/runs`, `/run-data`) and the
package shows up as untracked, ready to add.

## Not done

- Ore placement (7.5% of worker CPU) is mostly palette reads of the blob's candidates.
- `PalettedContainer.get` (9%) is still the largest leaf.
- Remaining allocation:
  - the scheduler's absolute dependency arrays (`KeyStatusPair` + `ChunkPos`, ~6%);
  - biome fill's `TargetPoint` (3%).

## GPU mode (2026-10-17)

The first version stood the cell sign skip and corner sharing down whenever GPU routing was on,
untested. They now run there too.

- The cell cache fill never reaches the GPU. Its 4x8x4 points are off the chunk's corner lattice, so
  `ChunkGridCache` rejects them, and 128 points is under `GpuDfcDelegate`'s direct-dispatch floor
  (1024). The final density's GPU delegate therefore leaves it to the same CPU bytecode as with the
  GPU off. `CellSignSkip.bind` accepts a delegate only when that holds for the cell size
  (`GpuDfcDelegate.cpuFillsOffLattice`); very large custom cells or `-Dsuperchunk.gpu.liveServeAll`
  still stand it down. The vein toggle is read per block through `compute`, which never uses the
  delegate.
- Compact ids `on`: consumed chunks skip the fill, so neither shortcut runs there. The hybrid fill's
  CPU cells are the ones holding a beard-sentinel block, and sentinels are written for exactly the
  NoiseChunk's beard box, the box the cell sign skip reads. So no hybrid CPU cell is ever decided,
  and the hybrid path never evaluates the material rules over a bound.
- Corner sharing copies columns the GPU computed; skipping a `fillArray` only means the grid cache
  serves fewer columns.

Light test, RTX 3070 (shared with other GPU jobs), radius 256, seed -987654321:

| Check | Compared | Mismatches |
|---|---:|---:|
| Cell sign verify, compact ids `verify` (89.1% decided) | 131,885,952 blocks | 0 |
| Cell sign solid answers | 30,106,496 blocks | 0 |
| Shared corner columns, recomputed | 96,985 columns | 0 |
| Shared surface levels / air cells | 286,325 / 88,749,952 | 0 |
| Noise-fill hash, compact ids `off`, switches on vs off | 1,490 chunks (76 with structure adaptation) | 0 |
| Surface hash, same legs | 1,490 chunks | 0 |

Compact-consume verify showed one stone->air flip in 143.2M blocks, the known fp32 decide flip: the
same position and counts appeared in a run where the cell sign skip did not engage at all. With the
GPU defaults (compact ids `on`) the skip saw 37,870 cells at radius 256 (77% decided): the chunks
that take the original fill, plus the hybrid fills' CPU cells, which stay undecided.
