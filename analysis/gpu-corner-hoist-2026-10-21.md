# GPU corner kernel: column hoisting and per-corner CSE — 2026-10-21

The cross-chunk corner kernel (`df_batch_lattice_multichunk`) was most of the GPU's time per
chunk: `[gpu-timeline]` put it at ~89% of in-batch device busy time, the decide kernel at ~7%.
`GPU-AHEAD-PLAN.md` listed its redundancy as the main GPU-capacity lever. This pass removes most
of it, with output unchanged bit for bit.

## Where the work was going

One work-item computes one lattice corner (5 × 49 × 5 per chunk) and calls each fused root's
generated function. The emitter inlines straight through vanilla's cache markers
(`flat_cache`, `cache_2d`), because on the GPU there is no per-chunk cache to fill. So:

- **Every corner recomputed the 2D terrain shape.** Continentalness, erosion and ridges (each a
  shifted noise over two shift noises), and the offset, factor and jaggedness splines over them,
  were evaluated at all 49 corners of a column. On the CPU vanilla does them once per column.
- **Several times per corner.** Each nested spline level samples its coordinate again, and the
  final density reads `sloped_cheese` as its range_choice input and again inside either branch.
  `McToAst` also rebuilds every reference to a router DF as a fresh subtree, so the program
  carried many copies of the same splines (2.9M chars of source).

A static count over the dumped program: of the noise calls reachable from a corner (all
branches), 4,729 sat in y-independent subtrees and 33 in y-dependent ones.

## Changes

All in `OpenCLAstEmitter` and `GpuBatchDispatcher`. All exact by construction; each has a kill
switch.

### 1. Column hoisting (`-Dsuperchunk.gpu.colHoist`, default on)

A node is **y-free** when its emitted value cannot depend on `y`: constants, `shift_a`/`shift_b`,
noise sampled with `yScale == 0`, splines whose coordinates are y-free, and arithmetic, range
choices and cache markers over y-free children. Anything else, including any node type not
listed, counts as y-dependent. A `yScale == 0` noise does see `(real) y * 0.0`, which is +0.0 or
-0.0 by the sign of `y`. Every sampler absorbs the sign at its first `y + yo` add (and `fabs` in
the wrap bound), so the result is the same.

A y-free node that samples noise or a spline becomes a **column slot**. The hoisted program has
two kernels on the slot's in-order queue:

1. `df_batch_columns_multichunk`: one work-item per (chunk, corner column), `K × 25` of them,
   computes every slot in dependency order into `colOut[gid * SC_COL_SLOTS + s]`. A slot reads
   the lower slots it depends on back from its own row.
2. `df_batch_lattice_multichunk`: the standard corner kernel (same name, arguments and `out`
   layout) plus a trailing `colIn`; every call to a slot node becomes `_col[s]`.

The overworld corner program has 28 slots. A slot is the same generated function at the same
`(x, z)` with FP_CONTRACT off, so its value is identical wherever it is computed.

### 2. Per-corner CSE (`-Dsuperchunk.gpu.cornerCse`, default on)

