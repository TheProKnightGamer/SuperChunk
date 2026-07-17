# SuperChunk

**Extremely fast chunk generation for Minecraft.** A standalone NeoForge 1.21.1
server-side worldgen performance mod with a from-scratch **GPU (OpenCL) worldgen
offload**, built by merging and extending the source of **C2ME + ScalableLux +
Lithium + Noisium + VMP** into a single jar (all relocated under
`dev.superchunk.*`), plus ~20 original worldgen optimizations and the
`dev.superchunk.gpu` OpenCL pipeline.

## The headline — 10× vanilla

Chunky pregen, radius 2048 (66,049 chunks), fresh seed-8675309 worlds, one box
(RTX 3070 + i7-14700K, NVMe):

| Configuration | chunks/sec | Wall time | vs vanilla |
|---|---|---|---|
| Vanilla NeoForge 1.21.1 | 111.9 | 9 m 50 s | 1.0× |
| Stock mods together, unedited (C2ME + Lithium + Noisium + ScalableLux) | 660.5 | 1 m 40 s | 5.9× |
| SuperChunk, CPU only (GPU disabled) | 815.4 | 1 m 21 s | 7.3× |
| **SuperChunk + GPU offload** | **1,119.5** | **59 s** | **10.0×** |

All legs: 0 faults, 0 SIGSEGV, 0 OOM. The GPU path's default configuration
passed the fp64 bit-exact gate (131M blocks, zero divergence) and the fp32
decide-kernel flip census (460M blocks, zero flips) — **the generated terrain is
identical to vanilla's**.

Ladder detail on the same workload:

| Configuration | Workers | chunks/sec | GPU util |
|---|---|---|---|
| CPU-only (GPU disabled) | 21 | 815.4 | — |
| GPU, strict parity (`compactIds=off`) | 21 | 869.1 | 79% |
| GPU, full recipe (round 7) | 22 | 1065.3 | 94% |
| + async ids readback (round 8) | 24 | 1100.8 | 92% |
| + high-air fast path, lazy veins (round 8) | **22–24** | **1119.5** | 90–93% |

## Install — one jar, client and server alike

1. NeoForge **1.21.1** (Java 21), client or dedicated server.
2. Drop **`superchunk-<version>.jar`** into `mods/`. That single jar is complete
   for both sides: it bundles the LWJGL/OpenCL bindings and, *only on a dedicated
   server* (which ships no LWJGL of its own), self-provides the LWJGL core module.
   A client's own LWJGL is left untouched. The OpenCL driver comes from your GPU
   vendor's normal driver install.
3. GPU offload is on by default when a capable OpenCL device is present
   (`cl_khr_fp64` required); everything falls back to the CPU path cleanly when
   it isn't. The one switch: `gpu.enabled=true|false` in
   `config/superchunk.properties`.
4. Worker count and heap are the only machine-specific tuning
   (C2ME auto-scales by default):

   ```
   -Dc2me.base.config.override.globalExecutorParallelism=22
   -Xmx16G
   ```

**Standalone Lithium is supported alongside SuperChunk:** if a `lithium` jar is
installed in the same instance, SuperChunk's bundled Lithium stands down
automatically and the installed one is authoritative — including exact
maintenance of its block counters from SuperChunk's GPU/direct-write paths
(verified zero-divergence over 100M+ blocks against Lithium 0.15.4). Do **not**
also install standalone C2ME / ScalableLux / Noisium / VMP — those are inside
this jar.

## What's inside

| Engine | What it brings |
|--------|----------------|
| **C2ME** | Multithreaded chunk generation / IO / loading (gen runs on `c2me-worker` threads) |
| **ScalableLux** | Starlight lighting engine + parallel light updates |
| **Lithium** | General game-logic optimizations |
| **Noisium** | Worldgen noise / biome / direct palette placement |
| **VMP** | Area-based player watching / chunk sending |
| **FlowSched** | The async scheduler C2ME/ScalableLux build on |
| **`dev.superchunk.gpu`** | Original OpenCL worldgen offload (below) |

## Architecture (GPU path)

Worker threads submit chunks at the noise-status seam and are freed (async
`Completable`); a single drainer thread buckets requests and dispatches
**multi-chunk fused kernels** (all ~104 density functions compile to OpenCL via a
custom AST emitter): corner-grid fill (fp64) → decide kernel (fp32) → readbacks
into persistently-mapped pinned staging, on a depth-2 slot ring of per-slot
command queues; a completer thread slices results per chunk and completes
futures. Key files: `src/main/java/dev/superchunk/gpu/dfc/GpuChunkBatcher.java`,
`GpuBatchDispatcher.java`, `GpuBatchPrefetch.java`, `CompactIds.java`,
`OpenCLAstEmitter.java`.

Program binaries are disk-cached (cold JIT of ~104 kernels is one-time per
driver/precision change; warm boot ~11–13 s).

### Parity philosophy

- **The corner/interpolation chain is bit-exact to vanilla** (fp64 GPU math;
  every density function parity-gated at registration). All CPU-side worldgen
  optimizations are proven bit-exact by lockstep verify harnesses (100M–455M
  sample runs, 0 mismatches) or byte-verbatim construction.
- **The compact-ids decide chain** (`superchunk.gpu.compactIds`, default `on`)
  lets the GPU decide block identity; its only theoretical deviation class is
  tie-block flips, and the fp32 decide kernel was gated on a flip census —
  **zero flips over 455.9M blocks** — before shipping default-on. Strict mode is
  one flag away: `-Dsuperchunk.gpu.compactIds=off`.
- Region-file hashes are NOT run-to-run deterministic (vanilla feature-overlap
  ordering), so parity work uses in-JVM lockstep verify flags
  (`-Dsuperchunk.*.verify=true`), not file hashes.

## The measured ceiling (why ~1,120 and not more)

- The decide kernel's floor (~0.50 ms/chunk) is **vanilla's own irreducible
  per-block 3D-noise math** — permutation-table gathers, latency-bound. FMA
  contraction: neutral. 8× corner-load reduction (z-runs): neutral. Skipping the
  aquifer machinery for 64% of blocks: only −7.5%.
- The corner kernel (~0.3 ms/chunk) is fp64 by the bit-exactness contract.
- The biome offload stays off by default: ~0.9 ms/chunk device-serial cost
  against a ~3–6% CPU saving, and fp32 climate is measured-refuted (domain-warped
  noises amplify drift to ~0.47 biome-quantization units).
- At 1,119.5 cps both sides are near their walls (GPU 90–93% util; CPU ≈ 87%+).
  Pushing past ~1,150 on this hardware means relaxing vanilla parity or a
  stronger GPU — the architecture scales with GPU horsepower.

## Building

```bash
./gradlew build          # system OpenJDK (toolchain auto-provisions Java 21)
# shippable jar: build/libs/superchunk-<version>.jar   (the shell — install this one)
# internal:      build/libs/superchunk-<version>-core.jar (rides inside the shell)
./gradlew deployBundle   # copies the shippable jar to build/deploy/
```

## Docs

- `GPU-AHEAD-PLAN.md` — GPU decide-chain design: parity gates, mod-compat strategy.
- `MERGE_NOTES.md` — how the five upstream mods were merged/relocated.
- `analysis/` — per-engine merge analyses.
- `dist/README.md` — end-user install notes.

## License

LGPL-3.0-only for the combined work (see `LICENSE`); incorporated MIT components
retain their notices in `NOTICE`. Upstream credits: C2ME & FlowSched & VMP
(ishland), Lithium (CaffeineMC), ScalableLux (RelativityMC), Noisium
(Steveplays28) — see `NOTICE` for the full attributions.
