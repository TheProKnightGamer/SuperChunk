# NeoForge 1.21.1 second optimization pass — 2026-09-21

This pass starts from the optimized jar in [the first report](optimization-2026-09-21.md).
It targets the remaining CPU and allocation costs found in generation JFRs. Noise
arithmetic, GPU precision, worker defaults, and generation rules remain unchanged.

## Profile findings and changes

The first-pass CPU profile showed aquifer ceiling memoization among the leading
CPU samples: 698 of its 699 leaf samples were map lookups. A longer GPU-enabled
profile put biome lookup first among CPU leaf samples. Allocation samples also
identified section serialization buffers, short-lived surface RNGs, and scheduler
release callbacks as substantial sources of garbage. Allocation sampling weights
are estimates, not exact allocation totals.

- Biome jitter: replace floor-modulo by 1024 with its exact low-ten-bit mask,
  including negative inputs. Preserve all corner comparisons and floating-point
  arithmetic. A corner-pruning candidate was discarded after it benchmarked worse
  than the simpler mask change.
- Aquifers: replace hashed ceiling lookups with a small dense table for the usual
  candidate columns. Preserve the consecutive-column fast path and a map fallback
  for coordinates outside that table; fluid calculations are unchanged.
- Surface gradients: compute the first positional random float directly for exact
  vanilla factory classes, avoiding the discarded RNG and its helper objects.
  Preserve the all-zero Xoroshiro state repair. Custom factories and subclasses
  take the original path. Constructor arguments are captured by type because
  development and production jars use different synthetic field names. The small
  condition method preserves vanilla's bound order, `Mth.map`, and comparison
  directly, avoiding a cancellable callback allocation on every sample.
- Section serialization: reuse bounded per-thread unpack buffers, and skip the
  unpack entirely for uniform zero-bit storage. Preserve vanilla palette encounter
  order, packed representation, detached mutable results, and acquire/release.
  Only stock storage and stock/bundled Lithium palettes use this path. Nested calls
  get independent scratch, and packed streams never reference reusable buffers.
- Scheduling: lock-free tasks share a stateless release callback. Tasks holding
  locks retain their independent atomic once-only release guard.
- GPU consumption: reuse histogram, palette, and heightmap scratch with a nested
  call guard and exception cleanup. Reuse section histogram bounds to avoid
  rescanning sections proven to contain no writes. Heightmap and fluid-mark call
  ordering is preserved.

Individual switches are available for troubleshooting:

```text
-Dsuperchunk.worldgen.biomeFiddle=false
-Dsuperchunk.worldgen.surfaceGradientRandom=false
-Dsuperchunk.io.fastPalettePack=false
```

These switches disable only the corresponding new optimization.

## Regression checks

```sh
./gradlew build --offline --console=plain
python3 tools/verify-compact-consume.py
```

At this stage the build ran seven standalone regression programs. Added coverage includes:

- 1,500,000 dense aquifer cache comparisons against the previous map behavior.
- 1,000,000 signed-modulus checks, 553,154 nearest-biome-cell comparisons, and
  4,425,232 bit-exact corner-distance checks.
- 880,438 positional-float comparisons against actual Minecraft RNG factories,
  including zero-state repair, extreme coordinates/seeds, and subclass fallback;
  another 44 cases cover gradient thresholds, equal/inverted anchors, full integer
  ranges, and exact fallback RNG-call counts with the optimization enabled/disabled.
- 530 palette serialization comparisons against actual vanilla `pack`, including
  block and biome strategies, palette thresholds, bundled Lithium palettes,
  detached results, and reentrant callbacks.
- Deferred scheduler release callbacks invoked off-thread, repeated/stale release,
  task failures before and after release, and successful successor execution.

The compact-consumer script additionally compares 120 full-fill cases for
side-effect ordering, plus nested-fill isolation and exception cleanup. Existing
section content is covered by the separate 500 section-write comparisons among
the earlier 521,397 scanner/histogram/section cases. It extracts actual
production methods into deterministic API doubles; it does not substitute for
testing the packaged mod in Minecraft.

## Packaged measurement protocol

Fresh-world runs use Java 21.0.12, NeoForge 21.1.248 for Minecraft 1.21.1, Chunky
1.4.23, 12 workers, an 8 GiB heap, Generational ZGC, seed 8675309, center
(2048, 2048), square radius 1536, and **37,249 requested chunks per run**. GPU runs
use the RTX 3070 and the existing default fp32 decision setting. Timed comparisons
have no JFR or verification counters enabled. Profiling and parity runs are separate.

```sh
python3 tools/run-worldgen-benchmark.py \
  --server-template /path/to/neoforge-test-server \
  --jar build/libs/superchunk-0.3.0.jar \
  --output run/performance/new-output-directory \
  --radius 1536 --workers 12 --heap 8G
# Add --gpu for the GPU variant. Use a new output directory for every run.
```

