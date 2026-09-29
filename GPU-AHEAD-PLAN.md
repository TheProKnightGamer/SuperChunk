# GPU-AHEAD PLAN — status and next steps (updated 2026-09-28)

The 2026-07-01 plan set out to move the per-block noise-stage decision onto the GPU: instead of
reading back density corner grids for the CPU to interpolate and decide, a batched GPU kernel
decides every block and reads back one byte per block ("compact readback"). The user accepted a
relaxed parity envelope ("one or two blocks can flip") for this path. That plan was carried out:
compact readback has shipped, on by default when the GPU is enabled, since round 8. This file
records what shipped, what it measured, where parity stands, and what is left. The original plan
and its research appendix are in git history (`03018dc`).

SuperChunk's GPU path is its own code (`dev.superchunk.gpu`, kernels in
`src/main/resources/superchunk/kernels/`). It does not use, port or merge C2ME's separately
licensed OpenCL module (c2me-ocl, `c2me-opts-accel-opencl`), and none is planned; see "Not
planned" below.

## What runs on the GPU today

- **Density functions.** All ~104 router density functions are compiled from C2ME's DFC AST to
  OpenCL C by SuperChunk's emitter (`OpenCLAstEmitter`), fp64, and each is gated at registration
  by the parity self-tests. The fp64 path is bit-exact to vanilla.
- **Batched corner grids.** Worker threads hand chunks off at the noise-status seam
  (`GpuBatchPrefetch.wrapNoise`) and are freed; one drainer thread (`GpuChunkBatcher`) coalesces
  up to `gpu.batchLimit` = 32 chunks per dispatch (`batchWindowMicros` = 500, pipeline depth 2)
  into fused multi-chunk corner kernels on one in-order queue; a completer thread slices results
  into `GpuBatchStore`, which serves them before the fill needs them.
- **Decide kernel (compact ids).** Chained on the same queue after the corner kernel
  (`CompactIds`, `decide.cl`): in-register interpolation in both vanilla orders, the router's
  real finalDensity tail (emitted from the AST), the aquifer decision (`aq_decide`) with the
  `shouldScheduleFluidUpdate` bit, and the ore-vein decision with bit-exact Xoroshiro draws. One
  byte per block (id, bit 7 = fluid-update flag), about 96 KB per chunk.
- **Per-chunk aux, CPU-authoritative.** Captured worker-side at the prefetch seam: the 315-cell
  aquifer FluidStatus table, the preloaded location cache, grid geometry and picker parameters,
  the beard AABB (blocks inside it get a sentinel and are decided on the CPU), and the ore seed.
  `computeFluid` and the surface scan stay on the CPU, so carvers and the GPU read the same
  FluidStatus.
- **Consumer.** `CompactConsume` replaces `doFill`'s per-block loop for batched chunks: bulk
  id-to-palette writes, section counters and Lithium flags, both worldgen heightmaps, and
  post-processing marks from bit 7. Any geometry, route or unknown-byte mismatch sends that chunk
  to the vanilla loop (counted per reason); nether/end, blending chunks and chunks without aux
  never get ids.
- **Stays on the CPU:** biomes (`gpu.offloadBiome` = false), structure-probe column samplers
  (`subLatticeGpu` = false), surface rules, carvers, features, lighting.

Precision defaults: corner grids and the aquifer comparison algebra are always fp64. The decide
kernel's own math is fp32 by default (`gpu.decideFp32` = true; set false for fp64). FMA
contraction stays off everywhere (`decideFmaContract` = false).

## Stage outcomes (2026-07-01 plan)

| Stage | Outcome |
|---|---|
| 0 Baseline freeze | Done. |
| 1 Spline-drift attribution | Resolved: the fp64 GPU path is bit-exact (the crDivide fix); spline drift no longer exists to gate. |
| 2 Decide-kernel duty bench | Built (`DecideBench`); the chain then shipped. The decide kernel turned out bound by the integer aquifer candidate loop and memory, not fp32 ALU. |
| 3 Self-consistent flip census | Passed: zero deviations over 455.9M blocks plus four ~460M-block censuses (`BlockIdCensus`, 3-way oracle), including the corruption class (GPU ≠ Java reference). |
| 4 fp64 corruption, single drainer | Sidestepped by topology: every decide dispatch runs on the one drainer queue, and the censuses (which count GPU ≠ Java reference as its own class) found zero deviations. The artifact still reproduces with per-worker dispatch (`AquiferGpuVerify`: single worker 0 bugs, multi-worker corruption). |
| 5 Plumbing-tax probe | Built (`compactIds=probe`, ids computed and discarded); the consume path shipped after it. |
| 6 Consume path | Shipped; default `on` since round 8, with `compactIds=verify` as the side-effect equivalence gate (zero divergence required). |
| 7 Soak and hardening | Partly: per-chunk validation and CPU fallback exist; the always-on 1-in-N re-verify sampler with auto-disable was never built. |

## Measured results

Throughput (RTX 3070, i7-14700K; chunks/s):