After emission, a function called on two or more paths from the corner roots, and on at least one
path no branch can skip, is computed once at the top of the corner kernel into `_cor[i]` (a private
array, at most 32 entries), and its calls read that. In the overworld this picks exactly
`sloped_cheese` and the blended 3D noise inside it, the most expensive sampler left after
hoisting. The generated functions are pure, so computing one early is exact whatever the branch
analysis says; the analysis only decides where eager evaluation costs no extra work. Mul's
short-circuit counts as unconditional (it only skips when the left side is exactly 0, e.g.
vanilla's bottom-row `y_clamped_gradient`). Functions reachable from a column slot are never
shared.

### 3. Function dedup (`-Dsuperchunk.gpu.dedupFunctions`, default on)

Node functions with the same expression, spline functions with the same body and spline
constant arrays with the same bits are emitted once, in every program except the decide program.
The same text over the same (already deduped) callees is the same value. This is what lets the
copies of a subtree share one slot; on its own it shrinks the corner program from 2.9M to ~250K
characters.

## Results

### Device time: real router, replayed outside the game

New tool: `-Dsuperchunk.gpu.dumpBatchTo=<dir>` writes the corner program as built and its
counterpart with hoisting flipped, each with its own noise state; `tools/cornerbench.c` replays
them on the device, interleaved, a different batch of chunks each rep, and FNV-hashes every
output bit. RTX 3070, driver 595, seed 8675309. The GPU was shared with a training job at
60–98% utilization throughout, so absolute times swing; the comparison is interleaved.

| Batch K | reps | plain ms/chunk | hoisted | hoisted + CSE | bit-exact |
|---|---|---|---|---|---|
| 8 | 300 | 1.385 | 0.235 (−83.0%) | 0.177 (−87.2%) | yes, all reps |
| 16 | 300 | 1.114 | 0.299 (−73.2%) | 0.127 (−88.6%) | yes |
| 32 | 300 | 1.002 | 0.204 (−79.6%) | 0.169 (−83.1%) | yes |

Earlier runs of hoisting alone: −80.4% (K=16, 40 reps), −79.5% (K=32, 300), −86.8% (K=8, 300),
all bit-exact. The fp32 build of the same program (`gpu.desiredFp=fp32`): −79.6%, bit-exact over
300 batches.

How much of the hoisted kernel the blended noise took was estimated by replacing it with a
constant (not exact, timing only): −52.6%. Halving its evaluations is what the CSE recovers.

### Throughput

`tools/run-worldgen-benchmark.py`, radius 1024 (16,641 chunks), 22 workers, 16 GB heap, GPU on,
three alternating pairs, same jar with `colHoist` false/true:

| | wall (3 legs) | chunks/s | CPU ms/chunk |
|---|---|---|---|
| plain | 17.17, 16.77, 16.48 s | 990 | 22.8 |
| hoisted + CSE | 14.63, 15.20, 14.64 s | 1,123 (+13.4%) | 20.6 (−9.8%) |

Every hoisted leg beat every plain leg. At 12 workers (r512) the two were equal (10.30 vs 10.27 s):
there the CPU is the limit on this box, and the gain needs the GPU to be the scarce resource. It
will be larger on cards with less fp64 throughput, and with other GPU load.

### Cold compile

Truly cold boots (no SuperChunk program cache, `CUDA_CACHE_DISABLE=1`), radius 128:

| | boot | compile total | two largest per-DF programs | per-chunk fused program |
|---|---|---|---|---|
| before | 278 s | 279 s | 82 s + 82 s | 47 s |
| after | 236 s (−15%) | 263 s | 67 s + 67 s | 38 s |

The hoisted corner program itself builds in ~5 s. Dedup alone does not make a program cheap to
compile: NVIDIA inlines the shared functions back, and ptxas time follows the inlined size. What
remains of a cold boot is mostly the per-DF bridge programs (see the plan's cold-start item).

## Parity

- Noise-fill and surface hashes (`-Dsuperchunk.debug.noiseFillHash` / `surfaceHash`,
  `tools/noisehashdiff.py`), compact ids off, radius 512: GPU plain, GPU hoisted, GPU hoisted +
  CSE and CPU all identical, 4,882 chunks, 0 mismatches.
- Same with the dedup build plus a Nether pregen (which takes the per-chunk per-DF path), against
  CPU: 5,386 chunk positions, 0 mismatches.
- Compact ids on (default), hoisted vs plain: surfaces identical over 4,882 chunks. Both differ
  from CPU in the same 2 chunks, the known fp32 decide flips.
- Biome climate offload (`gpu.offloadBiome=true`, which also uses this dispatcher): 1 surface
  difference vs CPU over 2,930 chunks, the same fp32 decide-flip chunk.
- Boot self-tests with `gpu.selftest.gpu_parity=true`: GPU-vs-vanilla PASS (FP64 bit-exact);
  `[batch-emit]` (hoisted batched program vs per-chunk reference) 88,200 values, 0 mismatches;
  `[batch-agg]` PASS.
- fp32 mode (`gpu.desiredFp=fp32`), compact ids off, radius 384, 2,930 chunks: two plain runs
  identical; a second hoisted run identical to them. The first hoisted run differed in one chunk,
  (113, 149), with a hash that was neither the fp32 one nor the CPU one. That run was the only one
  whose programs compiled from scratch while chunks were generating (the dedup changed their text),
  and in fp32 mode a chunk generated while some density functions are still compiling gets a mix of
  fp64 CPU and fp32 GPU values. In fp64 mode both are exact, so this cannot show there. The replayed
  fp32 corner programs were bit-exact (above).
