# SuperChunk — merge notes / architecture / status

**Goal:** literal from-source merge of C2ME + ScalableLux + NoisiumForked + Lithium
into ONE NeoForge 1.21.1 mod, single Mojmap source tree, hotspot conflicts
hand-fused. (User chose the "pure literal blend" path with eyes open: multi-week,
fragile, frozen at today's upstream — no upstream bug-fix pulls.)

## Forward constraints (user, 2026-06-22)
- **GPU/OpenCL acceleration is the endgame.** The merge must keep the noise /
  density-function generation pipeline modular and cleanly seamed so C2ME's
  OpenCL noise path (or a custom one) can slot in later. When merging C2ME's
  `*NoiseSampler` / `DensityFunctionTypes$*` overwrites, preserve a clear
  boundary between "where noise is computed" and "how chunks consume it".
- **Boot-test via the dedicated SERVER run config** (`gradlew :runServer` /
  the `server` run), not the client. These are server-side worldgen mods anyway.
- Target: a fully functional merged mod.

## Build facts
- ModDevGradle 2.0.119, NeoForge **21.1.215**, MC **1.21.1**, Java **21**.
- Mappings: **Mojang official + Parchment 2024.11.17**. NeoForge runs Mojmap at
  runtime, so **mixins need NO refmap** (big simplification).
- Launch Gradle with JDK 17 (`C:\Program Files\Java\jdk-17.0.17.10-hotspot`);
  JAVA_HOME=jdk-25 breaks Gradle 9.x. Compile toolchain (21) auto-provisioned.
- config-cache OFF; gradle jvm -Xmx4G.

## Upstream mapping reality (the core merge cost)
| Mod | MC/Loader (src) | Mappings | Mixins | Notes |
|-----|-----------------|----------|--------|-------|
| C2ME | NeoForge 21.1.209 | **Yarn** (loom-remapped) | **~210**, 22 modules | MIT. Patch-based port over C2ME-fabric submodule. Needs Yarn→Mojmap translation. |
| Noisium | NeoForge 21.1.22 | **Yarn** | **5** | LGPL-3.0. Architectury. Needs Yarn→Mojmap. |
| ScalableLux | NeoForge 21.1.92 | **Mojmap** | 14 | LGPL-3.0. Port applied via build patch; mixins unchanged. Drops in mapping-clean. |
| Lithium | NeoForge 21.1.125 | **Mojmap** | many (feature-gated) | LGPL-3.0. v0.15.3. NO lighting mixins. Build-time `@MixinConfigOption` codegen + `LithiumMixinPlugin` gate every feature. Overlaps C2ME (`NoiseBasedChunkGenerator`,`ServerChunkCache`,`ChunkHolder`,`LevelChunk`) + Noisium (`PalettedContainer`,`LevelChunkSection`). |

Yarn→Mojmap concept map (recurring): `ServerChunkLoadingManager`=`ChunkMap`,
`ServerChunkManager`=`ServerChunkCache`, `ChunkNoiseSampler`=`NoiseChunk`,
`NoiseChunkGenerator`=`NoiseBasedChunkGenerator`, `ChainedBlockSource`=`MaterialRuleList`,
`WorldChunk`=`LevelChunk`, `Chunk`=`ChunkAccess`, `ChunkSection`=`LevelChunkSection`,
`World`=`Level`, `ServerWorld`=`ServerLevel`, `Identifier`=`ResourceLocation`.

## Integration order (risk-ascending) + status
1. **FlowSched** (pure Java, RxJava3 dep) — ✅ DONE, compiles. `com.ishland.flowsched.*`.
2. **ScalableLux** (Mojmap lighting; Starlight engine + 14 mixins) — ✅ DONE.
   Applied via the upstream NeoForge port patches; entrypoint folded into `SuperChunk`;
   AW→`accesstransformer.cfg`; `scalablelux.mixins.json` registered. **Boot-verified on a
   dedicated server**: all 14 mixins apply, parallel lighting runs (4 threads), world gens, no errors.
3. **Lithium** (Mojmap, 234 mixins) — ✅ DONE + boot-verified (ScalableLux+Lithium together:
   208+ mixins apply, world gens, 0 errors). Folded `LithiumNeoForgeMod`→init; copied SPI
   services + AT + generated config props; one API fix (`Fluid.isEmpty` protected → adapt caller).
4. **Noisium** (Yarn, 5 mixins) — DEFERRED to the Yarn phase (see wall section).
5. **C2ME** (Yarn, ~210 mixins) — ✅ DONE (embedded as its own jar; see "C2ME plan"). Built from
   prebuilt `c2me-neoforge` jar; runs alongside SuperChunk.
6. ✅ **ALL FOUR BOOT-VERIFIED TOGETHER** (2026-06-22): superchunk (ScalableLux+Lithium+FlowSched)
   + c2me + ~20 c2me submodules load; world generates on `c2me-worker` threads; ~197 Lithium +
   ~194 C2ME + 11 ScalableLux mixins apply; 0 errors. Fix that unblocked it: **relocated superchunk's
   FlowSched** `com.ishland.flowsched` → `dev.superchunk.deps.flowsched` (dropped the rxjava-using
   `scheduler` subpackage; ScalableLux only needs `executor`) to resolve a JPMS "package exported by
   two modules" clash with C2ME's bundled FlowSched. Lithium overlaps disabled via `config/lithium.properties`.
   Deployable build assembled in `dist/` (2 jars + config + README).
7. ✅ **Noisium** (2026-06-22) — added as its own jar (Yarn, like C2ME); 4 worldgen mixins apply with
   no C2ME conflict; boot-verified in the 5-component stack. Marginal gain (C2ME also does worldgen).
8. ✅ **VMP player-watching** (2026-06-22) — VMP has no NeoForge build, so its ONE additive chunk opt
   (area-based player-watching/chunk-send) was ported Yarn→Mojmap INTO superchunk source (`com.ishland.vmp.*`).
   Skipped its other chunk opts (ticket-propagator/async-login/POI/ticking = redundant/conflict with C2ME).
   Boot-verified: VMP's ChunkMap overwrites (`getPlayers`,`applyChunkTrackingView`) coexist with C2ME's ~12
   ChunkMap mixins (different methods). 6-component stack boots, 0 errors.

Remaining polish: single-file packaging (embed c2me+noisium jars into superchunk.jar — needs real-server
smoke test), one unified config screen, extended gameplay correctness testing.

Notes from the ScalableLux boot: harmless DEBUG "compat level JAVA_21 > mixin max JAVA_13"
(cosmetic; can lower mixin configs to JAVA_17 later). FlowSched from C2ME's pinned commit is
API-compatible with ScalableLux's thread glue — no drift. Dev runtime gets RxJava via
`implementation` (jarJar only needed for the shippable jar).

## Conflict hotspots (must hand-fuse — two mods replace the SAME method)
- **`ChunkSerializer`** (`world.level.chunk.storage.ChunkSerializer`): C2ME REDIRECT (async IO)
  vs ScalableLux INJECT (persist light data). → merge: keep C2ME's IO path, re-apply
  ScalableLux's light read/write inside it.
- **Noise/surface pipeline**: C2ME `@Overwrite`s the noise samplers (`*NoiseSampler`,
  `DensityFunctionTypes$*`) + `MaterialRules$*`; Noisium `@Overwrite`s `populateNoise`
  + `MaterialRuleList`. → reconcile; prefer C2ME's sampler rewrites, fold in Noisium's
  populateNoise palette-bypass where non-overlapping. (Noisium already ships a
  Lithium-aware variant — reuse that logic.)
- **Chunk system core**: C2ME `@Overwrite`s `ChunkMap`/`ServerChunkCache`/`ChunkHolder`/
  `ChunkTicketManager` from 4 of its own modules — internally consistent, but ScalableLux
  accessors on `ServerLevel`/`ChunkAccess`/`LevelChunk`/`ProtoChunk` + Lithium chunk
  mixins must layer on without re-overwriting.
- **Lighting**: ScalableLux `@Overwrite`s `LevelLightEngine` (14) + `ThreadedLevelLightEngine` (9);
  C2ME only INJECTs `ServerLightingProvider` and already has Starlight-aware accessors
  (`ca.spottedleaf.starlight.*`) — designed to coexist with Starlight, so low real conflict.
- **`LevelChunkSection` / `PalettedContainer`**: Noisium AW(palette internals) + ScalableLux
  AT(states) + C2ME accessor — additive ATs, reconcile into one accesstransformer.cfg.

## Conflict-resolution lever: Lithium's config gating
Every Lithium mixin is gated by `LithiumMixinPlugin` against per-package options
(option name = sub-package path, e.g. `mixin.chunk.serialization`), with defaults
generated at build time from `@MixinConfigOption` in each `package-info.java`.
=> **Where Lithium collides with C2ME, just default those Lithium options OFF**
(via the generated config defaults / `PlatformMixinOverrides` / `config/lithium.properties`)
and let C2ME's deeper rewrite win — no need to delete Lithium code. Disable at least:
Lithium's `NoiseBasedChunkGenerator` overwrite (C2ME+Noisium own worldgen), and its
`ServerChunkCache`/`ChunkHolder`/`LevelChunk` chunk-access fast paths (C2ME rewrites the
chunk system). Re-evaluate `PalettedContainer`/`LevelChunkSection` vs Noisium case-by-case.
Triple-overlap to settle: **`NoiseBasedChunkGenerator`** = C2ME REDIRECT + Noisium OVERWRITE
+ Lithium OVERWRITE.

## Yarn→Mojmap translation wall (found while integrating Noisium, 2026-06-22)
C2ME and Noisium ship **Yarn-named source** that loom remaps at build. Two concrete
problems for a hand-merge into the mojmap tree:
1. Mixins `@Overwrite` **intermediary/lambda names** — e.g. Noisium `@Overwrite method_38332`
   = the lambda inside `NoiseBasedChunkGenerator.fillFromNoise`. No stable hand-writable mojmap name.
2. Structural mojmap differences: `MaterialRuleList.materialRules` is a `BlockStateFiller[]`
   array (not Yarn's `List`); `PalettedContainer.Data` uses record accessors `palette()/storage()`;
   `LevelChunkSection` count fields rename (Yarn `nonEmptyFluidCount`→mojmap `tickingFluidCount`,
   `randomTickableBlockCount`→`tickingBlockCount`).
=> Feasible per-mixin but slow + silent-corruption-risky at C2ME's ~210-mixin scale.

**Plan:** do the mojmap mods as true merged source first (ScalableLux ✅, Lithium next).
Then a dedicated Yarn phase for Noisium + C2ME together: careful per-mixin mojmap rewrites
(dropping lambda-target micro-opts), or — if too risky — a separate **yarn-mapped source set**
in the same Gradle project, merged into the single superchunk jar. Decide at C2ME (also the GPU target).

Noisium translation crib (when we do it): drop `method_38332` lock micro-opt; keep palette-bypass
`@Redirect` (retarget `populateNoise`→`doFill`, `ChunkSection.setBlockState`→`LevelChunkSection.setBlockState`);
`GenerationShapeConfig`→`NoiseSettings` cache (`horizontalCellBlockCount`/`verticalCellBlockCount`→`getCellWidth`/`getCellHeight`);
`ChunkSection.populateBiomes`→`LevelChunkSection.fillBiomesFromNoise`; `ChainedBlockSource.sample`→`MaterialRuleList.sample`.

## C2ME plan (final piece) — the one literal-blend exception
C2ME = ~210 Yarn mixins, many targeting intermediary/lambda names (see wall section). Hand-translating
to a mojmap source set is impractical + silent-corruption-risky, and C2ME is the **GPU target** (must stay
editable). Decision: build C2ME from its own source (its loom/yarn toolchain — `upstream/C2ME-neoforge`
already does this) and **embed the remapped jar into the single superchunk jar via jarJar** → ships as ONE
mod/one distribution, C2ME source stays editable for GPU, and we skip the 210-mixin translation. This is the
one place the "single mojmap source tree" ideal bends, because the alternative isn't realistically functional.
- **Dedup caution:** superchunk already contains FlowSched (`com.ishland.flowsched`) + RxJava; C2ME's jar
  shadow-bundles both → exclude one copy to avoid duplicate-class load errors.
- **Conflict caution:** when C2ME loads, disable Lithium's overlapping features via `config/lithium.properties`:
  `mixin.gen.cached_generator_settings`, `mixin.chunk.serialization`, `mixin.chunk.no_locking`,
  `mixin.world.tick_scheduler`, + any ServerChunkCache/ChunkHolder overlaps — let C2ME's chunk system win.
  ScalableLux lighting + C2ME already coexist upstream (C2ME ships Starlight-aware code).

## TODO carried forward
- jarJar-embed RxJava3 + reactive-streams (+ any other non-MC libs) for the production jar.
- Merge all upstream AccessWideners → one `META-INF/accesstransformer.cfg`.
- Per-mod mixin configs (one json per domain) wired into neoforge.mods.toml.
- Lithium's config-driven mixin-plugin: port so options toggle mixins.

## Per-mod deep analysis
See `analysis/c2me.md`, `analysis/scalablelux.md`, `analysis/noisium.md`
(+ `analysis/lithium.md` when done).

## Restored dropped upstream mixin (2026-07-02)
`MixinSplineImplementation` (c2me-opts-dfc) was in upstream's mixin json + our
`analysis/c2me.md` inventory but was silently absent from the port (every OTHER dfc mixin
was ported; no drop rationale recorded anywhere — an accidental omission of the
useLegacyScheduling class). Restored as a faithful Mojmap translation
(`CubicSpline$Multipoint`: Yarn `findRangeForLocation`→`findIntervalStart`,
`sampleOutsideRange`→`linearExtend`, `locationFunction`→`coordinate`; float math verified
op-for-op against neoforge-21.1.215 sources — bit-identical). Perf half matters wherever
UNCOMPILED density functions evaluate splines: blendingFallback (old-chunk upgrades),
interpreter/parity paths, third-party router consumers. Value-equals half is redundant with
SplineAstNode.deepEquals but kept for upstream parity.
