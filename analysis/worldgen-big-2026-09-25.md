# Noise-fill CPU pass ("the big ones") — 2026-09-25

The three largest items of the review backlog (`review-2026-09-25.md`), all in the CPU noise
fill, plus an exactness bug the new whole-world check found in an older optimization. Every
change returns the same values as the code it replaces; each has a kill switch and a lockstep
verify mode, and the pregen hash check below compares the whole fill chunk by chunk.

## Changes

1. **Per-cell lerp tables** (`LerpTables`, `MixinChunkNoiseSamplerDensityInterpolator`). Every
   block of a noise cell reads each interpolated function as vanilla `Mth.lerp3`: seven lerps per
   block and interpolator. Within a cell the four x-lerps depend only on the block's x and the two
   y-lerps only on (x, y), so they are computed once per column / row (generation-stamped tables,
   reset by `selectCellYZ`) and each block does the last z-lerp. Same operands, operations and
   order as `lerp3`, so bit-identical. Applies to the cell-cache fill, the single-point reads and
   the multi-point reads; falls back to `lerp3` when on-device interpolation owns the corners or a
   read is outside the cell.
   Switches: `-Dsuperchunk.worldgen.lerpTables=false`, `.verify=true`.

2. **Bulk constant-factor multiply** (`MulNode.doBytecodeGenMulti`, `IFastCacheLike.c2me$getCachedPointwise`).
   DFC's compiled `mul(constant, interpolated(...))` evaluated the right side element by element
   through the single-point method. When the right side is a cache-like node that can fill the
   whole array exactly as the per-element reads would (an interpolator inside the interpolation
   loop), it now fills the array in one call and multiplies in place; otherwise the original
   per-element code runs. Switch: `-Dsuperchunk.dfc.mulBulk=false`.