| Date | What | Result |
|---|---|---|
| 2026-07-01 | Plan baseline, GPU on (corner grids only) | 450–500 @12 workers, 632–664 @21; the GPU offload was worth 1.000× |
| 2026-07-16 | Round 8, r2048, quiet box (released in 0.1.0, 07-17) | CPU only 815.4 @21 workers; GPU strict (`compactIds=off`) 869.1 @21; GPU default recipe 1,119.5 @22–24 (10.0× vanilla, +37% over CPU only; GPU 90–93% busy) |
| 2026-08-08 | Round 10, r2048, interleaved, quiet box | GPU 1,258.2 vs CPU-only 836.1 vs vanilla 114.9 (11.0×). Kernel fp64 op count cut by exact IEEE identities: noise chain −22%, device time 1.542 → 1.354 ms/chunk |
| 2026-09-21 | r1024, 12 workers | GPU 711–766 vs CPU 464–480 |
| 2026-09-25 | r1024, 12 workers | GPU 903–959 (`analysis/worldgen-big-2026-09-25.md`) |

Round 8 also took the decide kernel to its floor (async id readback, a proven high-air fast path,
lazy-staged vein evaluation): what remains is vanilla's own per-block 3D-noise math. Round 10 found
the corner and decide kernels fp64-op-count bound, not gather-bound, which is why exact IEEE
identities paid there.

Decisions the measurements settled:
- Per-worker full-field "residency" (`-Dsuperchunk.gpu.onDeviceInterp`): −33%, stays off.
- Biome climate offload: −22% at 24 workers once the decide chain made the GPU the scarce
  resource (805.5 vs 1,032); off by default. Its fp32 variant was refuted: domain-warped noise
  amplifies fp32 drift past any usable guard band.
- Structure-probe column samplers routed to the CPU: +18% at 21 workers.
- Kernel merge (one program per router): cold JIT much slower; off.
- fp32 decide with FMA: zero flips over 459.7M blocks but throughput-neutral; off.
- CPU-side noise-fill optimizations are flat in GPU mode, because consumed chunks skip `doFill`.
- Distant Horizons: the GPU does not speed DH's own generator; the batch seam removes a ~20% tax
  DH paid for per-chunk dispatches (README, 2026-08-14).

## Parity envelope

- GPU off, or `-Dsuperchunk.gpu.compactIds=off`: bit-exact vanilla.
- Compact ids with `gpu.decideFp32=false`: zero mismatches over 143,200,256 blocks and every
  side-effect category (r256, seed −987654321, 2026-09-21).
- Compact ids with the default fp32 decide: the same run has one stone/air difference at
  (1844, −3, 2291), in both the jar before and after that day's changes. The earlier zero-flip
  censuses therefore do not establish universal equivalence for fp32.
- Relaxed-parity terrain is precision- and driver-dependent. Program binaries are keyed by
  `CL_DRIVER_VERSION` and rebuild on a driver change; census results measured on one driver do not
  carry over to another.

## Next steps

Ranked by expected payoff (from `analysis/review-2026-09-25.md` and the GPU-mode A/Bs since):

1. **Corner-kernel redundancy** (M–L). The fused corner kernel recomputes cached 2D and shared
   subtrees, an inferred ~5–7× redundancy. This is the main GPU-capacity lever now that the GPU is
   the scarce resource at high worker counts.
2. **Cold start** (M). Per-DF programs compile on the server thread, plus a redundant fused
   per-chunk program: ~6 min with a cold cache, ~1–2 min is reachable.
3. **Nether and End never batch** (M). Their chunks take the per-chunk path and get no compact ids
   (the aux capture needs a noise aquifer); routing them through the batcher is the first step.
4. **Batching knobs** (S). A/B the batch window and a minimum K, and re-test column dedup
   (`-Dsuperchunk.gpu.colDedup`: removes 19% of corner column evaluations, no device-time win at
   round-10 batch sizes).
5. **Worker-side aux capture** (S–M). In GPU mode the per-chunk 315-cell FluidStatus capture is
   one of the few noise-stage costs left on the workers; profile it before changing anything.
6. **fp32 decide default** (decision). One stone/air flip per ~143M blocks on one seed; decide
   whether the default should be fp64 decide (measure its throughput cost first).
7. **Stage 7 remainder** (S). An always-on 1-in-N chunk re-verify with auto-disable, since ids are
   written into saved terrain.

## Not planned

- **C2ME's OpenCL module** (c2me-ocl, `c2me-opts-accel-opencl`). It is a separate,
  All-Rights-Reserved project. SuperChunk will not merge, port, depend on or benchmark against it,
  and none of its code or kernel text is in this repository. The 2026-07-01 research used it as
  prior art and proposed a calibration run and "c2me-style" follow-ons (4×4 spatial tiles, GPU
  surface-height area tiles, native bulk palette writes); those proposals are withdrawn.
- **Spatial 4×4 tiling / chunk-system surgery.** Temporal K ≤ 32 batching delivered the gains; the
  vendored chunk system stays as it is.
- **Re-enabling the residency path or biome offload by default**, without a new measurement that
  overturns the numbers above.
