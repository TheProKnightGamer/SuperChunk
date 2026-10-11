# GPU compile time — 2026-10-10

RTX 3070, driver 595.91.07, vanilla world, seed -987654321. The GPU was shared with another CUDA
job throughout, so throughput numbers are interleaved, relative comparisons.

## Where a cold boot goes

A truly cold GPU boot took 431–450 s. Truly cold means an empty
`config/superchunk-gpu-cache` plus `CUDA_CACHE_DISABLE=1`.

New tooling:
- `-Dsuperchunk.gpu.dumpProgramsTo=<dir>` writes every program compiled from source.
- `tools/compilebench.c` times those programs outside the game.

Results:
- 39 programs, 521 s of compiling. It runs one at a time on the server thread during world creation,
  except the fused program, which builds on its own thread once chunks generate.
- Three ~2.9M-char programs are two-thirds of it: 145 s and 139 s (the two largest per-DF programs)
  plus 83 s (the fused program). The other 31 programs are 22 s in total.
- The two big per-DF programs served only 200 dispatches before the fused program took over.
- NVIDIA serializes `clBuildProgram` inside a process. 8 threads took 21.2 s against 22.5 s
  sequential, each build ~6× longer. A small program logged at 69 s in game had only waited for the
  driver lock behind the fused build.
- Front end vs ptxas, one fused program: 74 s from source, 61 s rebuilding from the returned binary.
  On NVIDIA that binary is PTX, so ~80% of a build is ptxas.
- So SuperChunk's disk cache saves only the front end on NVIDIA. A boot with every program a
  disk-cache hit but NVIDIA's ComputeCache empty still took 437 s (288 s at level 1).
- SuperChunk's share of the ComputeCache is ~94 MB (38 entries), small next to the driver's ~1 GB
  default limit.

## NVIDIA fast compile: `-cl-nv-opt-level=1` by default

`cl_nv_compiler_options` (NVIDIA's OpenCL extension) defines `-cl-nv-opt-level=N`, the ptxas
optimization level. The space-separated form is rejected; `=` works. The device record now carries
`nvCompilerOptions` (the extension is present). `CLProgram.effectiveOptions` appends the flag only
there, so the program caches key on it. Override: `-Dsuperchunk.gpu.nvOptLevel=N` (0–3, or -1 for
the driver's default).

Cold boots:

| level | boot | compiling | fused program |
|---|---|---|---|
| driver default (3) | 436 s | 520 s | 81 s |
| **1** | 313 s (309 s on the final build, with the parity self-test) | 369 s | 51 s |
| 0 | 229 s | 305 s | 41 s |

Warm pregen throughput, interleaved legs (Chunky total time):

| level | r1024 (16,641 chunks) | r2048 (66,049 chunks) |
|---|---|---|
| driver default | 21, 22 s | 82, 86 s |
| 1 | 19, 21 s | 86, 85 s |
| 0 | 25, 28 s (23% slower) | — |

Level 1 is within noise of the default in both sizes; level 0's runtime cost is real.

Parity at level 1:
- The fp64 GPU-vs-vanilla self-test passed.
- Compact ids off: noise-fill hashes of 1,490 chunks were identical across CPU, GPU at level 1 and
  GPU at the default.
- Compact-ids verify at level 1 over 474M blocks: one water→stone flip at (−5844, 52, 6316). The
  driver default shows the identical flip at the same block; it is the known fp32 decide flip
  (`gpu.decideFp32=true`), not the level.

## Not done (candidates)

- Merging the giant per-DF programs into one per program body would not help: marking the root
  function `noinline` left the PTX the same size and ptxas just as slow.
- The per-DF programs of DFs the fused program covers are boot-blocking bridges (~280 s cold at the
  default level, ~200 s at level 1). Building them off the server thread, or not at all, needs fusion
  discovery to stop depending on a resolved per-DF program (`ChunkGridCache.tryServe`).
- Builds in helper processes would get past the per-process driver lock. The machine code would
  then reach the main process through NVIDIA's ComputeCache.
