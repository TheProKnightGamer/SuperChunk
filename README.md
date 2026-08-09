# SuperChunk

**Extremely fast chunk generation for Minecraft.** A server-side worldgen
performance mod for NeoForge 1.21.1. It merges C2ME, ScalableLux, Lithium,
Noisium, and VMP into a single jar, adds ~20 original worldgen optimizations,
and offloads terrain generation to the GPU via OpenCL.

## 11× vanilla

Chunky pregen, radius 2048 (66,049 chunks), fresh worlds, RTX 3070 + i7-14700K, quiet box.
Four configurations, each at its own best settings, run **interleaved** over two rounds
(2026-08-08):

| Configuration | chunks/sec | Wall time | vs vanilla |
|---|---|---|---|
| Vanilla NeoForge 1.21.1 | 114.9 | 9 m 35 s | 1.0× |
| The same upstream mods, separately | 626.0 | 1 m 45 s | 5.4× |
| SuperChunk, CPU only | 836.1 | 1 m 19 s | 7.3× |
| **SuperChunk + GPU offload** | **1,258.2** | **52 s** | **11.0×** |

Reproducibility across the two rounds: vanilla 114.1/115.7, upstream 623.1/629.0, CPU-only
836.1/836.1, GPU 1246.2/1270.2. Measuring the steady state instead of the whole wall (excluding
Chunky's ramp-up and tail) gives 120 / 644 / 852 / **1,341** cps.

Every configuration got the same 16 GB heap, the same Generational ZGC, 22 C2ME workers where
applicable, and the same raised Chunky in-flight chunk limit — so this compares the mods, not who
remembered the flags. The upstream row is **C2ME 0.4.0-alpha.0.115 + Lithium 0.15.4 + ScalableLux
0.3.0-alpha.0.6 + Noisium 2.3.0**, each with its own default config; VMP is not in it because it
has no NeoForge build, and its SuperChunk port is player-watching work that does nothing in a
pregen with no players.

So: merging the upstream mods and tuning them is worth **+34%** over running them separately, and
the GPU offload is worth a further **+50%** on top of that.

No faults, crashes, or OOMs in any run. **The generated terrain is identical to
vanilla's** — verified bit-exact over hundreds of millions of blocks.

Round 10 (2026-08-07) adds **+12.6%** on top of that, measured head-to-head at radius 3072
(148,225 chunks) with each build at its own best settings — 1,090 → 1,228 chunks/sec steady-state,
two interleaved pairs, the new build winning both. It comes from four changes that are individually
provable rather than tuned:

- fewer fp64 operations in the OpenCL noise chain, by exact IEEE identities (bit-identical output,
  verified by hash in fp32 *and* fp64, and by the boot parity gate on every build);
- raising Chunky's in-flight chunk limit, which is worth nothing on its own and only pays *because*
  the kernel got faster (see the note under Install);
- a memo of the per-block biome lookup that was 10.5% of all worldgen CPU, verified against the
  code it replaces over 359 million lookups with zero mismatches;
- the same `wrap` identity applied to the CPU noise path.

## Install

1. Install NeoForge **1.21.1** (Java 21), client or dedicated server.
2. Drop **`superchunk-<version>.jar`** into `mods/`. That one jar works on both
   sides — it bundles the OpenCL bindings it needs and leaves a client's own
   LWJGL alone. The OpenCL driver comes from your GPU vendor's normal driver.
3. GPU offload turns on automatically when a capable device is present, and
   falls back to the CPU path cleanly when it isn't. One switch:
   `gpu.enabled=true|false` in `config/superchunk.properties`.
4. Optional tuning — worker count and heap are the only machine-specific knobs
   (C2ME auto-scales by default):

   ```
   -Dc2me.base.config.override.globalExecutorParallelism=22
   -Xmx16G
   ```

   **Pregenerating with Chunky?** You no longer need `-Dchunky.maxWorkingCount`.
   Chunky caps in-flight chunks with a semaphore whose size defaults to **50** —
   sized for vanilla's synchronous chunk pipeline, so it hard-caps throughput at
   `50 / per-chunk latency` no matter how fast generation is. SuperChunk now sets
   it from `pregen.chunkyWorkingCount` (default `auto` = 192 per GB of heap,
   clamped 256–3072). An explicit `-D` still wins; `default` opts out. It is
   worth nothing on its own and everything in combination: raising it alone is
   −0.5%, the round-10 kernel work alone is −1.4% (a faster kernel drains the GPU
   batcher and shrinks its batches), and together they are **+6.3%**.

**Do not also install standalone C2ME, ScalableLux, Noisium, or VMP** — they're
inside this jar. Standalone **Lithium is fine**: if one is present, SuperChunk's
bundled copy stands down and the installed one takes over.

## What's inside

- **C2ME** — multithreaded chunk generation, IO, and loading
- **ScalableLux** — Starlight lighting engine, parallel light updates
- **Lithium** — general game-logic optimizations
- **Noisium** — worldgen noise, biomes, direct palette placement
- **VMP** — area-based player watching and chunk sending
- **FlowSched** — the async scheduler C2ME and ScalableLux build on
- **`dev.superchunk.gpu`** — original OpenCL worldgen offload

## How the GPU path works

Worker threads hand chunks off at the noise-status seam and are immediately
freed. A drainer thread batches those requests and dispatches fused multi-chunk
kernels — all ~104 density functions are compiled to OpenCL by a custom AST
emitter — then a completer thread slices the results per chunk. Compiled program
binaries are cached to disk, so the one-time JIT only recurs when your driver
changes (warm boot is ~11–13 s).

Main files live in `src/main/java/dev/superchunk/gpu/dfc/`.

## Parity

The corner and interpolation math runs in fp64 and is bit-exact to vanilla, with
every density function gated at registration. The compact-ids decide chain (on
by default) lets the GPU pick block identity; it was shipped only after a flip
census showed zero divergence across 455.9M blocks. Strict mode is one flag
away: `-Dsuperchunk.gpu.compactIds=off`.

## Building

```bash
./gradlew build          # shippable jar: build/libs/superchunk-<version>.jar
./gradlew deployBundle   # copies it to build/deploy/
```

Install the plain jar, not the `-core` one — that rides inside it.

## Docs

- `GPU-AHEAD-PLAN.md` — GPU decide-chain design and parity gates
- `MERGE_NOTES.md` — how the five upstream mods were merged
- `analysis/` — per-engine merge analyses
- `dist/README.md` — end-user install notes

## License

LGPL-3.0-only for the combined work (see `LICENSE`); MIT components retain their
notices in `NOTICE`. Credits: C2ME, FlowSched, and VMP (ishland), Lithium
(CaffeineMC), ScalableLux (RelativityMC), Noisium (Steveplays28).
