# NeoForge 1.21.1 optimization pass — 2026-09-24

This pass targets worldgen CPU outside the noise arithmetic: the biome lookups that fresh
JFR profiles now put at the top of the non-noise work, plus per-block and per-task allocation
in ore veins and the chunk scheduler. Noise math, OpenCL kernels, GPU precision, worker
defaults and generation rules are unchanged. Every change is exact: each returns the same
values, in the same order, as the code it replaces, and each has a kill switch and, where
a live comparison is possible, a lockstep verify mode.

## Where the time was

JFR execution samples from the pre-pass build (`baseline.jar`, CPU mode, radius 1024, 13,774
worker samples) and from the round-2 GPU profile (19,847 samples):

| Path (inclusive, share of worker samples) | CPU mode | GPU mode |
|---|---:|---:|
| Noise fill (`generateNoise`) | 48.7% | 6.6% |
| Surface rules (`buildSurface`) | 13.9% | 26.0% |
| Features (`applyBiomeDecoration`) | 11.7% | 21.1% |
| Biome fill (`fillBiomesFromNoise`) | 8.8% | 14.0% |
| — of which `Climate.RTree.search` | 6.6% | 10.1% |
| `BiomeManager.getBiome` self time | 4.7% | 8.2% |

`getBiome` is called per block by surface rules and placement filters. Its body scores the
8 quart lattice points around the block with `getFiddledDistance`: 9 LCG steps and 3
modulus/divide sequences per point, 72 and 24 per block. The quart-resolution lookup behind
it was already memoized; the jitter computation in front of it was not.

## Changes

1. **Flat climate search** (`FlatClimateIndex`, `MixinClimateParameterListFlat`,
   `MixinClimateRTreeLeaf`). `ParameterList.findValueIndex(TargetPoint)` answers from an
   array copy of the list's own R-tree: 14 bounds per node contiguous in one `int[]`, each
   subtree's children numbered consecutively. The traversal is vanilla's recursion (child
   order, strict `>` tests) and it reads and writes vanilla's own `lastResult` warm start, so
   ties resolve exactly as vanilla resolves them, including when vanilla's search runs in
   between. A distance stops accumulating once it reaches the current best, which is exact
   because the caller only acts on `best > distance`; bounds and targets are required to fit
   in ±2^28 so the partial sums cannot overflow. Out-of-range targets, subclasses of
   `ParameterList`, and trees with unknown node classes take the original method. Removes
   the per-call `long[7]` from `TargetPoint.toParameterArray()` (2.35 GB sampled per CPU-mode
   run). Isolated lookup cost: ~480 → ~240 ns (1.6–2.0× across runs on a loaded machine).
   Switches: `-Dsuperchunk.worldgen.flatClimateSearch=false`, `.verify=true`.

