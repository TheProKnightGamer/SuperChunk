# Small GPU batches and the aquifer cell cache — 2026-10-22

Two follow-ups to `analysis/gpu-beard-2026-10-22.md`.

## Small batches: one warp per work-group

At 22 workers the batcher's mean batch is 4–5 chunks (r1024: 1,055 single-chunk dispatches, 1,669
of 2–4, 638 of 5–8, 465 larger). `tools/cornerbench.c` (now also printing the column pass) showed
a batch of 1–8 chunks costing about the same 1.2–1.4 ms as each other: latency, not work.

The cause was the work-group size. Every dispatch left it to the driver, which picked 256 on
NVIDIA, so a small batch's few warps sat together on a few SMs and shared their fp64 units (GA104
has two per SM). The column pass shows it most plainly: 25 work-items for one chunk took 0.27 ms,
200 for eight took 0.55 ms. One warp per work-group spreads them over all 46 SMs.

`CLProgram`'s 1-D enqueue now passes a local size (`-Dsuperchunk.gpu.localSize`): `auto` (default)
is the kernel's preferred multiple, at least 32 (a warp on NVIDIA, a wavefront on AMD), capped by
the kernel's maximum, and only when it divides the global size; `0` restores the driver's choice.
No kernel uses local memory or barriers, so results cannot change.

Replayed hoisted corner program (column pass + corner kernel), RTX 3070, 100 interleaved reps,
bit-exact:

| Batch K | driver (256) | local 32 | local 64 | local 128 |
|---|---|---|---|---|
| 1 | 1.171 ms | 0.566 (−51.7%) | 0.587 | 0.753 |
| 4 | 1.215 | 0.678 (−44.2%) | 0.717 | 0.897 |
| 16 | 2.041 | 1.634 (−19.9%) | 1.738 | 1.841 |
| 32 | 4.019 | 3.519 (−12.4%) | 3.910 | 4.078 |

Decide kernel replay (`tools/decidebench.c`, which now takes `variant.cl@<local>`): 32 or 64
−7.0%, 256 the same as the driver's.

In game, r1024, 22 workers, same jar, `localSize` auto vs 0, two interleaved rounds, at the same
mean batch size (4.1–4.4): corner stage 0.869 / 0.932 ms per batch vs 1.616 / 1.675 (−44%), decide
0.102 / 0.118 vs 0.153 / 0.178, whole batch on the device 1.090 / 1.185 vs 1.915 / 2.014 ms (−42%):
about 0.27 instead of 0.46 ms of GPU time per chunk. Chunks per second did not move: on this box a
pregen is CPU-bound. What changes is how long the GPU is held, which matters with other GPU work
(a client rendering, another job) and on GPUs where the batcher is the bottleneck.

fp64 `compactIds=verify`, r512: neo 477M blocks, forge 537M blocks, 0 mismatches.

### Tried and not kept

- **Roots split** (one work-item per root and corner, root-major): −16 to −28% at K ≤ 4 with the
  driver's group size, but with 32-wide groups it adds only 0–5% (noisy at K=32), for a second
  kernel shape in the emitter.
- **Blended noise across 32 lanes** (one work-group per corner, octave terms in parallel, summed
  in order: bit-exact): −23% at K=1, but +5% at K=4 and +115% at K=32 (idle lanes).
- **Column pass by dependency level**: the 28 slots form a chain about 12 levels deep (2–3 slots
  per level), so little to win. A first level map missed the `splN` spline functions' slot reads
  and produced a mismatch, which the output hash caught.

## Aquifer cell cache: lock-free 4-way table

The aux capture asks for all 315 aquifer cells of every chunk, and the cross-chunk cache
(`ScAquiferCellCache`) answered from 64 locked, access-ordered maps, reordering one on every hit.
JFR (r1024, GPU): lookups 0.54% of all worldgen CPU, the LRU upkeep (`moveIndexToFirst`,
`removeLast`, `shiftKeys`, `putAndMoveToFirst`) another 0.42%.

It is now a lock-free 4-way set-associative table of immutable (key, status) entries, LRU per set.
JFR after: the upkeep is gone; the lookups cost the same 0.53% (they are random memory reads either
way). Hit rates (`-Dsuperchunk.worldgen.aquiferCellCache.metrics=true`), r1024 GPU pregen:

| Cache | Entries | Hit rate |
|---|---|---|
| old LRU maps | 262,144 | 83.63–83.71% |
| 4-way | 262,144 | 82.2% (2-way and FIFO the same) |
| 4-way (default) | 524,288 | 83.41% |
| 4-way | 1,048,576 | 83.87% |
| 4-way | 2,097,152 | 84.06% |

A miss recomputes the cell (about 2 µs). The default is 524,288 entries (about 14 MB full; the old
maps were about 10.5 MB). Net effect about 0.4% of CPU, below what wall-clock pairs on this box
resolve; the JFR shares are the evidence. CPU-mode noise-fill and surface hashes against the old
cache: 0 mismatches over 4,882 chunks (neo) and 5,490 (forge).