3. **Air-cell skip** (`AirCells`, `MixinNoiseFillAirCells`, `MixinChunkNoiseSampler`,
   `MixinAquiferSamplerImpl.superchunk$airCell`). After `selectCellYZ` has filled a cell's final
   densities, the cell is skipped when every density is non-solid (`!(d > 0)`, the aquifer's own
   test), the aquifer's adaptive air branch answers the identical `AIR` state for every block of
   the cell (same functions, block by block: at or above the local water ceiling plus the barrier
   margin, where the global fluid is air), and no other mod hooks the fill (`BlockFillHooks`,
   shared with the GPU compact ids; YUNG's API does, so the skip stands down there). Each block's
   `getInterpolatedState` then returns `AIR` and the per-block interpolator loops are skipped; the
   loop structure, counters and every non-air cell are vanilla's. About 60% of cells qualify in
   the test world. Switches: `-Dsuperchunk.worldgen.airCells=false`, `.verify=true` (skips
   nothing; checks every block of every predicted-air cell came out `AIR`).

4. **Fix: the aquifer cell cache was not exact** (`ScAquiferCellCache`, `AquiferCellSharing`,
   `MixinRandomStateCellCache`, `MixinNoiseChunkCellCache`). The cross-chunk cache of aquifer
   `computeFluid` results (worldgen lever 1, shipped earlier) assumed a cell's status is the same
   whichever chunk computes it. Vanilla's is not: `computeFluid` reads erosion, depth and
   floodedness at the cell's jittered position through the asking chunk's `NoiseChunk`, and a
   `flat_cache` there answers from that chunk's quart grid (the quart corner's value, at y = 0)
   when the position lies inside it, and computes at the exact position otherwise. Near the
   deep-dark threshold (`erosion < -0.225 && depth > 0.9`) the two differ, so the chunk that
   happened to compute a cell first decided it for its neighbours: 5–7 chunks per radius-512
   pregen came out differently from run to run and from vanilla.
   - The key now carries which of the two variants the asking chunk computes (in or out of its
     flat grid); each variant is chunk-independent.
   - Whether the rest of `computeFluid` is chunk-independent depends on the router, so it is
     decided per `RandomState` from the vanilla router, before DFC compiles it: `flat_cache` in
     the surface scan (`initial_density_without_jaggedness`, read down quart columns in or out of
     the grid) only over y-independent functions; none in `fluid_level_spread` / `lava`;
     `cache_2d` (last value per x, z whatever the y) only over y-independent functions; no
     beardifier; no density-function type it does not know (a mod's own type could hold anything).
     A router that fails keeps vanilla's per-chunk path and says why in one INFO line. The
     vanilla overworld, nether and end share.
   - `computeFluid` returns early, before those grid-dependent reads, for every cell well above
     the surface; its early returns are the global fluid picker's own status objects and its other
     path always builds a new one, so a result identical to one of the picker's statuses is stored
     for both variants and only the rest are computed per variant.
   - Sharing also stands down while another mod hooks `computeFluid`, what it calls, or the
     surface scan (`MixinTargetScan`, like the air-cell skip).
   - The cache is now owned by the `RandomState` (it was keyed by the fluid picker, which one
     generator shares across seeds).
   Switches: `-Dsuperchunk.worldgen.aquiferCellCache=false` (all three aquifer levers),
   `-Dsuperchunk.worldgen.aquiferCellSharing.selfTest=true` (checks the decision on the overworld's
   shapes and on each way a datapack can break sharing; logs PASS/FAIL).

## Pregen hash check

`-Dsuperchunk.debug.noiseFillHash=<file>` (`MixinNoiseFillHash`) writes one hash per chunk right
after the CPU noise fill: every block state, the fluid post-processing lists in order, and the two
worldgen heightmaps. A chunk's fill depends only on its position and the seed, unlike the finished
chunk (features from neighbours land in scheduling order), so two runs must agree chunk for chunk;
`tools/noisehashdiff.py` compares two files. Radius 512, seed −987654321, 4,882 chunks
(691 with structure terrain adaptation):

| Comparison | Mismatched chunks |
|---|---:|
| Old cache, identical settings, two runs | 5 |
| Old cache, new changes off vs on | 7 (none from the changes) |
| Cache off: two runs | 0 |
| Cache off: lerp tables + bulk multiply + air cells off vs on | 0 |
| Fixed cache, two runs | 0 |
| Fixed cache vs cache off (= vanilla per chunk) | 0 (both runs) |
| Per-RandomState cache vs cache off | 0 |
| Final (early returns shared) vs cache off | 0 |
| JJThunder (1,490 chunks): cache on vs off, before and after the early-return sharing | 0 |

## Verification

Final jar `5c4b28eb` (`build/libs/superchunk-0.4.0.jar`). The verify legs below ran `5694dcac`,
which lacks only the early-return sharing and the hook guard of change 4; the final jar was checked
by the pregen hashes above (both worlds) and the benchmarks. Legs in `run/big-20260925/`
(`tests.sh`, `queue2.sh`).

| Check | Result |
|---|---|
| 12 regression programs | all pass |
| CPU parity gate, every verify flag (r256, seed −987654321) | 0 mismatches: 341.6M lerp-table reads; 693,359 of 1,144,320 cells predicted air, 88.7M blocks checked; 88.1M fiddle, 3.0M climate, 19.6M quart-cache, 4.78M ore-RNG |
| 9-mod compat pack, CPU, verify (r512) | 0 mismatches (1.13B lerp reads); air-cell skip stands down naming `yungsapi NoiseChunkMixin`; Deeper Oceans' own density function type turns aquifer sharing off in the overworld (vanilla path) |
| JJThunder To The Max (patched), y −2032..2031, r256, verify | sharing on; 10.8M of 12.5M cells air (87%), 694M blocks checked, 809M lerp reads, 0 mismatches; cache on vs off: 0 of 1,490 chunk hashes differ |
| Sharing self-test (`aquiferCellSharing.selfTest`) | 10 routers, PASS |

## Benchmark

CPU mode, paired concurrent rounds (`run/perf-20260924/pair.py`: both jars generate the same fresh
world at the same time, 6 workers each, sides alternating), radius 1024 (16,641 chunks), the
review's final jar `4762598e` → `5694dcac`:

| Round | CPU per chunk | Chunks/s |
|---|---:|---:|
| 0 | 26.38 → 24.87 ms (−5.7%) | 277.1 → 298.1 (+7.6%) |
| 1 | 25.29 → 24.89 ms (−1.6%) | 287.1 → 298.8 (+4.1%) |
| 2 | 25.61 → 24.69 ms (−3.6%) | 280.8 → 299.0 (+6.5%) |
| **Mean** | **−3.6%** | **+6.0%** |

The lerp tables alone measured −1.9% CPU per chunk and +2.2% chunks/s the same way.

GPU mode (`ab.py`, sequential interleaved legs, radius 1024, 12 workers): flat. The GPU fills
most chunks' blocks itself, so `doFill`'s per-block loop, where the lerp tables and the air-cell
skip work, rarely runs; what remains on the CPU per chunk is the aquifer status capture and the
later stages.

| Comparison | CPU per chunk | Chunks/s |
|---|---:|---:|
| Review jar → `5694dcac`, 6 pairs | +1.1% | −1.4% |
| Before the cache fix → `5694dcac`, 4 pairs (cost of the fix) | −1.5% | +0.9% |
| `5694dcac` → final, 4 pairs | −0.3% | +0.3% |

Leg-to-leg spread is ±2–3% in both configurations, so none of these is distinguishable from zero.
CPU mode, `5694dcac` → final, 3 pairs: −0.6% CPU per chunk, +0.6% chunks/s (noise).

## Not done

- Lazy interpolator updates (backlog item 3): the air-cell skip already removes the per-block
  interpolator loops for most cells, and in the rest the updates feed reads whose order would
  have to be reproduced exactly; left for a profile of the new build.
