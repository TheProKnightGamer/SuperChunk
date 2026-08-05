# SuperChunk

**Extremely fast chunk generation for Minecraft.** A server-side worldgen
performance mod for NeoForge 1.21.1. It merges C2ME, ScalableLux, Lithium,
Noisium, and VMP into a single jar, adds ~20 original worldgen optimizations,
and offloads terrain generation to the GPU via OpenCL.

## 10× vanilla

Chunky pregen, radius 2048 (66,049 chunks), fresh worlds, RTX 3070 + i7-14700K:

| Configuration | chunks/sec | Wall time | vs vanilla |
|---|---|---|---|
| Vanilla NeoForge 1.21.1 | 111.9 | 9 m 50 s | 1.0× |
| Stock mods together, unedited | 660.5 | 1 m 40 s | 5.9× |
| SuperChunk, CPU only | 815.4 | 1 m 21 s | 7.3× |
| **SuperChunk + GPU offload** | **1,119.5** | **59 s** | **10.0×** |

No faults, crashes, or OOMs in any run. **The generated terrain is identical to
vanilla's** — verified bit-exact over hundreds of millions of blocks.

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