Logs, result JSON files, profiles, and jar snapshots are under
`run/optimization-round2-20260921/` (ignored by Git). The reference jar is the
first-pass optimized build, SHA-256
`baa59ba5ca2a47d39641ffbc8ab4f8b2e42eaa5d104425327addd94307a36aa4`.
The initially measured candidate has SHA-256
`6630d3a830e54a4cecaeec6d0c2d8d554b292a6f9d9750185ada735f32bfdf96`.
The final jar for this pass (snapshot `final.jar` in the run directory) has SHA-256
`81ec686ab53703aa963b25e5f5f24a4dfed8fad871ee36644448c0ee1d5c7792`.

The user confirmed that the server was heavily loaded during these measurements.
Other active Java and Python processes were also observed. The first CPU pair
favored the new build, while the second favored the reference. These runs therefore
**do not establish an overall throughput gain or regression**. Further timing
repeats were stopped after the already queued runs; a separate allocation profile
is more useful under these conditions. No percentage speedup is claimed for this
pass. The older README headline used a different workload and machine configuration.

Raw observations, retained for transparency rather than as a performance claim:

| Mode | First-pass reference chunks/s, runs 1 / 2 | Measured candidate chunks/s, runs 1 / 2 |
|---|---:|---:|
| CPU | 517.1 / 519.7 | 528.0 / 500.4 |
| GPU | 786.5 / 799.4 | 799.4 / 779.2 |

All eight runs completed **297,992 requested chunks** in total, excluding neighbors
and spawn preparation. They exited cleanly with no captured ERROR/FATAL messages,
compact-consumer faults, native crashes, or out-of-memory errors. The GPU runs
confirm the RTX 3070 and actual compact-ID consumption. This establishes successful
generation on the tested workload, independently of the inconclusive timing data.

The final build subsequently removes the surface hook's callback allocation. Its
throughput was not retimed under the known competing load.

## Allocation evidence from the packaged server

Separate JFR captures of the reference and final jars each cover 37,249 requested
chunks with the same GPU workload. Summing `jdk.ObjectAllocationSample` weights
for the relevant call stacks gives:

| Allocation category | First-pass reference | Final jar |
|---|---:|---:|
| Integer arrays under palette packing | 18.37 GB | 105.5 MB |
| Surface-gradient RNG and Gaussian helper objects | 12.76 GB | None sampled |
| Scheduler worker `AtomicBoolean` release guards | 1.90 GB | 48.0 MB |

These are **sampled cumulative allocation estimates**, in decimal units, not exact
allocation counts or peak heap usage. Their large reductions support the intended
removal of repeated temporary objects. They do not establish a chunks/s improvement.

An intermediate profile exposed about 3.02 GB of sampled surface callback
allocations introduced by the first version of the new hook. That prompted the
final small condition-method replacement; the final profile samples neither those
callbacks nor the discarded surface RNG objects. It also samples the actual
`FastPalettePacking.pack` and `FirstPositionalFloat.testGradient` methods, confirming
that the packaged server exercises them.

Raw summaries are in `final-allocation-comparison.txt`, with the bounded JFR reader
in `AllocationSummary.java`, under the run directory. The reader uses the JDK's
streaming `RecordingFile` API and a 256 MiB heap. The final profiled generation run
also completed and exited cleanly with no captured health errors.

## Live correctness checks

A separate seed **-987654321**, radius-256 run of the final jar (`parity-final/`) passed:

- 1,465 noise chunks and **143,200,256 blocks** compared, with zero block,
  heightmap, section-counter, Lithium-flag, or postprocessing differences.
- **3,975,496 surface-gradient samples** checked against the original positional
  RNG call, with zero mismatches. This also confirms the hook runs in the packaged
  server, beyond the standalone arithmetic tests.
- **8,301,532 biome-cache hits** verified, with zero mismatches.
- At least **94,000,004 aquifer air checks**, including 89,150,626 adaptive skips,
  with zero water/stone deletion bugs and no tripped safety gate at the last
  periodic report.

The compact-ID check uses `gpu.decideFp32=false`. The preexisting rare fp32
stone/air discrepancy documented in the first report is still relevant; defaults
are unchanged. The live compact-ID verifier compares a shadow model while the CPU
writer runs. Source-extracted differential cases exercise the actual optimized
bulk-writing logic, and the normal GPU benchmarks exercise it in Minecraft.

```sh
python3 tools/run-worldgen-benchmark.py \
  --server-template /path/to/neoforge-test-server \
  --jar build/libs/superchunk-0.3.0.jar \
  --output run/performance/new-parity-directory \
  --radius 256 --seed -987654321 --gpu \
  --config gpu.decideFp32=false \
  --jvm-arg=-Dsuperchunk.gpu.compactIds=verify \
  --jvm-arg=-Dsuperchunk.worldgen.biomeQuartCache.verify=true \
  --jvm-arg=-Dsuperchunk.worldgen.surfaceGradientRandom.verify=true \
  --jvm-arg=-Dsuperchunk.worldgen.adaptiveAirSkip.verify=true
```
