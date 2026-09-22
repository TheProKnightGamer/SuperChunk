# NeoForge 1.21.1 optimization pass — 2026-09-21

This pass targets repeated CPU work around generation and the GPU result consumer.
It does not change noise arithmetic, OpenCL kernels, worker defaults, or precision.
The starting build includes the compatibility changes already present in the worktree.

## Changes

- Small palettes: single-valued storage emits once; palettes with at most eight
  entries collect distinct IDs in encounter order without allocations and stop
  when all palette entries have appeared. Unsupported storage uses vanilla.
  Collection finishes before invoking consumers, preserving callback behavior.
- CPU noise fill: retrieve the previous palette ID during the write with
  `getAndSet`, avoiding a separate block lookup while preserving palette resize
  handling and Lithium block-tracking updates.
- GPU compact-ID consumption: validate eight bytes at once; count equal-ID section
  prefixes eight blocks at a time; bulk-fill uniform packed sections; reuse
  immutable mappings per worker and palette scratch across sections. Existing
  nonempty-section fallback and block/fluid/ticking/Lithium counters are preserved.
- Scheduling: maintain the highest ticket status incrementally, skip priority
  calculations for positions without queued tasks, avoid lock bookkeeping for
  lock-free tasks, and avoid repeated contended wakeup attempts while the serial
  executor already runs.
- Biome-cache correctness: retain full generation epochs so old managers cannot
  read another manager's entries after the packed generation wraps. Publish the
  initial owner/table together and reject stale inserts after reentrant lookups.
  Verify mode now counts lookups and reports its result at server shutdown.

## Reproducible checks

```sh
./gradlew build --offline
python3 tools/verify-compact-consume.py
```

`check` runs three standalone regression programs without extra test dependencies:

- Scheduler: 1.6 million ticket mutations against a reference maximum; concurrent
  serial-executor work and idle restarts; mixed lock-free/overlapping-lock work.
- Palette collector: 15,200 cases against Minecraft's `SimpleBitStorage`, including
  padded final words, encounter order, unused palette entries, and malformed IDs.
- Biome cache: generation wrap, nested wrap during resolution, 100,000 interleaved
  manager lookups, thread isolation, and coordinate bounds.

The compact-consumer script extracts the changed production loops and section
writer, then compares 510,800 scanner cases, 7,097 histograms, 3,000 packed sections,
and 500 section writes against scalar references. The section tests use API doubles
and include counters and nonempty-section fallback; they complement live Minecraft
tests rather than establish whole-world parity by themselves. `--bench` adds
isolated timings, which must not be presented as overall generation speedups.

## Packaged-server validation

`tools/run-worldgen-benchmark.py` creates a new output directory for every run,
copies the selected jar and Chunky, and reuses only the template's libraries and
optional GPU binary cache. It never deletes worlds or stops unrelated processes.
The template must already have an accepted EULA and one Chunky jar. The server
binds only localhost on an ephemeral port; commands use standard input.

Example (run from `neo-1.21.1`; choose a new output directory for each invocation):

```sh
python3 tools/run-worldgen-benchmark.py \
  --server-template /path/to/neoforge-test-server \
  --jar build/libs/superchunk-0.3.0.jar \
  --output run/performance/example-gpu \
  --radius 1024 --workers 12 --heap 8G --gpu
```

Add `--profile` for a generation JFR. For parity checking, add
`--jvm-arg=-Dsuperchunk.gpu.compactIds=verify` and
`--jvm-arg=-Dsuperchunk.worldgen.biomeQuartCache.verify=true`; these checks add work
and should run separately from throughput measurements.

The September 21 runs use Java 21, NeoForge 21.1.248 for Minecraft 1.21.1,
Chunky 1.4.23, 12 workers, an 8 GiB heap, Generational ZGC, seed 8675309,
center (2048, 2048), radius 1024, and 16,641 requested chunks per fresh world.
GPU runs use an RTX 3070. Both CPU variants record JFR; neither GPU variant does.
All run logs, JFRs, jar snapshots and result JSON files are under
`run/optimization-20260921/` (ignored by Git).

The optimized build won both repetitions in each mode:

| Mode | Baseline chunks/s, runs 1 / 2 | Optimized chunks/s, runs 1 / 2 | Mean change |
|---|---:|---:|---:|
| CPU, JFR enabled | 464.1 / 474.2 | 479.2 / 480.3 | +2.3% |
| GPU | 721.1 / 711.4 | 765.8 / 759.9 | +6.5% |

These eight completed runs generated 133,128 requested chunks, excluding neighbors
and spawn preparation. Every run exited cleanly with no ERROR/FATAL messages,
native crashes, out-of-memory errors, or compact-consumer faults. GPU logs confirm
the RTX 3070 and actual compact-ID consumption. The first GPU pair's measured
consumer time fell from 1.073 to 0.869 ms/chunk; this is a concurrent wall-time
counter, not an isolated CPU-cycle measurement.

Baseline jar SHA-256:
`409afa650a63c25e61fe2e5c53dbe67598994153fe3c81624cdd6b3053134da9`.
Optimized jar SHA-256:
`baa59ba5ca2a47d39641ffbc8ab4f8b2e42eaa5d104425327addd94307a36aa4`.
The source build targets NeoForge 21.1.215; the available packaged test installation
uses 21.1.248, both for Minecraft 1.21.1.

These are short runs on a shared desktop, so small differences include system-load
and JVM warmup noise; they are not universal throughput promises. The older README
headline used a different worker count, heap, radius, and operating conditions.
Full region hashes are not an appropriate parity gate for parallel feature
generation, whose overlap ordering already varies between identical runs.

## Live parity and existing precision limitation

A separate radius-256 run with seed **-987654321** checked 1,465 noise chunks and
143,200,256 blocks (including generation neighbors). With the existing default
fp32 decision kernel, **both the baseline and optimized jar** report the same
single stone/air mismatch at **(1844, -3, 2291)**, plus its corresponding one-block
section-count difference. Both therefore correctly fail the strict parity gate.
There were no heightmap, fluid, ticking, Lithium-flag, or postprocessing mismatches.
This reproduces a preexisting precision limitation, not a new optimization fault.

Repeating the optimized run with `--config gpu.decideFp32=false` reports
**zero mismatches across all 143,200,256 blocks and every side-effect category**.
Biome-cache verification also passes: 8,301,939 checked hits, 1,492,621 misses,
and zero mismatches. The fp32 run independently passed 8,301,694 biome-cache hits.

The live compact-ID verifier runs the vanilla writer and compares a shadow model;
it does not execute the optimized bulk writer. That writer is covered by the
source-extracted differential cases and the successful normal GPU generation runs.
The measured +6.5% throughput uses the original fp32 setting. Defaults were left
unchanged; a user requiring the stricter decision path can set
`gpu.decideFp32=false` in `config/superchunk.properties`, or disable compact IDs
with `-Dsuperchunk.gpu.compactIds=off`.

Reproduce the fp64 check with the benchmark example above plus:

```sh
--radius 256 --seed -987654321 --config gpu.decideFp32=false \
--jvm-arg=-Dsuperchunk.gpu.compactIds=verify \
--jvm-arg=-Dsuperchunk.worldgen.biomeQuartCache.verify=true
```
