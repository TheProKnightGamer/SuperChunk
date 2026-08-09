# SuperChunk 0.2.0 — standalone merged worldgen/chunk super-mod (NeoForge 1.21.1)

**One mod, one source tree** — the optimizations of five mods merged into a single
NeoForge 1.21.1 jar (Mojmap, ~200 mixins, ~990 source files):

| Engine | What it brings |
|--------|----------------|
| **C2ME** | Multithreaded chunk **generation / IO / loading** (runs gen on `c2me-worker` threads) |
| **ScalableLux** | Starlight lighting engine + parallel light updates |
| **Lithium** | General game-logic optimizations (full) |
| **NoisiumForked** | Worldgen noise / biome / blockstate placement |
| **VMP** | Area-based player-watching / chunk-sending ("very many players") |
| **FlowSched** | The async scheduler C2ME/ScalableLux build on |

Verified: boots a dedicated server clean, generates the world on **C2ME's `c2me-worker`
threads** (`Global Executor Parallelism: 7`), zero runtime errors. Built from source — no
nested third-party mod jars; everything is one mod id (`superchunk`).

## Install — ONE jar, client and server alike
1. NeoForge **1.21.1** (neoforge `21.1.x`, Java **21**), client or dedicated server.
2. Copy **`superchunk-0.3.0.jar`** into `mods/`. That single jar is the complete build for
   BOTH sides: it is a thin bootstrap shell that loads the real mod from inside itself and,
   *only on a dedicated server*, also supplies the LWJGL core module (`org.lwjgl`) the
   bundled GPU/OpenCL backend needs (a headless server ships no LWJGL of its own; a client
   already has one, and is left untouched). On first boot it extracts its inner jars to
   `.superchunk-bootstrap/` in the game folder (content-hashed; managed automatically).
3. **Upgrading from an older SuperChunk:** delete the old
   `superchunk-*-lwjgl-server-locator.jar` companion if present — it is no longer needed
   (redundant but harmless if left behind).
4. **Copy `config/lithium.properties` into the server's `config/` folder BEFORE first start.**
   This disables the Lithium features that overlap C2ME's chunk-system rewrite
   (`gen.cached_generator_settings`, `chunk.serialization`,
   `world.tick_scheduler`, `world.chunk_access`) so C2ME owns those paths. (SuperChunk also
   rewrites this file from `config/superchunk.properties` on every boot, so after the first
   start it maintains itself.)
5. (Recommended) Aikar's JVM flags.
6. Tune `config/c2me.toml` (auto-generated on first boot) for thread counts; C2ME auto-sizes
   to your CPU by default. ScalableLux parallel lighting: `config/scalablelux.properties`.

`./gradlew deployBundle` collects the shippable jar into `build/deploy/`.

## Standalone Lithium is now SUPPORTED alongside SuperChunk
If a standalone **Lithium** (any version, e.g. `lithium-neoforge-0.15.x`) is installed in the
same instance, SuperChunk's bundled Lithium **stands down automatically** (one log line:
*"standalone Lithium detected -> SuperChunk's bundled Lithium disabled"*) and the installed
Lithium is authoritative — no more version conflicts with newer Lithium releases.
SuperChunk's GPU/direct-write worldgen paths bind to the *installed* Lithium's block-counting
internals structurally at runtime (no compile-time linkage), so its per-section counters stay
exact; verified ZERO-divergence over 100M+ blocks against Lithium 0.15.4. If a future Lithium
rewrites those internals beyond recognition, SuperChunk detects that and automatically falls
back to the vanilla write path for safety (log: `[SuperChunk-LithiumBridge]`).
The C2ME-overlap pins in `config/lithium.properties` (step 4) apply to the standalone Lithium
too — keep that file in place.

## Notes
- **Do NOT also install standalone C2ME / ScalableLux / Noisium / VMP** — they're already
  inside this jar; running duplicates would conflict. (Standalone **Lithium** is the
  exception — see above.)
- Source project: this repository (build with `gradlew build`, launch JVM = JDK 17;
  compile toolchain auto-provisions Java 21).
- **GPU / OpenCL IS included.** The LWJGL OpenCL bindings (`org.lwjgl.opencl`) are embedded in the
  mod, so SuperChunk's GPU compute backend ships on both client and dedicated server. (The
  OpenCL ICD itself comes from your system GPU driver.)
    - On a **client**, the game already ships the LWJGL core module (`org.lwjgl`), which the backend
      uses — the bootstrap contributes nothing there.
    - On a **dedicated server** (which ships no LWJGL), the same jar's bootstrap supplies the LWJGL
      core module so the OpenCL bindings resolve.
  This is distinct from C2ME's `natives-math` *CPU-SIMD* path, which remains **inert** on this
  Java 21 build (it needs Java 22 `java.lang.foreign` + prebuilt native libs). GPU worldgen is
  experimental; see `../MERGE_NOTES.md`.
- Not exhaustively gameplay-tested — boot + multithreaded spawn-gen are verified; a longer play
  session (chunk save/reload, players connecting) is wise before production.