2. **Biome fiddle cache and cell path** (`BiomeFiddleCache`, in `MixinBiomeManagerQuartCache`).
   The three jitter offsets of a lattice point depend only on the seed and the point, and all
   blocks of a 4×4×4 cell share the same 8 points. Two exact-keyed, per-thread, direct-mapped
   tables hold them: lattice points, and whole cells (24 offsets in vanilla corner order). Both
   are cleared when the seed changes.
   - *Cell path.* For worldgen managers on their owner thread, `getBiome` is answered with one
     cell probe, vanilla's 8 distance evaluations over the cached offsets (same fractions, corner
     order, operand order and strict comparison), and the quart memo. This takes over the whole
     method, so it runs only when no other mod's mixin is merged into `BiomeManager` (checked on
     the finished class by `MixinTargetScan`; see "Code review and fixes"). The decision is
     logged once at startup.
   - *Per-corner path.* Otherwise, vanilla's method runs and each `getFiddledDistance` call is
     wrapped to take its offsets from the lattice table, leaving vanilla's control flow intact.
     Non-worldgen managers use an allocation-free copy.
   Both paths use a copy of `getFiddledDistance`. A one-time self-check compares it with the
   actual target method on 256 sampled inputs; if another mod changed that method, both paths
   stand down and the original is always called. Isolated cost per surface-shaped block:
   vanilla ~77 ns, per-corner ~40 ns, cell path ~25 ns. Switches:
   `-Dsuperchunk.worldgen.biomeFiddleCache=false` (both paths),
   `-Dsuperchunk.worldgen.biomeCellPath=false` (per-corner only), `.biomeFiddleCache.verify=true`
   (compares every cached distance and every cell-path biome with vanilla's).

3. **Ore-vein generator reuse** (`ReusableOreRandom`, via `MixinNoiseChunkVeinCensus`).
   Vanilla's ore-vein filler allocates an `XoroshiroRandomSource`, its generator and its
   Gaussian helper for every block past the vein-band gate, draws at most three floats and
   drops them (3.4 GB sampled per CPU-mode run). The filler itself is unchanged; its factory
   is wrapped so each thread reuses one generator, reset to exactly the state a new one starts
   in (zero-state repair included, no pending Gaussian). Non-vanilla factories, and Xoroshiro
   classes another mod has mixed into, keep the original behavior. Switches:
   `-Dsuperchunk.worldgen.oreRandomReuse=false`, `.verify=true`.

4. **Scheduler allocation** (C2ME/flowsched sources).
   - `ChunkSystemExecutors`: the consolidating executor kept its queue in a `ThreadLocal` that
     was set and removed for every root task, and every submission from outside a root
     created an entry just to remove it (1.0 GB of `ThreadLocalMap$Entry` sampled). A
     per-thread state object is now created once; worker roots reuse their drained deque.
     The server-thread path still hands a fresh queue to its background task.
   - `getDependencyFuture0` created one identical capturing callback per dependency; one
     callback now serves all of them (callbacks are only listed and each is run once). An
     unused `KeyStatusPair` allocation is removed.
   - `flushUnloadedStatus` no longer re-completes futures that are already done:
     `completeExceptionally` allocates its result wrapper before discovering the future is
     complete, and most of these were the shared, always-done `UNLOADED_FUTURE`.

The benchmark harness now also records process CPU seconds over the generation window
(`generation_cpu_seconds`, `cpu_ms_per_chunk`), which is far less sensitive to competing
load than chunks/s. Regression programs now run with `build/regression-tests` as their
working directory, so Minecraft's logging no longer creates `logs/` in the source tree.

## Regression checks

```sh
./gradlew build --offline --console=plain
```

The build runs twelve standalone regression programs; four are new. Two are described here;
`SuperChunkConfigDocsTest` and `MixinTargetScanTest` are covered with their features:

- `FlatClimateIndexTest`: 4,320,000 lockstep lookups against the unmodified R-tree on the real
  overworld and nether presets and on synthetic trees (single-leaf root, one-level roots,
  duplicate points for exact ties, several levels). Each lookup starts both searches from
  the same warm-start leaf and compares the value and the warm-start leaf each leaves behind.
  Targets include uniform points, coherent random walks, and region corners/edges ±1.
  Out-of-range targets must be refused without moving the warm start, and trees with bounds
  beyond ±2^28 must not be indexed.
- `BiomeFiddleCacheTest`: 13,832,344 bit-exact distances against the private vanilla method
  (via a method handle) and 1,726,508 full nearest-cell selections against the real
  `getBiome`, each checked on both the per-corner and the cell path, over surface-shaped scans
  with seed switches, 300,000 scattered positions (constant slot replacement) and the
  packed-key range edges (where the cell path must decline).

## Live parity

Radius-256 fresh worlds, seed -987654321, with every verify flag on. Each verify mode runs
the original code as well, returns the original result, and counts disagreements.

CPU mode (`parity-cand2`; the final build `parity-cand5` repeated this with the same result,
4,778,399 ore generators checked after the per-thread change):

| Check | Compared | Mismatches |
|---|---:|---:|
| Fiddle cache: cached vs. original distance, bit-for-bit | 78,353,688 | 0 |
| Flat climate search: value and warm-start leaf | 3,036,641 | 0 |
| Ore generator reuse: first four longs and a Gaussian vs. a fresh instance | 482,116 | 0 |
| Existing quart cache (hits re-resolved) | 8,301,892 | 0 |
| Existing surface-gradient sampler | 3,975,478 | 0 |

GPU mode (`parity-gpu-cand5`, final build with the review fixes): the strict gate. fp64 decisions,
`-Dsuperchunk.gpu.compactIds=verify` and every verify flag above. All 1,465 noise chunks went
through the batched GPU path, and the compact-id decide chain handled every one. The shadow
verifier compared 143,200,256 blocks with zero differences in blocks, heightmaps, section
counters, Lithium flags and post-processing marks, and the fp64 flip census was empty. The new
checks also passed in GPU mode: 88,150,121 fiddle-cache/cell-path checks, 3,036,234 climate
lookups and 4,778,399 ore generators, all matching.

A first GPU attempt (`parity-gpu-cand2`) proved nothing. The test template's program cache was
stale after the 0.4.0 bump, so the lazily built batch programs were still compiling when the
7-second pregen ended, and zero chunks reached the verifier. The runs above use a private
template whose cache was warmed by a radius-768 GPU run first.

## Measured effect

Java 21.0.12.1, NeoForge 21.1.248, Chunky 1.4.23, Generational ZGC, seed 8675309, radius 1024
(16,641 chunks per run), CPU mode. The machine was shared with up to a dozen other heavy
workloads, and load averages swung between 13 and 57 on its 28 hardware threads. Under that
load, single sequential runs varied by ±10% even in process CPU time, which is more than the
effect being measured. The design that held up was **paired concurrent runs**. In each round
both jars generate the same fresh world at the same moment, on separate servers with 6 workers
and a 6 GiB heap each, so any outside load hits both equally. Sides alternate between rounds.

09-24 measurement (`pair-final.log`, baseline vs. `cand5.jar`, before the review fixes):

| Round | Baseline CPU ms/chunk | This pass | Change | Baseline chunks/s | This pass | Change |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 36.09 | 34.53 | −4.3% | 168.1 | 173.8 | +3.4% |
| 1 | 29.86 | 28.92 | −3.1% | 241.9 | 253.4 | +4.8% |
| 2 | 30.13 | 28.76 | −4.5% | 236.6 | 248.6 | +5.1% |

**About 4% less CPU per chunk and 4–5% more throughput**, in the same direction in every round.
With both servers competing for the same caches and memory bandwidth this is a conservative
figure. An earlier sequential A/B of an intermediate build (`ab2-cpu.log`, 12 workers, 8 GiB)
measured −8.8% CPU per chunk and +14.9% chunks/s. There, every candidate run beat every
baseline run, but those runs are the noisier design, so the paired numbers are the ones to quote.

### Final build, re-measured 2026-09-25

`final.jar` (`5b610beb`) is `cand5` plus the review fixes and the broken-chunk wait fix
(`analysis/jjthunder-freeze-2026-09-25.md`). The box was quieter (load 10–16), so all rounds
ran faster than on 09-24. Queue: `bench-final.sh` (resumable).

CPU mode, paired as above (`pair-v2.log`, baseline vs. final):

| Round | Baseline CPU ms/chunk | Final | Change | Baseline chunks/s | Final | Change |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 26.30 | 25.41 | −3.4% | 279.4 | 293.0 | +4.9% |
| 1 | 25.99 | 25.78 | −0.8% | 287.4 | 288.5 | +0.4% |
| 2 | 26.05 | 25.59 | −1.8% | 282.0 | 287.6 | +2.0% |

Mean −2.0% CPU per chunk and +2.4% chunks/s, the same direction in every round but smaller than
the 09-24 figure. A direct paired `cand5` vs. final run (`pair-c5-final.log`) found no cost from
the later fixes: +0.2%, +3.4% and 0.0% CPU per chunk (mean +1.2%, within round-to-round noise).
Treat the CPU-mode gain as roughly 2–4%.

GPU mode, interleaved A/B (`ab-gpu-final.log`, ABBAAB, 12 workers, 8 GiB, default config, program
cache warmed for both jars first):

| | Chunks/s mean (min–max) | CPU ms/chunk mean (min–max) |
|---|---:|---:|
| Baseline | 889.7 (872.0–898.9) | 18.60 (18.18–19.18) |
| Final | 964.4 (956.3–969.9) | 17.46 (17.21–17.72) |

**+8.4% chunks/s and −6.1% CPU per chunk in GPU mode.** Every final leg beat every baseline leg.
That is larger than the CPU-mode gain, as the profile predicted: once the GPU takes the noise
work, the paths this pass speeds up (R-tree search 10.1%, `getBiome` self time 8.2% of worker CPU)
are a bigger share of what is left.

## Compatibility

Static check: all 167 mods of a real NeoForge 1.21.1 server modpack were scanned for mixins
into the classes this pass touches (`Climate`, `BiomeManager`, `OreVeinifier`,
`XoroshiroRandomSource`, `NoiseChunk`, `SurfaceSystem`, the flowsched scheduler). Only three
mods overlap, none on the same members: Lithostitched replaces `MultiNoiseBiomeSource`
parameter lists through codecs (each replacement list gets its own flat index) and injects at
the end of the `SurfaceRules.Context` constructor; YungsApi injects at `RETURN` of the `NoiseChunk`
constructor, `NoiseChunk.getInterpolatedState` and `Beardifier.compute`.

Live check (`compat-baseline`, `compat-cand3`): fresh radius-512 worlds (4,225 chunks) with
nine worldgen-relevant mods from that pack — Lithostitched 1.5.4, Deeper Oceans 2.0.0, YungsApi
5.1.6, YUNG's Better Mineshafts 5.1.1 and Better Strongholds 5.1.3, standalone Lithium 0.15.1,
Sparse Structures 3.0, Moog's Structures 1.1.0 and Async Locator Refined 1.5.2. Both jars
booted, generated and saved with zero ERROR lines; standalone Lithium was detected and took
over as designed, and the cell path correctly stayed enabled (none of these mods mixes into
`BiomeManager`). With every verify flag on, the candidate compared 248,038,498 fiddle-cache
distances and cell-path biomes, 8,471,743 climate lookups, 704,661 ore generators and
51,838,595 quart-cache hits against vanilla: zero mismatches.

The cell path's guard was also exercised by construction: it logs, once, which mixin made it
stand down, and the per-corner path keeps vanilla's `getBiome` body in that case.

## Code review and fixes

Six independent read-only reviews covered this pass's changes and the rest of SuperChunk's own
code (the GPU batch pipeline, OpenCL resource handling, worldgen mixins, and player chunk delivery).
Two further reviews, of the compact-id block writer and of compat/config/bootstrap, and three
verification passes were cut off by a desktop crash before reporting. Those two areas remain
unreviewed. Each fix below was checked against the code before it was made. The regression
suite (twelve programs) and the live runs listed above cover the result.

**This pass's own code**

- The cell path's "only SuperChunk mixins on `BiomeManager`" guard read
  `ClassInfo.getAppliedMixins()` for the target class. Mixin records applied mixins on the mixin's
  own `ClassInfo`, so the set was always empty and the guard always passed. The target-side
  registry is package-private and its package is opened only to Gson, so reflection would fail
  in production. The replacement is `MixinTargetScan`. A no-op probe mixin at the highest
  priority (`MixinProbeBiomeLookup`) is applied after every other mixin. `SuperChunkMixinPlugin.postApply`
  then scans the finished class for `@MixinMerged` entries from outside SuperChunk and records
  the result in a system property. The cell path, the flat climate search and ore generator reuse
  now all fail closed unless their classes were scanned and found clean. A regression program
  covers clean, foreign (visible and invisible annotation) and unscanned classes.
- Ore generator reuse was bound to the thread that created the noise chunk (the BIOMES step),
  so a different NOISE worker fell back to allocation. It now reuses one generator per thread.
- Four option descriptions were corrected: `lowMemoryMode` only acts with legacy scheduling
  off, the no-locking rule removes a lock on writes (not reads), the extended render distance
  is requested once at login for any distance above 32, and unreadable booleans read as false.
  The config upgrade now leaves symlinked or read-only files alone and reports a failed write
  as failed.

**Pre-existing issues fixed**

- Predictive generation ran for spectators even with `spectatorsGenerateChunks=false`.
  It now uses vanilla's rule, so a spectator's corridor is withdrawn like a departed player's.
- The GC advisory appended ZGC flags to `user_jvm_args.txt` when that file named no
  collector, even if the running JVM had one from its start script or `JDK_JAVA_OPTIONS`. The
  next start then selected two collectors and the JVM refused to launch. It now checks the
  JVM's actual arguments and the Java option variables first.
- `GpuChunkBatcher`: requests failed at shutdown or on a bucket error completed only their
  density future, leaving a chained climate future (and that chunk's BIOMES step) waiting
  forever. Both paths now fail both futures.
- `GpuClimatePrefetch`: the dimension guard ran after the one-time climate-chain attach it
  exists to protect, so a nether chunk arriving first could lock the overworld out of the chain
  for the dispatcher's lifetime. The guard now runs first.
- `GpuFusedInterpolator`: a failed event wait (kernel abort, out of resources, GPU reset) was
  ignored, so whatever was in the pinned staging (often the previous chunk's corners) became
  terrain. The default async path now takes its blocking fallback, and the opt-in full-field
  paths report "not served".
- `GpuBatchDispatcher`: slice reads copied from pinned staging without the lifecycle lock that
  `ids()` uses, so a completer that outlived its shutdown join could read unmapped memory
  (a JVM crash). They now return false once the dispatcher is closed, and the chunk takes the
  CPU path. `Slot.free()` also drains its queue before freeing host buffers that non-blocking
  writes may still be reading.
- `CLProgramCache`: a program whose build finished after `clearMemory()` was cached with the
  released context and handed to the next session (a singleplayer world reload). A shutdown
  epoch now refuses to cache a build that straddled a clear.
- `MixinPlacedFeatureImperative`: the fast paths matched `RepeatingPlacement` and
  `InSquarePlacement` with `instanceof`, silently ignoring a modded subclass's own
  `getPositions`. They now apply only to classes that inherit vanilla's `getPositions`.

**Reported and not changed (for follow-up)**

- GPU pipeline:
  - No timeout on GPU event waits. One wedged event stalls every chunk at BIOMES or NOISE
    with no CPU fallback.
  - A drainer that outlives its shutdown join can hand over batches after the completer has
    exited.
  - Batch-store entries are keyed by position and geometry only. A Distant Horizons deposit and
    a server chunk at the same position could, in principle, exchange data.
  - The backend is marked unavailable only after its context is released.
  - A few events and queues leak on closed or failed paths.
  - A fusion build that finishes during shutdown can leave batching off for the next
    singleplayer session.
- Player delivery:
  - Client stutter reads as a stop or teleport, so the predicted corridor thrashes.
  - Withdrawn corridor tickets can unload chunks a client was already sent.
  - The `[predictive-gen]` metrics line keeps logging after the last predicted player leaves a
    world.
  - The prediction hit rate is inflated by chunks already inside the no-tick ring.
  - The latency metrics thread never stops.
  - Pending block entities are only created in the ghost-mushroom branch of the early-send path.
  - `player.fullSpeedLoading` removes the client's rate feedback on slow links (the option is
    off by default).
- Worldgen compatibility (vanilla output is exact in every default path reviewed):
  - `MixinSurfaceGradientRandom` overwrites `compute()` even when its switch is off.
  - The post-processing skip detects overrides only by declared methods.
  - The inline-height overrides assume modded chunk subclasses do not override only the base
    height getters.
  - Two caches (decoration steps, carver lava state) are not keyed on everything a mod could
    vary.
  - The opt-in tier-2 post-processing whitelist mishandles double plants and falling blocks.

## Reproduction

```sh
# CPU-mode parity (add --gpu --config gpu.decideFp32=false
#   --jvm-arg=-Dsuperchunk.gpu.compactIds=verify for the strict GPU gate)
python3 tools/run-worldgen-benchmark.py --server-template /path/to/neoforge-test-server \
  --jar build/libs/superchunk-0.4.0.jar --output run/perf/new-parity-dir \
  --radius 256 --seed -987654321 \
  --jvm-arg=-Dsuperchunk.worldgen.flatClimateSearch.verify=true \
  --jvm-arg=-Dsuperchunk.worldgen.biomeFiddleCache.verify=true \
  --jvm-arg=-Dsuperchunk.worldgen.biomeQuartCache.verify=true \
  --jvm-arg=-Dsuperchunk.worldgen.oreRandomReuse.verify=true
```

Run logs, JFRs, jars and result JSON files are under `run/perf-20260924/` (ignored by Git).
The comparison runners are `ab.py` (sequential ABBA), `abn.py` (N jars, rotated per round) and
`pair.py` (both jars generate at the same time, so competing load hits both equally).
Baseline jar SHA-256 `9e35c9981fbaa4313bd60367559714c66f3ceeb2c40604470443fcb230977c22`.
The paired timing, GPU parity and compatibility runs used `cand5.jar`
(`0d195c867cb43686a2e0a4c674626352dfbdda905631e11864c7749470d400d5`). The final build adds only
the placement-subclass guard and passed the same CPU parity run (`parity-cand6`): `cand6.jar`,
`062509ef48372aa04b18fcc4f5d06852261562d9d11d0713f2cc569fd71267c2`.

## Considered and not changed

- `ProtoChunk.getBlockState` prefetch for the surface column memo (~1–2% in GPU mode): it
  would bypass other mods' hooks on that method for reads that currently reach them.
- Batching surface-rule `setBlockState` writes, or skipping same-state writes: changes the
  calls and side-effect order other mods observe.
- Smaller initial `CompoundTag` maps in bundled Lithium's `alloc.nbt`: changes NBT iteration
  order. C2ME's GC-free serializer remains off (experimental upstream).
- `KeyStatusPair` dependency arrays: each holder computes each status's dependencies once
  and stores the array, so reuse needs an API change for short-lived garbage.
- Beardifier: already has an exact AABB skip; the remainder is pieces genuinely in range.

## Known environment issue during this pass

At 11:38–11:40 three benchmark JVMs — baseline and candidate jars alike — died with SIGSEGV
in ZGC young-generation marking (`ZMark::follow_object`) while swap was exhausted by other
workloads (5.7 MB free). No crash occurred in the other runs. The affected legs were discarded
and the A/B was rerun; the reported numbers come only from runs that completed cleanly.
