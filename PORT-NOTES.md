# NeoForge 1.21.1 → Forge 1.20.1 port notes

## FLUID FILTER SAVE RACE (2026-10-24) — same as neo

C2ME's `ServerBlockTicking.upgradeToThis` ran `filterFluidTicks` on the worldgen thread, and it
removed marks from the post-processing lists of a chunk that is already full. The server thread's
autosave can serialize those lists at the same moment, and `ChunkSerializer.packOffsets` threw
`NoSuchElementException` when a list shrank under it ("Failed to save chunk", with a chunk IO crash
report). It was seen once in a neo GPU-off pregen; upstream C2ME has the same code.

The scan now only reads, and records per section which marks to drop (`FluidMarkFilter`). The
server thread removes them in `dropFluidMarks`, at the start of the main-thread runnable, before
`postProcessGeneration`. The lists come out the same, in the same order: the compaction was
checked against the old `iterator.remove()` on 200,000 random lists. A list whose length changed
in between is left whole, which is vanilla's behavior. The file is the same in both ports apart
from the region constructor, which was already different.

## SURFACE BUILDING (2026-10-24) — same as neo

These are copied verbatim from neo:
- `DeepSurfaceRules`, `SurfaceColumn`, `SurfaceTiming` and `BiomeQuartCache`;
- `MixinSurfaceSystemFast`, `MixinSurfaceDeepRules`, `MixinSurfaceContextDeep`,
  `MixinSurfaceBiomeConditionMemo`, `MixinSurfaceBuildTiming`, `MixinSurfaceColumnMemo` and
  `MixinNoiseFillHash`;
- the `ISurface*Access` accessors and `ISurfaceRulesContextAccess`.

`ProtoChunkWrites` gains neo's `setInColumn`. 1.20.1's `buildSurface`, the surface rule classes and
`ProtoChunk.getBlockState` match 1.21.1's. Write-up in neo's `analysis/surface-2026-10-24.md`.

- **One difference: the uniform biome cells.** 1.21.1 reads a cell's 8 corners only within the
  radius where the region's `ChunkStep` guarantees biomes, since its `WorldGenRegion.getChunk` throws
  beyond it. 1.20.1 has no `ChunkStep`, and its `getChunk(x, z, BIOMES, false)` returns null there,
  so `getNoiseBiome` falls back to the uncached biome and never throws. `MixinBiomeManagerQuartCache`
  sets the radius to `Integer.MAX_VALUE`, and `IWorldGenRegionAccess` keeps no `generatingStep`
  accessor.
- `SequenceRuleSource` is package-private in 1.20.1 (public in 1.21.1), so both ports read it
  through `ISurfaceSequenceSourceAccess`.
- **SRG names** added to `MixinTargetScan.SRG_NAMES`:
  - `ProtoChunk#getBlockState` `m_8055_`, `ChunkAccess#getSection` `m_183278_`,
    `LevelChunkSection#hasOnlyAir` `m_188008_`;
  - `SurfaceRules$Context#updateY` `m_189576_`, `#getMinSurfaceLevel` `m_189583_`;
  - `SurfaceSystem#buildSurface` `m_224648_`;
  - `Holder$Reference#is` `m_203425_` (the `Predicate` overload).

  The refmap carries every accessor, invoker and redirect. The copy's shadows reobfuscate to
  `f_189904_`, `m_189952_`, `m_189954_` and `m_189934_`.
- **Gate:** `-Dsuperchunk.debug.surfaceHash`, r512 overworld plus r192 nether and end, everything off
  vs on: 6,551 chunks, 0 mismatches. Surface 4,383 -> 3,330 us per chunk.

## ORE PLACEMENT (2026-10-24) — same as neo

`MixinOreFeatureFast`, `MixinHeightmapTop`, `MixinProbeBiomeLookup`, `OreRuns`, `OreTiming` and
`OreRunsTest` are copied verbatim from neo, except for one line in `OreRuns`. 1.20.1's `OreFeature`
(`place`, `doPlace`, `canPlaceOre`, `shouldSkipAirCheck`) and the chunk and heightmap methods used
are the same as 1.21.1's. Write-up in neo's `analysis/ores-2026-10-24.md`.

- `place`'s column scan looks each chunk up once and reads `ProtoChunk` heightmaps directly. It skips
  a chunk whose highest column, cached on the heightmap against a copy of its raw words, is below the
  box. `doPlace` finds each row's run of blocks instead of testing every block, with the taken bits
  in a `long[]`. Plain block and tag targets keep their answer per block state. Kill switches
  `-Dsuperchunk.worldgen.oreHeightScan=false`, `.oreRuns=false`, `.oreTargetMemo=false`.
- **SRG names.** `MixinTargetScan.SRG_NAMES` gains the methods these replicas ask about:
  - `OreFeature#place` `m_142674_`, `#canPlaceOre` `m_225186_`;
  - `WorldGenRegion#getHeight` `m_6924_`;
  - the four rule tests' `test` `m_213865_`;
  - `ChunkAccess#getHeight` `m_5885_`, `#hasPrimedHeightmap` `m_187658_`,
    `#getOrCreateHeightmapUnprimed` `m_6005_`; `ProtoChunk#getHeight` `m_5885_`;
  - `Heightmap#getFirstAvailable` `m_64242_`, `#getRawData` `m_64239_`.

  The map holds one name per method, so `OreRuns` also asks for the private
  `getFirstAvailable(int)` by its SRG name `m_64240_` directly. That is the one line that differs
  from neo.
- `-Dsuperchunk.worldgen.oreVerify=true`, r1024 GPU: 4,307,084 height checks, 1,572,635 placements,
  122,233,778 blocks, 0 mismatches. The heightmaps were read directly and every block went through
  the target memo. Three concurrent pairs, shortcuts off vs on: ore CPU 5.87 -> 4.17 us per call
  (-29%).

## LIGHTING NEW CHUNKS (2026-10-23) — same as neo

The light engine's changes (`StarLightEngine`, `SkyStarLightEngine`, `BlockStarLightEngine`,
`StarLightInterface`) are neo's diff applied as is; the files differ from neo only in 1.20.1's
`ChunkStatus` package and `getStatus()`. `LightVerify` takes the same two substitutions, and
`LightTiming` is copied verbatim; both report from `SuperChunk`'s server-stopping hook. Write-up in
neo's `analysis/lighting-2026-10-23.md`.

- Sky column entries that cannot raise anything are dropped before propagation; block light sources
  are found from the palette and raw storage; edge checks skip block reads the palette answers.
  Kill switches `-Dsuperchunk.lighting.skyColumns=false`, `-Dsuperchunk.lighting.paletteSkips=false`.
- The emitter test is the existing one: forge's `BlockStateBaseMixin` marks a state dynamic when its
  `IForgeBlockState`/`IForgeBlock` `getLightEmission(level, pos)` is overridden, and otherwise
  that call returns `state.getLightEmission()`, as on neo.
- `-Dsuperchunk.lighting.verify=true`, r1024 GPU: 17,170 chunks, 16,088 compared, 0 mismatches.
  In-run A/B (`-Dsuperchunk.debug.lightTimingAB=3`): light 902 -> 707 us/chunk (-21.6%; sky -21%,
  block -22.5%).

## ONE WARP PER WORK-GROUP + LOCK-FREE AQUIFER CELL CACHE (2026-10-22) — same as neo

`CLProgram`, `ScAquiferCellCache` and `tools/decidebench.c` are copied verbatim from neo; the cache
report line is added to `SuperChunk`'s server-stopping hook. Write-up in neo's
`analysis/gpu-small-batches-2026-10-22.md`.

- Every 1-D dispatch launches with the kernel's preferred multiple (at least 32) instead of the
  driver's 256, so a small batch spreads over all SMs (`-Dsuperchunk.gpu.localSize=0` restores the
  driver's choice). fp64 `compactIds=verify`, r512: 537,231,360 blocks, 0 mismatches.
- The cross-chunk FluidStatus cache is a lock-free 4-way table (default 524,288 entries) instead of
  64 locked LRU maps. CPU-mode noise-fill and surface hashes against the old cache, r512: 0
  mismatches over 5,490 chunks.

## BEARDIFIER ON THE GPU (2026-10-22) — same as neo

`CompactIds`, `GpuBatchDispatcher`, `GpuChunkBatcher`, `ScBeardBoxAccess`, `IStructureWeightSampler`
and `decide.cl` are copied verbatim from neo; `tools/decidebench.c` too. Full write-up in neo's
`analysis/gpu-beard-2026-10-22.md`.

- The decide kernel adds each structure chunk's beard to the density inside its beard box, so those
  blocks no longer come back as sentinels for the CPU. Terms are looked up from tables the CPU fills
  once with its own functions (`superchunk$beardTables`); BEARD_BOX terms are computed in-kernel.
  Kill switch `-Dsuperchunk.gpu.beardOnGpu=false`.
- Tree-specific: `MixinStructureWeightSampler.superchunk$beardTerms` / `superchunk$beardTables`.
  1.20.1 has no ENCAPSULATE (its table range stays zero), and its `getBuryContribution(int, int,
  int)` halves y inside, so the BURY table calls `getBuryContribution(i1, l1, j1)`; the kernel's
  lookup is the same. The `BEARD_KERNEL` accessor maps to `f_158062_` in the refmap.
- Parity (sc-forge-test, r1024, `gpu.decideFp32=false`, `compactIds=verify`): 18,393 chunks,
  1,808,105,472 blocks, 506 structure chunks summed on the GPU, 0 mismatches in every class.

## GPU CORNER KERNEL: COLUMN HOISTING + PER-CORNER CSE (2026-10-21) — same as neo

`OpenCLAstEmitter` and `GpuBatchDispatcher` are copied verbatim from neo (the files were identical
before; the new code is Java 17-clean: records and instanceof patterns only). Full write-up in neo's
`analysis/gpu-corner-hoist-2026-10-21.md`; `tools/cornerbench.c` is copied too.

- The corner kernel computes every y-independent subtree that samples noise or a spline
  (continentalness, erosion, ridges, the offset/factor/jaggedness splines; 28 slots in the
  overworld) once per (chunk, corner column) in `df_batch_columns_multichunk`, and `sloped_cheese`
  with its blended noise once per corner (`_cor`). Kill switches `-Dsuperchunk.gpu.colHoist`,
  `-Dsuperchunk.gpu.cornerCse`, `-Dsuperchunk.gpu.dedupFunctions` (all default on).
- Real router replayed with `cornerbench` (dump via `-Dsuperchunk.gpu.dumpBatchTo`): corner time
  1.056 → 0.130 ms/chunk (−87.7%, K=16, 300 batches), bit-exact.
- Parity (sc-forge-test, Forge 47.4.x template, r512, 5,490 chunk positions): compact ids off, GPU
  plain = GPU hoisted = CPU on noise-fill and surface hashes, 0 mismatches; compact ids on, plain =
  hoisted, both 2 chunks off CPU (the known fp32 decide flips). Boot self-tests with
  `gpu.selftest.gpu_parity=true`: FP64 bit-exact PASS, `[batch-emit]` 88,200 values 0 mismatches.
- Throughput, r1024, 22 workers, 16 GB, alternating legs on the same jar: plain 19.55 / 19.62 s,
  hoisted 14.96 / 14.12 s: 849 → 1,144 cps (+34.7%), process CPU −8.1%. (Neo: +13.4%.)

## PERFORMANT, ECOPT AND BYE PREGEN (2026-10-20) — find + Bye Pregen also on neo

GitHub #22 (Forge, one big pack) and #23 (NeoForge). Three of the four crashes were ours:

- Performant: a world crashed with `ClassCastException: Collections$UnmodifiableCollection cannot
  be cast to ArrayList` in `EntitySection.getEntities(EntityTypeTest, ...)` (`m_188348_`).
  Performant replaces `ClassInstanceMultiMap.find` to hand back the type's list itself and casts
  that to `ArrayList` in its `EntitySectionMixin`; the bundled Lithium's
  `collections.entity_filtering` overwrote `find` at a higher priority (1000 vs Performant's
  lower one), which silently replaced Performant's version. It is now a `@WrapMethod` that stands
  down via `ForeignHooks` (probe on `ClassInstanceMultiMap`, `find -> m_13533_` in
  `MixinTargetScan.SRG_NAMES`); with Lithium's `mixin.experimental` off by default every per-type
  list stays an ArrayList.
- Entity Collision Optimization (`ecopt` 1.20.1.1) `@Redirect`s the same `Level.getEntities` call
  in `LivingEntity.pushEntities` as `unpushable_cramming`, both required, same priority: "Redirector
  getOtherPushableEntities ... failed injection check". `ForgeMixinOverrides` turns
  `mixin.entity.collisions.unpushable_cramming` off when `ecopt` is loaded
  (`-Dsuperchunk.compat.ecopt=false` to keep it). Its 1.21.1 build wraps the call and yields, so
  neo has no override.
- Bye Pregen 1.1.3.3 (#23, NeoForge; its 1.20.1 build has the same code): its raw (GC-free) chunk
  saves go through vanilla `IOWorker.submitTask`, whose mailbox the bundled C2ME's IOWorker never
  creates; Bye Pregen turns those saves off when it finds C2ME, by the un-relocated class
  `com.ishland.c2me.base.common.registry.SerializerAccess`. Every save failed ("this.mailbox is
  null"), the chunk was lost, and each failure built a crash report (40 s ticks on Windows: OSHI's
  hardware query). `compat.MixinByePregenRawSave` answers its `GcFreeChunkSerializer.shouldUseGcFree`
  with false while `C2MEStorageVanillaInterface.inUse`, so those chunks are saved through C2ME.
- The fourth (Simply Swords Overhaul 1.0.4: `@Inject` into `StormsEdgeSwordItem.m_5929_`) is
  theirs: Simply Swords 1.70.2's `StormsEdgeSwordItem` has no `onUseTick` override.

Tested with fixture mods, since the real jars are CurseForge-only (dedicated server, r128, Forge
47.4.28; the Bye Pregen one also on NeoForge 21.1.248): one replaces `find` and casts as in the
report, one redirects `pushEntities` with mod id `ecopt`, one is mod id `byepregen` with the same
class and method and a raw save through `submitTask`. On 0.5.0 each fails the way its report did:
the cast crash on an entity tick, the redirect conflict before boot, and 3,854 failed saves
("this.f_63517_ is null"; 40 on NeoForge, "this.mailbox"). With these changes each logs its
stand-down, rule-disabled or raw-saves-off line, generates 289 chunks and reports no errors.
SuperChunk alone: the same, with no stand-down lines.

## SPAWN AND QLIPHOTH AWAKENING CRASHES (2026-10-19) — Forge only

GitHub #20 ("Crash with Spawn"): the client failed at mod construction with `Registry Object not
present: spawn:ant_mound`. 1wholibs creates the item renderer while mods are still being constructed,
which makes a shulker box block entity, and Lithium's support cache tested `BlockEntityType.isValid`
in the `BlockEntity` constructor. Spawn hooks `isValid` and reads its own registry object, which is
not filled yet. 1.21 vanilla calls `isValid` in that constructor itself, so neo is unaffected; on
1.20.1 nothing else does. The cache (`support_cache/BlockEntityMixin`) is now filled on first use and
cleared by `setBlockState`.

GitHub #19 ("Incompatible with performant"): the world failed to load with `chunk [0, 0] failed to
generate`. The cause was Qliphoth Awakening (`fdbosses`), not Performant (Performant was only first
in the crash report's suspect list). Its `BeardifierMixin` injects into `Beardifier.compute` after
the second `terrainAdjustment()` call (an ENCAPSULATE-style term for its arena pieces), and C2ME's
`MixinStructureWeightSampler` overwrote `compute` with a higher priority, so the injection failed to
apply (FATAL) when `Beardifier` first loaded. `compute` is now a `@WrapMethod` that stands down via
`ForeignHooks` (`Beardifier#compute` -> `m_207386_` added to `MixinTargetScan.SRG_NAMES`), so vanilla's
body, with the other mod's injection, runs. The beard box consumers already stand down through
`BlockFillHooks` whenever another mod's mixin is on `Beardifier`. Qliphoth Awakening 3.2 for 1.21.1
only hooks `forStructuresInChunk`, so neo does not crash; neo still overwrites `compute`.

Tested: the shipped 0.5.0 jar with Qliphoth Awakening 3.1.0.3 + FDLib on a dedicated server fails
the same way as the report; the fixed jar generates 1,089 chunks and logs the stand-down. Without
it, worlds are unchanged (noise-fill and surface hashes, 18,499 chunks, 1,207 with structure
adaptation, 0 mismatches) and CPU per chunk is unchanged (three r1024 pairs: -0.6%, -0.8%, +0.3%).
"No starting jigsaw fdbosses:malkuth_arena_part_1" also appears without SuperChunk: theirs.

## GUHAO'S VIX STARTUP CRASHES (2026-10-18) — same changes as neo

GUHAO's VIX crashed the game at startup: `Critical injection failure: Redirector postponeGetEntities
... entity.collisions.movement.EntityMixin failed injection check`. VIX bundles ModelLoader, whose
`EntityCollisionMixin` (priority max) takes the same call in `Entity.collide`, and VIX declares
`"lithium:options" = { "mixin.entity.collisions.movement" = false }` to turn Lithium's redirect off.
It puts the key inside its `[[mods]]` entry, and `ForgeMixinOverrides` read it only at the top of
the file, so the bundled rule stayed on. It now reads both (`ModInfo.getConfigElement` for the
entry); the fixed jar logs the rule disabled "added by mods [vix]".

Past that, VMP failed: `PACKAGE @Overwrite method m_140192_ in vmp.mixins.json:playerwatching.
MixinThreadedAnvilChunkStorage cannot reduce visibiliy of PUBLIC target method`. ModelLoader's
access transformer has wildcards (`public net.minecraft.server.level.ChunkMap *()`, and the same
for Entity, LivingEntity, Level, ServerLevel, ServerChunkCache, MinecraftServer, the entity
section classes and about twenty more), so every non-public overwrite on those classes trips
Mixin's visibility check. C2ME's and Lithium's configs already set
`"overwrites": {"conformVisibility": true}` (C2ME's modules inherit it from `c2me.mixins.json`),
which lets Mixin raise a private overwrite to the target's visibility (logged as "Static binding
violation") and accept a package one. vmp, noisium, scalablelux, superchunk and superchunk-dh now
set it too (neo as well). Without such an AT nothing changes: the check only acts when the target
is more visible than the overwrite.

Tested (dedicated server, r256): the shipped 0.5.0 jar fails with the collision injection error
on Forge 47.3.0 and 47.4.28; the fixed jar with VIX 1.20-1.3.6 + Epic Fight 20.14.17 on 47.4.28
boots and generates 1,089 chunks, and SuperChunk alone does too on 47.3.0 and 47.4.28. VIX 1.3.x
needs Forge 47.4 (`ResourceLocation.fromNamespaceAndPath`) and Epic Fight (it loads
`yesman.epicfight` classes without declaring the dependency); both are VIX's requirements.

Neo additionally vendored MixinSquared's `@TargetHandler` (its jar-in-jar copy clashed with VIX's,
packaged under the other Maven group). Forge does not bundle MixinSquared, so there is nothing to
port for that half.

## CELL SIGN SKIP + CORNER SHARING IN GPU MODE (2026-10-17) — same files as neo, copied

Both used to stand down whenever GPU routing was on. They now run there too; the reasoning and the
neo test are in `neo-1.21.1/analysis/cpu-per-chunk-2026-10-11.md` ("GPU mode"). In short: a cell's
fill never reaches the GPU (off the corner lattice, under the 1024-point direct-dispatch floor), so
`CellSignSkip.bind` accepts a final density with a GPU delegate when
`GpuDfcDelegate.cpuFillsOffLattice(cellBlocks)` holds, and the compact-ids hybrid fill's CPU cells
all touch the beard box, so none is decided. Files: `GpuDfcDelegate`, `CellSignSkip`,
`MixinNoiseChunkCellSign`, `MixinNoiseChunkCornerColumns` (identical to neo before and after).
Backup: `superchunk-backups/forge-1.20.1-pre-gpu-cellsign-20261017.tgz`.

**Hard test** (Forge 47.3.0, sc-forge-test, shell jar, seed -987654321, RTX 3070 shared with other
GPU jobs; every leg also generated the Nether and the End, radius 512 each, at separate
coordinates so the hash files do not collide):

| Leg | Compared | Mismatches |
|---|---:|---:|
| GPU, compact ids `verify`, r1536: cell sign (87.1% decided) | 3,408,614,656 blocks | 0 |
| same: cell sign solid answers | 785,163,264 blocks | 0 |
| same: shared corner columns, recomputed | 2,797,042 columns | 0 |
| same: air cells / surface levels / biome fiddle cache | 2.40B / 7.21M / 3.15B | 0 |
| GPU, compact ids `off`, r1024: noise-fill hash, both on vs off | 27,940 chunks (1,299 with structure adaptation) | 0 |
| same: surface hash | 27,940 chunks | 0 |
| GPU defaults (compact ids `on`), r1024: surface hash, on vs off | 27,940 chunks | 0 |
| same: noise-fill hash (only chunks that take the original fill) | 9,564 chunks | 0 |
| GPU off, r1024: cell sign verify (86.7% decided) | 1,583,631,104 blocks | 0 |
| GPU off, r512: noise-fill and surface hashes, on vs off | 15,012 chunks | 0 |

The r1536 verify leg's compact-consume verify found 11 flip chunks in 3.85B blocks (veinClass=2).
That is the fp32 decide flip rate already recorded below (QA RESULTS); the shipped blocks in that
leg never use the shortcuts (both verify modes ship the full computation). No exception or ERROR
line in any of the 14 legs. CPU per chunk, sequential single legs: GPU off 29.2 ms on vs 34.3 off;
GPU with compact ids `off` 23.4 vs 25.9; GPU defaults, three pairs, 18.2 vs 18.7 mean (spread
±10%, no consistent sign: few chunks take the CPU fill there).

`mod_authors` now matches neo's (`TheProKnightGamer (...)`), for the public `forge-1.20.1` branch.

## CPU PER CHUNK (2026-10-11) — neo commit 494dd71 (PR #17), ported

Measured and explained on neo: `neo-1.21.1/analysis/cpu-per-chunk-2026-10-11.md` (neo: 29.27 → 25.26
ms of process CPU per chunk, −13.6%, four concurrent pairs). The patch applied to this tree almost
unchanged. Backup before this round: `superchunk-backups/forge-1.20.1-pre-cpu-per-chunk-2026-10-11.tgz`.

**Ported:**
- Cell sign skip: `worldgen.CellSignSkip`, `MixinNoiseChunkCellSign`, the solid-cell answer in
  `MixinNoiseFillAirCells`, the vein-toggle capture in `MixinNoiseChunkVeinCensus`.
- Shared cell-corner columns: `CornerColumns`, `MixinNoiseChunkCornerColumns`,
  `AquiferCellSharing.cornerReason`.
- Shared preliminary surface levels: `PreliminarySurfaceLevels`, in `MixinNoiseChunkCellCache`.
- Lean surface writes: `ProtoChunkWrites`, in `MixinSurfaceColumnMemo`. Here it uses `ChunkStatus` from
  `net.minecraft.world.level.chunk` and `getStatus()`; 1.20.1's `ProtoChunk.setBlockState` is otherwise
  the same.
- `BiomeCellLookup` + `MixinSurfaceSystemCellBiome`, the NBT compound size, and
  `-Dsuperchunk.debug.surfaceHash`.
- Same switches as neo: `superchunk.worldgen.cellSignSkip`, `.cornerColumns`, `.sharedSurfaceLevels`,
  `.leanSurfaceWrites`, each with `.verify` / `.metrics` where neo has them.

**Deliberately NOT ported:**
- `MixinWorldGenRegionCenterChunk` / `RegionCenterChunk`. 1.20.1's `WorldGenRegion.getChunk` already
  indexes a plain `List<ChunkAccess>`; there is no holder chain to skip.

**Forge-only:** `MixinTargetScan.SRG_NAMES` gains `ProtoChunk#setBlockState` → `m_6978_`. Production
runs SRG names, and without the entry another mod's hook on that method would read as unhooked.

**Verified here** (Forge 47.3.0, sc-forge-test, radius 512, shell jar, refmap and SRG shadows checked in
the nested core jar):

| Check | Compared | Mismatches |
|---|---:|---:|
| Cell sign: decided cells' final densities | 480,814,336 blocks | 0 |
| Cell sign: solid-answered blocks' material rules | 115,615,744 blocks | 0 |
| Shared corner columns, recomputed | 368,721 columns | 0 |
| Shared surface levels, recomputed | 964,846 | 0 |
| Biome fiddle cache / cell path | 329,969,451 | 0 |
| Noise-fill hash, all switches on vs off | 5,490 chunks (512 with structure adaptation) | 0 |
| Surface hash, all switches on vs off | 5,490 chunks | 0 |

Cells decided: 88.4%. Corner columns shared: 32.6%.

Timing here: pre-port backup build vs this build, three concurrent pairs, radius 1024, 6 workers,
6 GiB, GPU off:

| Pair | Before | After | CPU per chunk | Steady chunks/s |
|---|---:|---:|---:|---:|
| 1 | 26.82 ms | 23.75 ms | −11.4% | +22.7% |
| 2 | 28.28 ms | 24.01 ms | −15.1% | +29.8% |
| 3 | 30.53 ms | 25.73 ms | −15.7% | +25.7% |
| mean | 28.54 ms | **24.50 ms** | **−14.1%** | |

## NVIDIA FAST COMPILE (2026-10-10) — copied from neo, byte-identical GPU files

Measured on neo (`neo-1.21.1/analysis/gpu-compile-2026-10-10.md`).

**What changed (all copied verbatim; `CLProgramCache` keeps `net.minecraftforge` FMLPaths):**
- `OpenCLDevice` gains `nvCompilerOptions` (the `cl_nv_compiler_options` extension is present).
- `OpenCLBackend` resolves `-cl-nv-opt-level=1` on those devices; `-Dsuperchunk.gpu.nvOptLevel=-1..3`
  overrides. `CLProgram.effectiveOptions` appends it, so both program caches key on it.
- `CLProgramCache` logs per-build compile time and the total in its stats line, and has
  `-Dsuperchunk.gpu.dumpProgramsTo=<dir>`; `tools/compilebench.c` times dumped programs.

**Why:**
- ptxas is ~80% of an NVIDIA build.
- The driver serializes builds per process, so threads don't help.
- Level 1 compiles ~30% faster than the driver default with the same output and throughput.
- Level 0 is faster to compile but 23% slower to run.

**Forge, cold** (empty program cache + `CUDA_CACHE_DISABLE=1`), vanilla world, RTX 3070:
- Level 1, with the parity self-test: boot 297 s, 339 s of compiling (53 programs incl. 12
  self-test), fused build 49 s, GPU-vs-VANILLA parity PASS (FP64 bit-exact).
- Driver default, no self-test: boot 377 s, 500 s of compiling (41 programs), fused build 67 s.
- So level 1 compiles ~32% less even while also building the self-test's 12 programs.

## SAVE/SHUTDOWN FIXES FROM NEO (2026-10-09) — and neo now has this tree's 10-06/07 fixes

Found on neo-1.21.1 stress-testing a 321-mod pack (`neo-1.21.1/analysis/save-shutdown-2026-10-08.md`).
This tree had the same code. Neo commit `9f2aac5`; the reverse port is neo `34b1a48`, PR #16.
Backup before this round: `superchunk-backups/forge-1.20.1-src-pre-savefixes-20261009.tgz`.

- **`/save-all flush` during chunk loading never finished.**
  - Cause: the `opts/scheduling/mixin/shutdown` pump re-scanned all loaded chunks each pass while
    its own pumping loaded new ones, so a pass never came back empty.
  - Fix: `task_scheduling.MixinEntityChunkDataAccess` queues entity deserialization so that
    `EntityStorage.flush` (`m_182219_`) drains it, as vanilla drains `entityDeserializerQueue`; the
    pump and `ITryFlushable` are deleted. `C2MEStorageVanillaInterface.synchronize` honours `sync`
    (chunk/POI storage only here; entity storage is vanilla's IOWorker on 1.20.1).
  - Reproduced HERE (minimal server, vanilla worldgen, Chunky 1.3.146): the old jar was killed by
    the watchdog on the 5th flush during pregens. New: 25/25 flushes in 0.1–2.0 s, no watchdog.
- **Chunk futures completed on FlowSched workers.**
  - Cause: once the server is stopping, `MinecraftServer.execute` runs tasks inline, so a ticket
    removal in a future callback (NeoForge Chunky) raced `removeTicketsOnClosing`. The resulting
    NPE skipped the world save (neo: 5/5 runs).
  - Fix: `NewChunkHolderVanillaInterface`'s three wrappers use `handleAsync` with
    `TheChunkSystem.vanillaCompletionExecutor` (inline on the server thread, else queued there).
  - NOT reproducible here: Chunky Forge 1.3.146 never calls `removeRegionTicket` (3/3 clean stops
    on the old jar). The code path is identical, so any mod doing that is exposed.
- **`ReadFromDiskAsync.asyncSave`:** a block entity throwing while the scope is built now falls back
  to vanilla's save instead of failing the unload. It only matters with
  `chunkSystem.asyncSerialization=true`, which is off by default here; the default sync path
  already behaves like vanilla.
- Also: the `BiomeQuartCache.UNCACHEABLE` collision (now `0`, regression check added); the
  GcAdvisory hint file no longer says servers are auto-configured; `mods.toml` now admits only
  `minecraft [1.20.1]`, `forge [47,48)`, which closes the 1.20.2–1.20.6 item under "Found, not
  fixed" below.

Verified with the shell jar on Forge 47.3.0:
- 13/13 regression programs pass; the refmap maps `flush`/`loadEntities`.
- Flush+SIGKILL: 441/441 chunks (×2).
- 144/144 tagged armor stands after a flush+SIGKILL during an active pregen.
- Concurrent A/B vs the 10-07 jar (3 pairs, 37,249 chunks): 1,014 vs 1,014 CPU-s; wall 105.7 vs
  106.3 s — neutral.

## CONFIRMED, PERF-CHECKED, NOISIUM REPLACED (2026-10-07)

Every fix from 2026-10-06 re-run as an A/B: **pre-fix** = the pre-bugfix source backup rebuilt
(`superchunk-backups/forge-1.20.1-src-pre-bugfix-20261006.tgz`), **final** = this tree. All legs in
memory-capped cgroups (`systemd-run --user --scope -p MemoryMax=…`).

| fix | pre-fix | final |
|---|---|---|
| Noisium biome order | 32/1,089 chunks' biomes differ from vanilla | 0 (also 0 vs vanilla in Nether, End, and all mod sets) |
| C2ME geode seed | chunk (130,127): 1,565 blocks differ | 162, none of them geode blocks (blob overlap = generation order) |
| `chunksystem.disable` CCE | 3 ClassCastExceptions at boot | 0 |
| save retry vs close | normal stop with retries pending (`-Dsuperchunk.debug.failFirstWriteAttempt` + `writeRetryDelayMillis=3000`): **2,525 of 2,650 retried chunks lost** | 0 lost (stop waits 4.4 s vs 1.9 s) |
| Radium out-of-region skip | Radium + far-reading datapack: 12 "No chunk exists at", 12 chunks failed, pregen failed | 81/81 chunks, feature skipped with a warning; new regression test: pre-fix code fails 3 of 11 cases |
| Starlight declared incompatible | loads, saves 0 SkyLight sections | refuses to load, names starlight |
| GPU inline guard (Tectonic) | OOM-killed at the 10 GB cap after DF #101 | boots, 11 programs refused, terrain = CPU |
| boot line once | 2 | 1 |

The far-reading datapack is a Forge biome modifier adding a `random_patch` with `xz_spread` 200
(reads ~12 chunks out; 1.20.1's feature region is 8). The save-retry window is only 100 ms in
production, so it needed the delay knob to hit deterministically; both knobs are debug-only, default off/100.

### Noisium's biome-fill speedup, done exactly: `worldgen.ClimateColumns`
Noisium's reorder only changed the palette-write order — palette and loop work is 2.3% of biome-fill
time (~0.1% of worldgen CPU, JFR), so it had nothing to win. Biome fill itself is 5.1% of worker CPU:
about half the flat climate search, most of the rest temperature/vegetation noise, recomputed for all
1,536 quart cells of a chunk although in vanilla only depth reads y. `ClimateColumns` decides per noise
router (from the vanilla density functions; DFC decides before compiling and registers the compiled
router) which climate functions ignore y — same conservative rules as `AquiferCellSharing`, unknown
types count as reading y — tags the sampler `NoiseChunk.cachedClimateSampler` builds, and while
`fillBiomesFromNoise` runs reuses those values per quart column (16-slot per-thread memo). Lookups stay
one per cell in vanilla order, so the R-tree warm start and every biome are unchanged. Stands down if
another mod hooks `Climate$Sampler#sample` or `NoiseChunk#cachedClimateSampler` (probe target + SRG
names added). Kill switch `-Dsuperchunk.worldgen.climateColumns=false`; verify
`-Dsuperchunk.worldgen.climateColumns.verify=true`. Vanilla/Terralith+Tectonic/BOP+TerraBlender/all 12:
reuses temperature, humidity, continentalness, erosion, weirdness (not depth). Verify 3.63M samples per
set and 15.2M with Nether + End: 0 mismatches; biomes identical to vanilla everywhere. Biome fill
5.1% → 2.9% of worker CPU.

### Performance (pre-fix vs final, same box, same session)
| | pre-fix | final | |
|---|---|---|---|
| CPU, 4 concurrent pairs r1024: CPU ms/chunk | 27.61 | 27.34 | −1.0% (3 lower, 1 tie) |
| CPU wall / steady cps | 269.2 / 294.7 | 271.9 / 296.4 | +1.0% / +0.6% |
| GPU, 3 interleaved pairs r1024 (warm caches): CPU ms/chunk | 16.97 | 16.61 | −2.1% |
| GPU wall / steady cps | 493.9 / 554.8 | 506.6 / 584.2 | +2.6% / +5.3% |
| all 12 mods, 2 CPU pairs r512: CPU ms/chunk | 57.31 | 57.16 | −0.3% (noise) |
| modded wall cps | 128.3 | 129.9 | +1.3% |

No regression anywhere; the gain is the column reuse (largest in GPU mode, where the CPU climate work
is a bigger share).

### Also fixed today
- **build.gradle: jars could ship without `superchunk.refmap.json`.** `org.gradle.caching=true` + a
  FROM-CACHE `compileJava` (sources compiled before: a revert, a second checkout) restored the classes
  but not the mixin AP's refmap, an undeclared side output, so every string-target mixin would fail on
  an SRG server. Found rebuilding the pre-fix jar. The refmap and its mappings are now declared
  `compileJava` outputs; verified a FROM-CACHE compile restores them.
- `FeatureRegionReadsTest` added to the regression programs (now 13).

### FIXED — `save-all flush` was not a durability barrier (pre-existing; neo has the same code)
Kill the JVM the moment `save-all flush` prints "Saved the game" after generating 441 chunks:
vanilla Forge keeps 441/441; SuperChunk before this fix 165-328; after: 441/441 (twice), and 441/441
with all 12 mods. Cause: a holder leaves vanilla's holder map (`wasAccessibleSinceLastSave()` = status >=
SERVER_ACCESSIBLE, neo's identical override, from upstream C2ME's `isAccessible`) as soon as it drops
below SERVER_ACCESSIBLE, but keeps its full chunk until `ReadFromDisk.downgradeFromThis` saves it
after a light sync — and can wait there while neighbours depend on it. `ChunkMap.saveAllChunks(true)`
saved neither those nor waited for them (vanilla's own `processUnloads` does). Fix:
`TheChunkSystem.saveFullChunksOutsideHolderMap()`, injected before `flushWorker()` in
`saveAllChunks(Z)V`, saves on the main thread every holder's full chunk that is not in the holder map
(unwrapping `ImposterProtoChunk`, whose `isUnsaved()` is always false); the later unload save then
finds nothing unsaved, as after any vanilla save. Flush saves only (`/save-all flush`, shutdown);
autosave and generation are untouched. Needs `StatusAdvancingScheduler.snapshotHolders()` (read-locked
copy). Stress: 12 flushes every 2 s during a 2,601-chunk all-mods pregen — no errors, clean stop, clean
re-read. Exposure before the fix was a crash or kill right after a flush, e.g. backup scripts doing
`save-off` / `save-all flush` / copy.

## BUG HUNT + MODDED WORLDGEN (2026-10-06) — 7 fixes, 3 of them also needed in neo

Three read-only code reviews (chunk system/IO, worldgen levers, wiring/config), then real servers
with 12 worldgen mods from Modrinth: Terralith 2.5.4, Tectonic 3.0.17 (+ Lithostitched 1.4.11,
an undeclared dependency), Biomes O' Plenty 19.0.0.96 (+ TerraBlender 3.0.1.11, GlitchCore),
YUNG's API / Better Dungeons / Mineshafts / Strongholds, When Dungeons Arise 2.1.58, Repurposed
Structures 7.1.25, and Starlight (Forge) 1.1.2. Harness: `tools/run-worldgen-benchmark.py` legs
from `scratchpad/modtest/scripts/modleg.sh`, plus SuperChunk-free vanilla legs (an empty jar in the
SuperChunk slot) to diff saved worlds against. Pre-fix backup:
`~/mod_dev/superchunk-backups/forge-1.20.1-src-pre-bugfix-20261006.tgz`.

### Fixed — worldgen output differed from vanilla (both affect neo too: same code, not yet fixed there)
- **Noisium `ChunkSectionMixin` changed biomes** (~3% of chunks; 32/1,089 at r256 on plain vanilla
  worldgen, 13–34 with the mod sets). It reorders `LevelChunkSection.fillBiomesFromNoise` from
  x,y,z to y,z,x, but `Climate.RTree.search` warm-starts from the thread's previous result and keeps
  it on an exact distance tie, so lookup order is part of the output (plains vs forest columns,
  river vs dripstone_caves cells). Unregistered from `noisium.mixins.json`; the class stays, marked
  do-not-re-add (deleting it was refused by the session's permission guard). After: biomes
  IDENTICAL to vanilla — no mods, Terralith+Tectonic, and all 12 mods.
- **C2ME `SimplifiedAtomicSimpleRandom` mis-seeded** → every amethyst geode a different shape than
  vanilla. The `random_instances` redirect swaps `new LegacyRandomSource(seed)` in four classes; its
  constructor stored the raw seed where vanilla's calls `setSeed` (scramble with `^ 0x5DEECE66D`).
  Three call sites reseed at once (harmless); `GeodeFeature` builds its NormalNoise straight from it.
  Upstream C2ME has the same constructor. Fix: constructor calls `setSeed`. Chunk (130,127):
  1,459 differing blocks → 56 (vanilla run-to-run noise there: 9).

**What remains is generation order, not code.** Overlapping features (trees, ore/stone blobs,
dripstone, sculk) resolve by which neighbour decorates last. Proof: single-threaded legs
(`-Dmax.bg.threads=1`, chunk system off) are reproducible run-to-run; SuperChunk there differed from
vanilla in exactly the same chunks every time — until vanilla was given SuperChunk's Chunky in-flight
limit (`-Dchunky.maxWorkingCount=768`, which `applyChunky` sets): vanilla(768) reproduced SuperChunk's
"systematic" chunks block for block, and SuperChunk pinned to Chunky's 50 matched vanilla(50) at
vanilla's own noise level (5,677 vs 5,464 blocks). No lever group (30 kill switches, 3 groups)
changed any of it.

### Fixed — GPU (also affects neo: shared code)
- **Tectonic + `gpu.enabled=true` → the server is OOM-killed at startup.** Its router DFs generate
  programs whose spline call DAG the NVIDIA compiler inlines whole: measured with the headers,
  1.9–11.7 GB of inlined source (vanilla's largest: 214 MB; a truly cold vanilla build peaks at
  7.2 GB RSS). Building one took the JVM past 20 GB anon RSS on a 5 GB heap; three legs died this way
  (and systemd-oomd also killed a GNOME Rfkill helper once). Source size does not predict it (vanilla
  2.8 MB vs Tectonic 2.6 MB). New guard in `CLProgramCache.getOrBuild` (cold builds only, never cache
  hits): `KernelInlineEstimate` sums each function's body plus its callees per call site; over
  `-Dsuperchunk.gpu.maxInlinedKernelMB` (default 500, `<=0` off) the build is refused and those DFs
  take the existing per-DF CPU fallback. After: Tectonic GPU boots, 6.96 GB peak, 246/33 DFs GPU/CPU,
  noise-fill hashes = CPU on all 2,098 chunks; vanilla with an empty program cache: 0 refusals, decide
  chain built, 0 flips.

### Fixed — forge-only
- **`-Dsuperchunk.chunksystem.disable=true` threw ClassCastException** in notickvd/predictive gen:
  the StuckLoadSweeper copied in this catch-up casts `ChunkMap` to `IChunkSystemAccess` before the
  `CHUNK_SYSTEM_TICKETS` gate (neo has no vanilla-ticket fallback, so no gate). Seen headless at
  boot (3 stack traces, old jar) — the harness missed it because a bare `printStackTrace` is not an
  ERROR line. Gated in `PlayerNoTickLoader.tickFutures` and `PredictiveChunkLoader.tickFutures`. After: 0.
- **A retried chunk write could be lost at shutdown.** The forge-only serialize retry waits 100 ms
  on `delayedExecutor` while the failed attempt's future had already completed, so `flush`/`close`
  could finish inside the gap and the storage close; the retry then never ran (only a WARN "retrying"
  line). The attempt's future now stays pending until the retry is queued (`thenCompose`).
  Pre-existing since 0.3.0; triggers only on the rare serializer CME.
- **Out-of-region feature skip did not engage with Radium/Canary**: their `gen.chunk_region`
  (on by default) throws `NullPointerException("No chunk exists at ...")` instead of vanilla's
  RuntimeException; `FeatureRegionReads.outsideRegion` now accepts it, and also vanilla's
  suffix-less `getCurrentDifficultyAt` message.
- **Starlight (Forge) declared incompatible** in mods.toml (same mechanism as scalablelux/c2me/noisium):
  installed together, Mixin skips several bundled ScalableLux overwrites and the hybrid saves NO light
  (0 SkyLight/BlockLight sections in 1,089 chunks; Starlight alone saves normally) while stamping
  `starlight.light_version=9`. Verified: the loader now refuses with "only supports starlight below 0.0.0".
- Cosmetic: duplicate `entity.collisions.intersection.LevelMixin` in superchunk-lithium.mixins.json;
  boot lines printed twice (vmp.mixins.json shares `SuperChunkMixinPlugin`; now once).

### Modded worldgen results (fixed jar unless noted)
| set | boot/gen | verify gates | noise hash on=off=verify | GPU hash = CPU |
|---|---|---|---|---|
| Terralith + Tectonic | clean | 0 mismatches | 0/2,098 | 0/2,098 (with guard) |
| BOP + TerraBlender | clean | 0 | 0/2,098 | 0/2,098 (1 known fp32 flip in 204.7M blocks, hard gates 0) |
| YUNG ×3 + Dungeons Arise + Repurposed Structures | clean | 0 | 0/2,098 | 0/2,098 |
| all 12 | clean | 0 | 0/2,098; DFC off also 0 | — |

Stand-downs worked and named their cause: Lithostitched's and YUNG's API's `BeardifierMixin` turn
off GPU block ids + air-cell skip; TerraBlender bypasses `findValueIndex` (its regions call
`RTree.search` directly), so the flat climate search simply never runs there (0 checked, no refusal).
Restart/re-read of the all-mods world clean, storage threads retired and restarted. All 12 mods at r768
(9,409 chunks, 8 workers): 100%, 217 cps, 0 errors (a first attempt was SIGKILLed at 83% by something
outside the JVM — no kernel/cgroup OOM record — and did not recur). 0 "Error upgrading chunk", 0
CheckedThreadLocalRandom, 0 mixin failures across 61 legs.

### Found, not fixed (low)
- Entity region IO (`EntityStorage`'s own `new IOWorker`) is not routed through C2ME storage on
  1.20.1 (neo's `SimpleRegionStorage` covers both) — performance parity only.
- `async_serialization/gc_free_serializer` redirects use Mojang names with `remap=false`: inert in
  production, but the module is hard-off (`chunk_serializer` enabled=false), so zero impact today.
- ~~`forge [47,)` / `minecraft [1.20.1,1.21)` admit 1.20.2–1.20.6 installs~~ — FIXED 2026-10-09
  (`[1.20.1]`, `[47,48)`).
- Vanilla Forge + BOP logged one "IOWorker Failed to store chunk" without SuperChunk (not ours).

## CAUGHT UP TO neo 0.4.1 (2026-10-05, 0.4.1 sync 2026-10-06) — neo `3199d19..9a504fd` + neo's uncommitted Voxy guard

`mod_version` 0.3.0 → **0.4.0**. Replays neo's six commits since the ghost-mushroom guard
(ee72903 worldgen opts + Skein dimension threading, 9b30e70 bump, 7b0f6c1 Skein cell bridge,
ee5dd76 noise-fill opts + aquifer exactness + review fixes, 9a4f5c0 out-of-region feature skip,
9a504fd docs) plus the Voxy override that sat uncommitted in neo's working tree. Pre-port backup
(this tree has no VCS): `~/mod_dev/superchunk-backups/forge-1.20.1-pre-0.4.0-20261005.tgz`.

**How the delta was taken** (the 0.3.0 recipe again): 46 touched source files were still
byte-identical to neo `3199d19` and took `git diff 3199d19` verbatim, zero rejects — then had their
hunks audited against the 1.20.1 sources anyway (`javap` diff of the recomp jar vs neo's), since
byte identity says nothing about API validity. 37 diverged files were re-expressed onto forge's
shape. 69 new files were ported with every mixin target, descriptor, `@Shadow` and `@At` checked
against the 1.20.1 mojmap sources.

### What landed
- **Worldgen CPU** (neo `analysis/worldgen-big-2026-09-25.md`, `optimization-2026-09-2*.md`): flat
  climate R-tree search, biome fiddle cache + cell path, ore random reuse, surface gradient random,
  per-cell lerp tables for `NoiseInterpolator`, bulk constant multiply in DFC `mul(constant, cache)`,
  the air-cell skip, palette pack fast path, small-palette indices, first-positional-float.
  Each has a kill switch and a lockstep `.verify` flag.
- **Aquifer cell cache exactness fix** + per-`RandomState` sharing decision (`AquiferCellSharing`,
  self-test `-Dsuperchunk.worldgen.aquiferCellSharing.selfTest=true`), and the per-chunk noise-fill
  hash (`-Dsuperchunk.debug.noiseFillHash=<file>`, `tools/noisehashdiff.py`).
- **Foreign-mixin stand-downs** (`MixinTargetScan` via `MixinProbeBiomeLookup`, `ForeignHooks`,
  `BlockFillHooks`): the air-cell skip and aquifer sharing step aside when another mod hooks the code.
- **Out-of-region features** (`MixinFeatureOutsideRegion`, `MixinWorldGenRegionFarWrites`,
  `FeatureRegionReads`): a placed feature that reads past the decoration region is skipped for that
  chunk instead of failing it (the JJThunder freeze).
- **Chunk system review fixes**: `UnreachableChunkWait` (a server-thread `getChunk` stuck behind a
  broken chunk is abandoned with an error naming it), `StuckLoadSweeper` (frees no-tick/predictive
  load slots held by such chunks), opt-in non-creating `getChunk` budget, no negative-caching of a
  still-loading chunk, the broken-holder revive fix in `ReadFromDiskAsync`, flowsched
  `failPendingFuturesAbove`/`findBrokenBlocker`, consolidating-queue allocation fix, notickvd's
  "list a non-ticking FULL chunk only if it has pending broadcasts" (`IPendingBroadcast`).
- **Shutdown entity flush** (`shutdown.MixinServerLevel` + `shutdown.MixinPersistentEntitySectionManager`):
  upstream C2ME's pump for `/save-all flush` and server stop. The kept
  `task_scheduling.MixinEntityChunkDataAccess` moves entity loads onto the main-thread queue on this
  port too, so the same spin was reachable here.
- **Storage threads retire when idle** (`C2MEStorageThread`, neo issue #7, the DH thread leak):
  `-Dsuperchunk.io.storageIdleRetireMillis=0` restores pinned threads.
- **Skein** dimension + cell threading bridge (`SkeinCompat`, five `MixinSkein*`, the thread-check
  exemptions in the async catchers, Lithium `ChunkStatusTracker`/`block_entity_retrieval`, and the
  nine Lithium caches written off in `lithium.properties` when Skein is installed).
- **Quiet logging by default** (`logging.verbose`, `LogQuieter` + `Log4jQuietFilter`), the
  documented `superchunk.properties` (format 3: every option described; old files are regenerated
  once with values kept and a `.bak`), `OptionalTargets`, `GcAdvisory` opt-in, CL program-cache epoch.
- **mods.toml**: ScalableLux / C2ME / Noisium declared incompatible (they are bundled).
- **Tests**: neo's 12 standalone regression programs, as `JavaExec` tasks wired into `check`.
- **Tools**: `run-worldgen-benchmark.py`, `verify-skein-server.py` (both adapted, below),
  `noisehashdiff.py`, `worlddiff.py`, `verify-compact-consume.py`.

### Divergences added in this round (deliberate — do not "fix" back)
1. **Production SRG names** (`MixinTargetScan`, `FlatClimateIndex`, `InheritedPlacementPositions`).
   Forge runs SRG names in production (`m_196879_`, `f_186910_`), NeoForge 1.21.1 runs Mojang names.
   Neo's lookups by Mojang name would come back "absent" in every production boot here: the
   foreign-hook stand-downs would never fire and the flat climate search and the repeating/in-square
   placement fast paths would be refused. Each now tries the Mojang name, then the SRG name
   (`MixinTargetScan` carries an 18-entry SRG table from `srg_to_official_1.20.1.tsrg`).
2. **`FeatureRegionReads.outsideRegion`** matches 1.20.1's refusal: `WorldGenRegion.getChunk` throws
   a plain `RuntimeException` ("We are asking a region for a chunk out of bound" / "Chunk is not of
   correct status"), not 1.21.1's `IllegalStateException("Requested chunk unavailable...")`. Note
   1.20.1 also logs two vanilla ERROR lines ("Requested chunk", "Region bounds") before each refused
   read; those are vanilla's and were left alone.
3. **`ChunkResult` → `Either<ChunkAccess, ChunkLoadingFailure>`** in the chunk-system
   `MixinServerChunkManager` wraps and `UnreachableChunkWait.abandonedResult()` (an anonymous
   `ChunkLoadingFailure` whose `toString()` is the reason — that is what 1.20.1 prints after "Chunk
   not there when requested: "). Holder lookups keep this port's `invokeGetChunkHolder`.
4. **gc-free chunk serializer is forced off** with the Forge reason: it never writes the `ForgeCaps`
   tag nor fires `ChunkDataEvent.Save`.
5. **`SuperChunkConfig` descriptions** say what is true on 1.20.1: `sendAtChunkSending` stays
   `false` and inert (divergence 1 of the 0.3.0 round), `latencyMetrics` and
   `fullSpeedLoading.chunksPerTick`/`maxUnackedBatches` have no effect (no 1.20.2+ chunk sender),
   ghost-mushroom suppression is on because notickvd sends early unconditionally here.
6. **`GcAdvisory`**: neo's opt-in (`-Dsuperchunk.gc.autoConfig=true`, default now `false`) and its
   "collector chosen elsewhere" check, slotted ahead of this port's Java-21 `needs-jvm` gate. The
   flags file in `config/` is now always written, which also fixes the misleading "Exact flags
   written" line recorded under upstream bugs below.
7. **Incompatibilities use the impossible optional-dep range** (as for betterchunkloading); the
   Noisium redirect collision is loader-independent, the C2ME/ScalableLux config names are the same
   files this jar registers.
8. **Skein**: `MixinSkeinCommandBlock` shadows `tick` as `public` (protected on 1.21.1). (The
   `com.theproknightgamer.skein` package root is now in neo too; those files match neo exactly.)
9. **Java 17**: `AquiferCellSharing.children()` pattern `switch` → `instanceof` chain;
   `ReusableOreRandom` drops the `fromSeed(long)` override (no such method on 1.20.1).
10. **Tests**: `FlatClimateIndexTest.markBootstrapped()` (1.20.1's `ResourceKey.createRegistryKey`
    initialises `BuiltInRegistries`, which refuses to construct before `Bootstrap`); no
    `failOnNoDiscoveredTests` (Gradle 8.8 does not need it).
11. **Tools**: `run-worldgen-benchmark.py` finds `libraries/net/minecraftforge/forge/1.20.1-*/unix_args.txt`
    and drops the 1.20.5+ `spawnChunkRadius` gamerule; `verify-skein-server.py` writes a 1.20.1
    datapack (`pack_format` 15, `functions/` folders, `Count:64b`) and accepts Skein's current
    reload wording.

### Neo fixes that were already absent here (nothing to port)
- **"Chunks saved only on unload"** (neo kept the Yarn name `isAccessible`, overriding nothing): this
  port already overrode `wasAccessibleSinceLastSave`/`refreshAccessibility` in
  `NewChunkHolderVanillaInterface`. Only `c2me$blockTicking()` was added.
- **GitHub #6, stranded saved ticks** (neo called `registerTickContainerInLevel` where upstream calls
  `startTickingChunk`): this port's `ServerBlockTicking` already called `startTickingChunk`.
- `notickvd.mixin.servercore.*` — this port has no ServerCore compat.

### Upstream (neo) bugs found while porting — fix them THERE
- ~~Skein detection dead against current Skein~~ (package renamed to `com.theproknightgamer.skein`
  on 2026-09-30). Neo fixed it in its working tree the same day (uncommitted); forge's
  `SkeinCompat`, `compat.MixinSkein*` and `SkeinCompatTest` were then re-copied from neo, so they are
  byte-identical again.
- **`tools/verify-skein-server.py` expects Skein's old reload reply** ("config re-read."); current
  Skein says "config will be re-read at the start of the next tick". Fixed in this tree's copy.
- **`tools/verify-skein-server.py` reads the `phases:` line the old way** — current Skein lists
  blocked phases as `name (blocked)`, so even a correct dimension-only run fails `expected_phases`.
  Fixed in this tree's copy only.
- **`tools/verify-compact-consume.py` no longer runs against neo's own `CompactConsume`**: ee5dd76
  made `marksFromIds` use `ctx.cellWidth`/`ctx.cellsXZ` and vanilla `doFill` mark order; the tool's
  stub `Ctx` lacks the fields and its reference still emits id-array order ("postprocess mark/order
  mismatch"). Left byte-identical here; the in-game `compactIds=verify` gate covers the same ground.
- ~~`LithiumMixinPlugin` static-init order~~ — fixed in neo 2026-10-09 (`34b1a48`).

### Verification (2026-10-05, production shell jar, Forge 1.20.1-47.3.0, isolated server dirs)
Harness: `tools/run-worldgen-benchmark.py` with `~/mod_dev/sc-forge-test` as the template (only its
libraries, eula and Chunky jar are reused; every leg is a fresh directory, seed −987654321, 12
workers, 8 GB, ZGC). RTX 3070 shared with another process at ~46% utilisation throughout, ~5 of 28
hardware threads busy elsewhere — correctness legs only; no throughput claim is made from them.

- **Build**: `./gradlew build` green; all 12 regression programs pass on 1.20.1 (4.32M lockstep
  climate lookups, 13.8M fiddle distances, 1.5M aquifer-column cases, 121 Skein checks, ...).
  Refmap carries entries for every new Minecraft-targeting mixin; `@Shadow`/`@Overwrite` names in
  the reobf'd jar are SRG (`f_157493_`, `m_183479_`, ...). 430 "Mixing" lines at boot, all new ones
  present (`MixinProbeBiomeLookup` into all 27 targets).
- **CPU parity gate** (r256, every verify flag): 0 mismatches — 468.1M lerp-table reads, 120.1M
  fiddle distances, 4.08M flat-climate searches, 24.9M quart-cache hits, 5.5M ore-RNG, 5.6M surface
  gradient draws, 50M plain-random draws, beard/air-skip BUGS=0. Aquifer sharing self-test: 10
  routers, PASS.
- **Air-cell skip**: 994,874 of 1,611,264 cells (61.7%) predicted air, 127.3M blocks checked, 0
  mismatches. (It deliberately stands down while `airSkip.verify`/`adaptiveAirSkip.verify` run — a
  leg with those on shows `predicted air=0`, which is not a bug.)
- **Noise-fill hashes** (2,098 chunks each): new opts on vs all off (`aquiferCellCache`,
  `lerpTables`, `dfc.mulBulk`, `airCells` = false, i.e. vanilla per chunk), verify mode, and a
  repeat run — **0 mismatched chunks** in every pairing.
- **GPU** (r512, warm program cache): batch pipeline engaged (5,453 chunks prefetched, 0 misses,
  144 launches, ~7,600x coalescing). `compactIds=verify` with **fp64 decide: 0 divergence over 531.9M
  blocks**; with the shipped fp32 decide, 1 stone→air flip in 530.7M blocks (hard gates 0) — the
  known fp32 rate, not a port effect.
- **Restart**: a generated world reboots clean, all 1,089 chunks re-read through the storage path,
  `save-all flush` completes, 3 storage threads retired during a 12 s idle (5 s retire) and the next
  read pass restarted them transparently; exit 0.
- **CPU r1024**: 16,641 chunks, 547.8 cps CPU-only, 25.5 ms CPU per chunk, 0 failed chunks, exit 0.

### FIXED HERE, forge-only — MC-271899: StructureTemplate palette cache race stalled pregens
Found by this round's runtime legs, **pre-existing (reproduced on the 0.3.0 jar)**. Symptom: a Chunky
pregen stops at 96-98% (4,059/4,225 and 4,133/4,225 here) with every `c2me-worker` parked idle and
the GPU batcher threads waiting — nothing is running, the last chunks just never finish. Cause, from
`logs/debug.log`: `Error upgrading chunk [x, z] to "minecraft:features"` ← `ConcurrentModificationException`
at `HashMap.computeIfAbsent` ← `StructureTemplate$Palette.blocks(Block)`. Vanilla 1.20.1 keeps that
per-template filter cache in a plain `HashMap`; templates are global, and C2ME runs feature placement
on many threads, so two threads placing pieces of one template race on it. The failed chunk is
MARK_BROKEN and every neighbour needing it at a later status waits forever.

**Why neo never had it:** NeoForge 1.21.1 patches that field to `Maps.newConcurrentMap()` itself
("Neo: Fixes MC-271899"). There is no neo-side code to port — a pure diff of the two trees can never
surface this. Forge 1.20.1 has no such patch.

**Fix:** `fixes.worldgen.threading_issues.mixin.threading.MixinStructureTemplatePalette` swaps in a
`ConcurrentHashMap` at constructor RETURN — NeoForge's fix, in this module's own idiom (the sibling
`MixinStructurePlacementData` does the same for the processor list). The mapping function reads only
`blocks`, so per-bin locking cannot deadlock.

This is very likely also what killed the 0.3.0 round's second `asyncSerialization` leg ("threw
ConcurrentModificationException out of Feature placement at 99.57% then stalled") — that note is
above. `asyncSerialization` stays OFF; it was not re-measured.

### Skein on this port (Skein `1.20.1-forge` jar, `tools/verify-skein-server.py`)
- **Dimension threading: PASS**, fresh world and restart — generation, saves, 3 force-loads, hoppers
  and chests in all three dimensions, tick-function reload, **command-block transactions deferred
  past the dimension barrier** (SuperChunk's message logged), 3 command-block reloads, local and
  cross-dimension `spreadplayers`, conditional chain, command minecart, 3 hopper transfers, 0 errors.
  That exercises every `MixinSkein*` hook and the dimension-owner exemptions in the async catchers.
- SuperChunk's side of the **cell bridge negotiates** ("negotiated Skein's cell bridge", v1) and the
  nine Lithium caches are written off. **But Skein itself keeps the in-level phases off**: its
  1.20.1-forge `LithiumCompat` cannot read SuperChunk's bundled Lithium ("no mixin plugin found for
  SuperChunk's Lithium"), so it assumes the caches are on and blocks scheduled/random ticks,
  entities and block entities. Safe fallback (dimension-only), not a SuperChunk defect. Re-tested
  against Skein's 2026-10-05 20:25 rebuild (which adds a `lithium:options` table — this port's
  `ForgeMixinOverrides` honours it): same result.
  **Cause (Mixin 0.8.5):** Skein finds the plugin with `Mixins.getMixinsForClass(<vanilla target>)`
  and reads each `IMixinInfo`'s config. In 0.8.5, `MixinInfo.postApply` records the applied mixin on
  the **mixin class's own** `ClassInfo` (`this.info` = the mixin's state), never on the target's, so
  that call returns an empty set for every vanilla class — for any Lithium port, on any 1.20.1
  install. Skein-side fix: ask `Mixins.getMixinsForClass` for one of the port's *mixin* classes
  instead (e.g. `dev/superchunk/net/caffeinemc/mods/lithium/mixin/util/accessors/LevelAccessor`),
  whose `getConfig().getPlugin()` is the plugin. `--parallel-phases` fails until then.
- The tool needed two more updates for current Skein (both ports affected): the reload reply wording,
  and the `phases:` line now lists blocked phases as `name (blocked)`; the tool now counts only the
  running ones.

### Observed once, not reproduced — SIGABRT at JVM exit
The very first GPU leg of this jar (r512, `compactIds=verify`, cold program cache AND a driver that
had never compiled these kernels) ended with glibc `malloc_consolidate(): unaligned fastbin chunk
detected` after every chunk was saved and every storage thread had stopped (world intact; exit
-6). Not reproduced in 6 further cold-cache GPU legs (3 per jar, 0.3.0 and 0.4.0, exit 0 each);
forcing a truly cold driver compile (`CUDA_CACHE_DISABLE=1`) makes boot alone exceed 4 minutes on
both jars, so the original timing could not be recreated. Best guess: driver teardown racing a
kernel compile still in flight at exit. Watch for it; if it recurs, collect the hs_err/core.

### STILL NOT VERIFIED
- Skein in-level (cell) phases — blocked Skein-side, above.
- The Voxy guard (`ForgeMixinOverrides`): no Forge 1.20.1 Voxy build is known; inert unless modid
  `voxy` loads.
- Distant Horizons, the Embeddium render-distance cap, and the client in general — unchanged from
  the 0.3.0 round (no client harness for 1.20.1).
- No quiet-box throughput number; see PERFORMANCE VERIFIED below for contention-robust A/Bs.

### PERFORMANCE VERIFIED (2026-10-06) — 0.4.x is faster than 0.3.0 in both modes, at parity with neo
**Neo 0.4.1 synced first**: neo's 0.4.1 is 0.4.0 + the Voxy guard + the Skein package rename (all
already here) + "drop the oldest Skein package root". The four Skein files were re-copied from neo
HEAD (byte-identical) and `mod_version` → **0.4.1**; build green, SkeinCompatTest 120 checks. Perf-
irrelevant change, so the 0.4.0 numbers below stand for 0.4.1.

**Baseline**: the pre-port 0.3.0 tree (from the backup tarball) + ONLY `MixinStructureTemplatePalette`,
built as `superchunk-0.3.0-palettefix`. Plain 0.3.0 cannot be benchmarked: it stalls on MC-271899.
Same box as everything else this round, never quiet (load average 6-20, RTX 3070 at 44-72% from
other users' jobs), so the methods were chosen to survive contention:

- **CPU mode — concurrent pairs** (neo's `pair.py` method: both jars generate the same fresh world
  at the same time, 6 workers / 6 GB each, start order alternating), r1024, 4 rounds:

  | round | CPU ms/chunk old → new | steady cps old → new | wall cps old → new |
  |---|---|---|---|
  | 1 | 32.97 → 28.19 | 237.3 → 279.3 | 221.0 → 261.8 |
  | 2 | 33.41 → 28.75 | 243.3 → 283.4 | 218.0 → 256.4 |
  | 3 | 35.95 → 31.96 | 206.6 → 240.1 | 193.6 → 218.0 |
  | 4 | 31.38 → 27.92 | 251.2 → 297.3 | 232.1 → 264.7 |
  | **mean** | **33.43 → 29.21 (−12.6%)** | **234.6 → 275.0 (+17.2%)** | **216.2 → 250.2 (+15.7%)** |

- **GPU mode — sequential interleaved**, per-jar warm program cache seeded by a warm-up leg, 12
  workers, r1024, `-Dsuperchunk.gpu.decideProfile=true`, 4 pairs:

  | pair | wall cps old → new | steady cps old → new | CPU ms/chunk old → new |
  |---|---|---|---|
  | 1 | 727.7 → 800.1 | 873.3 → 921.6 | 21.90 → 19.10 |
  | 2 | 715.5 → 808.9 | 770.9 → 952.7 | 22.40 → 19.10 |
  | 3 | 708.3 → 798.8 | 765.5 → 970.1 | 21.87 → 19.26 |
  | 4 | 697.9 → 815.2 | 800.4 → 981.0 | 22.77 → 18.99 |
  | **mean** | **712.4 → 805.8 (+13.1%)** | **802.5 → 956.4 (+19.2%)** | **22.24 → 19.11 (−14.1%)** |

  Both sides fully on the GPU path every leg (all 18,393 chunks incl. neighbours prefetched and
  decided on the device, 0 decide faults, 40/41 disk-cache hits; one leg had 71 misses = 0.4%).
  Device time per chunk (`[gpu-timeline]` busy ÷ `mean batch size`) fell ~1.91 → ~1.74 ms, mostly
  from larger batches (14.6 → 18.5 chunks): the CPU side now frees slots faster.

New won all 8 pairings. The gain is larger than neo's own 0.3.0→0.4.0 rounds (+2.3%, ~flat, +6.0%
CPU), which is plausible: the kill-switch A/B below attributes −8.3% CPU/chunk to the new worldgen
levers alone, and the rest have no switch (flowsched allocation cuts, storage threads no longer
spinning ~16k wake-ups/s after each burst, consolidating-queue reuse). Output is unchanged — see
the noise-fill hash and verify gates above.

**Forge 0.4.1 vs neo 0.4.1, same box, same time** (context only: 1.20.1 and 1.21.1 do not generate
identical work, and Chunky versions differ):

| mode | forge | neo |
|---|---|---|
| CPU, concurrent pairs ×2: CPU ms/chunk, steady cps | 28.69, 274.0 | 29.86, 259.9 |
| GPU, interleaved ×2: wall cps, steady cps | 800.1, 964.4 | 767.2, 869.6 |

Parity or slightly ahead, as in the 0.3.0 round. Caveat: neo's GPU legs missed its program cache
(neo's harness copies the template's cache, which predates 0.4.1's kernels) and compiled in-leg with
a warm driver cache — a wall-time cost the steady-state window mostly excludes.

### Throughput, indicative only (shared box) — superseded by the section above
A 0.3.0-vs-0.4.0 A/B was not possible: the 0.3.0 legs kept failing chunks on the MC-271899 race
above (the one r1024 leg attempted, and 1 of 3 r512 legs; a failed chunk means a stalled pregen). Instead, this round's new CPU levers
were measured inside the 0.4.0 jar, CPU mode, r1024 (16,641 chunks), 12 workers, alternating legs,
"off" = `airCells`, `lerpTables`, `dfc.mulBulk`, `biomeFiddle`, `flatClimateSearch`,
`oreRandomReuse`, `surfaceGradientRandom`, `io.fastPalettePack` all `false`:

| pair | off cps / CPU ms per chunk | on cps / CPU ms per chunk |
|---|---|---|
| 1 | 434.9 / 31.54 | 469.8 / 29.19 |
| 2 | 448.8 / 30.55 | 461.3 / 29.71 |
| 3 | 423.5 / 32.28 | 506.4 / 27.63 |
| mean | 435.7 / 31.46 | 479.2 / 28.84 (**−8.3% CPU/chunk, +10.0% cps**) |

"On" won every pair; neo measured −3.6% / +6.0% the same way on a quiet box. Treat the size as
noise-inflated, the direction as real. Every leg on the fixed jar (these 6 plus the post-fix GPU and
r1024 legs, ~125k chunks) finished with 0 failed chunks; before the palette fix, 1 of 9 0.4.0 legs
stalled on the race.

## PORTED 2026-09-01 — ghost-mushroom scan crash guard (Twilight Forest)

Ported from neo 1.21.1 alongside the fix there; see that port's `compatibility.txt` (FIXED →
`twilightforest`) for the full mechanism. Short version: C2ME's MC-276863 workaround
(`chunkSystem.suppressGhostMushrooms`, which **SuperChunk defaults ON** while upstream ships it off)
calls `BlockState.canSurvive` on every vanilla mushroom in the post-processing list against a
bounded `WorldGenRegion`. Twilight Forest 4.8.3345 ASM-hooks `MushroomBlock.canSurvive` and reads
outside that region for a mushroom on a chunk border; the `ReportedException` escaped
`upgradeToThis` and `handleTransactionException` turned it into `MARK_BROKEN`.

`dev.superchunk.worldgen.GhostMushroomCheck` guards the call and treats any failure as "it
survives", so a check that could not be evaluated never deletes a block.

**Port difference, deliberate.** The region geometry is not the same on the two branches, and the
guard is written to cover both. 1.21.1 builds the region on
`ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL)`, whose `directDependencies()` has one
entry, so `WorldGenRegion.getChunk` allows a read radius of **0**. 1.20.1 has no `ChunkStep`: the
region is built over `ChunkLoadingContext.chunks()`, the 3x3 holder square, and 1.20.1's
`getChunk` bounds-checks against that list — so the legal radius here is **1**. TF's one-chunk
reach therefore does *not* crash this port; a hook reaching two chunks out still would, and the
guard covers it either way.

## FIXED 2026-08-28 — "chunks only load where I logged in" (port-only, 1.20.1)

**Symptom.** A client whose render distance is >= the server's `view-distance` received a full ring
on join and then **no new chunks at all** while moving. F3+A did not help (client-side only); only a
relog did. Clients with a smaller render distance were unaffected, so it looked intermittent.

**Cause.** `AreaPlayerChunkWatchingManager.getViewDistance()` watched
`clamp(clientVD, 2, watchDistance) + 1` — the `+1` was applied OUTSIDE the clamp, so the watched
radius could reach `watchDistance + 1`. Both send paths on this port stop at `watchDistance`:
`ChunkMap.getPlayers()` (the VMP overwrite) drops watchers past `ChunkMap.viewDistance`, and that is
the watcher list the CHUNK_SENDING seat uses. A one-chunk step adds *exactly* the ring at the
watched radius, so every newly entered chunk was unreachable: the area-map add ran
`updateChunkTracking` while the chunk was still unloaded (nothing to send, never retried), and the
later CHUNK_SENDING offer found zero watchers. Relogging worked because `updatePlayerStatus` re-adds
the whole area at once, by which time the chunks are loaded and the tracking path sends them.

**Why 1.20.1 only — MEASURED, not assumed.** neo 1.21.1 carries the identical
`clamp(reqVD, 2, watchDistance) + 1`, so it looks affected by inspection. It is not: same bot, same
one-chunk steps, at BOTH `view-distance=8`/client RD 8 and `view-distance=32`/client RD 32, neo
delivered the full watched radius every step (+19 and +67 chunks respectively — i.e. `2*(R_area)+1`,
including the outermost ring), where forge delivered **0**. Do NOT "fix" neo to match this change.

The structural reason is the 1.20.2+ `PlayerChunkSender`: on neo the area-map listeners are
`markChunkPendingToSend`/`dropChunk` against a *pending* set, and the ready-hook
(`onChunkReadyToSend`) filters by the player's `ChunkTrackingView` — state the same VMP code owns.
1.20.1 has neither (hence this port's own "no `chunkSender.isPending` clause — the API is 1.20.2+"
comment in the `getPlayers` overwrite): its two send paths are a one-shot `updateChunkTracking` that
needs the chunk loaded *right now*, and a `getPlayers` lookup one ring tighter than the area map.
That is what turns the spare ring into a dead one here and nowhere else.

**Fix.** `+1` moved inside the clamp, so the watched radius can never exceed `watchDistance`.

**Measured** (harness below, server `view-distance=32`, 1 chunk per step, 10 steps):

| client RD | before | after |
|---|---|---|
| 32 | **0** new chunks, 67 unloads/step | **65** new chunks, 65 unloads/step |
| 12 | 27 new chunks/step | 27 new chunks/step (unchanged) |

The before-case unload/send asymmetry (67 vs 65) is the dead ring: the server was sending
ForgetLevelChunk for a ring it had never sent.

**Repro harness.** Vanilla-protocol bot (`npm i mineflayer`, version `1.20.1`, `online-mode=false`)
counting `map_chunk` / `unload_chunk` / `update_view_position`, moved with RCON `tp` in 16-block
steps. Two traps that produce false negatives: the bot must set its client view distance explicitly
(mineflayer's `far` = 12, which is BELOW 32 and does not trigger the bug), and it must walk into
terrain it has not been sent before (a bot retracing its own path shows no new chunks either way).
`-Dsuperchunk.diag.chunkSend=true` prints the send funnel; `accWatchers=0` alongside `accSend>0` is
this bug's signature.

## PERFORMANCE: AT PARITY WITH NEO (measured 2026-08-17) — the "gap" was the harness

**r2048 (66,049 chunks), same box, harness matched to neo's:**

| build | wall cps | steady-state cps (20-90%) |
|---|---|---|
| forge 0.3.0 | 1246.2 / 1200.9 (mean **1223.6**) | 1269.6 / 1230.7 (mean **1250.2**) |
| neo 0.3.0 (2026-08-15, same box) | **1223.1** | **1288.0** |

Dead even on wall, ~3% apart on steady state — inside the leg-to-leg spread of both (neo legs ranged
1265-1310, forge 1230-1270). **There is no port-side performance defect to chase.**

The old "forge is 943-1032 cps vs neo 1223" figure was two artifacts stacked:
1. **A stale baseline.** 943 cps was 2026-07-18, BEFORE this tree received round 10. The `.cl`
   kernels are now byte-identical to neo's, so that alone is worth ~+12.6%.
2. **A misconfigured harness**, not the mod. `~/mod_dev/sc-forge-test` was running
   `-Dchunky.maxWorkingCount=768` (pinned by hand, which DEFEATS the shipped
   `pregen.chunkyWorkingCount=auto` -> 3072), no `globalExecutorParallelism` override, `-Xms2G`,
   `sync-chunk-writes=true` and `max-tick-time=60000`. neo's harness had none of those.
   **The shipped mod defaults were already correct** — a real user was never affected by any of it.

### The starvation cascade, and that it is gone
The in-flight cap was the whole story. Everything the audit flagged as a GPU-pipeline defect was
downstream of it, and all of it resolved by unpinning `chunky.maxWorkingCount`:

| metric | forge, in-flight 768 | forge, auto 3072 | neo |
|---|---|---|---|
| batcher MISSES | 2,405 | **0** | 28 |
| prefetch skipped / failed | 2,405 / 0 | **0 / 0** | 28 / 0 |
| fused fallback dispatches | 2,405 | **0** | 33 |
| GPU launches | 2,605 | **200** | 193 |
| coalescing factor | 338x | **1,882x** | 5,586x |
| density-fill wall-time GPU% | 84.4% | healthy | 16.9% |

`prefetch skipped == MISSES` on both builds, i.e. the miss originates at prefetch-ARM time: with too
few chunks in flight the NoiseChunk is not yet cached when the batcher tries to arm, so the chunk
falls into its own blocking per-chunk dispatch and parks a worker. Deepen the pool and it vanishes.
**If you ever see forge's MISSES climb again, check the in-flight limit before suspecting the seam.**

### Measured and REJECTED: chunkSystem.asyncSerialization
See the note in `chunksystem/common/Config.java`. Short version: it is not faster (1223.1 vs 1246.2)
and the second leg threw a ConcurrentModificationException out of feature placement at 99.57% then
stalled the last ~286 chunks for 59 minutes (1:00:10 vs 53 s). Keep it OFF on 1.20.1.

### Benchmarking forge
`~/mod_dev/sc-forge-test/sc_forge_bench.sh <label> <radius> [-Dargs] [key=value]` — a deliberate
mirror of neo's `sc_bench.sh` so a forge number is comparable to a neo number. Run a warm-up leg
first (this tree's OpenCL program cache is much colder than neo's) and match the harness: no pinned
`chunky.maxWorkingCount`, `-Xms8G`, ZGC, `sync-chunk-writes=false`, `max-tick-time=-1`, and
`-Dsuperchunk.gc.autoConfig=false` so GcAdvisory does not rewrite `user_jvm_args.txt` between legs.

## CAUGHT UP TO neo 0.3.0 (2026-08-15) — 0.2.0 + round 10 + mod compat + Distant Horizons

The tree was frozen at neo **0.1.0** (`03018dc`, 2026-07-18). This stage replays everything neo
landed since: the 0.2.0 worldgen/diagnostics snapshot, round 10's throughput work, the mod-compat
shims, and the DH generator integration. `mod_version` 0.1.0 → **0.3.0**.

**How the delta was taken.** 25 of the 35 touched files were still BYTE-IDENTICAL to neo `03018dc`,
so `git diff 03018dc HEAD` applied to them verbatim (`patch -p1`, zero rejects) — including all
three `.cl` kernels, which are now byte-identical to neo HEAD. Only 10 files had diverged
(`SuperChunk`, `SuperChunkMixinPlugin`, `SuperChunkConfig`, `PlayerLatency`, `CompactConsume`,
`GpuClimatePrefetch`, notickvd `NoTickSystem`/`PlayerNoTickLoader`,
`MixinOptionsExtendRenderDistance`, `LithiumMixinPlugin`); those were re-expressed onto forge's
shape rather than overwritten. **Use that identical-file check first next time** — it converts most
of a port into a patch application, and it tells you exactly which files hold real port divergence.

The 89 neo files absent here are the pre-0.1.0 deliberate exclusions (1.21-only Lithium rules,
neoforge plumbing, the dropped latencyMetrics/PlayerChunkSender work) — unchanged, still correct.

### What landed
- **Round 10 kernels** — `noise.cl` fp64 op-count reductions (`perlin_wrap` fast path,
  `mc_floor_frac`, wrap hoisting, `improved_noise_octave`), plus `aquifer.cl`/`dfc_support.cl`.
  Bit-exact by construction (neo verified by output hash in fp32 AND fp64 at 4/8/16 octaves).
- **`pregen.chunkyWorkingCount=auto`** (`SuperChunkConfig.applyChunky`) — Chunky's `GenerationTask`
  semaphore defaults to **50**, a hard cap on the whole pipeline. Worth ~nothing alone and the
  kernel work is worth slightly less than nothing alone (a faster kernel drains the batcher and
  shrinks batches); **together +6.3%** on neo. Ship them together or neither.
- **`BiomeQuartCache` + `MixinBiomeManagerQuartCache`** (+2.2% on neo), gated to `WorldGenRegion`
  sources only — that gate is correctness, not tuning (a `ServerLevel`'s manager is long-lived and
  `/fillbiome` rewrites biomes under it).
- 0.2.0 worldgen/diagnostics: carver + spring scratch reuse, `PostProcessNeighbourShapes`,
  fast-palette chunk IO, 7 placement accessors, `ChunkCensus`, `GcAdvisory`.
- Mod compat: `StructureLayoutOptimizerCompat`, `ByePregenCompat`, `BetterCavesCompat`,
  `PlainWorldgenRandomGate`, the blocked-jigsaw-skip lever (default OFF, with verify mode).
- Distant Horizons: `DhWorldGenBridge` + `DhMixinPlugin` + `DhC2meAccessor` + three seam mixins.

### Deliberate divergences from neo (do not "fix" these back)
1. **`player.sendAtChunkSending` stays `false`.** On 1.21.1 it fixes a real port defect
   (`ChunkHolder.getChunkToSend()` was never overridden). **1.20.1 has no `getChunkToSend` at all**
   — this port's notickvd module sends at FULL/CHUNK_SENDING unconditionally, so the flag is INERT
   and defaulting it true would only print an "INERT" line every boot. `PlayerLatency`'s static
   fallback was likewise kept at `false` so its warn text stays true.
2. **`c2me.chunkSystem.suppressGhostMushrooms` DOES default `true`** — and it matters MORE here than
   on neo for the same reason: neo needs it only when `sendAtChunkSending` is on, whereas on 1.20.1
   non-post-processed chunks are exposed to clients unconditionally (MC-276863).
3. **betterchunkloading incompatibility uses an impossible optional-dep version range.** Forge
   1.20.1's dependency schema has no `type = "incompatible"` (that is NeoForge/1.20.5+); an optional
   dep whose range nothing satisfies is the equivalent, and this port's own QA already proved an
   empty range hard-fails the boot when the mod is present. Verified before adding that the
   underlying C2ME tripwire (`chunksystem.mixin.MixinChunkHolder#failFastIncompatibility`) really is
   in this tree, so the block is earned rather than inherited on faith.
4. **`GcAdvisory` gates its JVM-flag write on `Runtime.version().feature() >= 21`.** A faithful port
   is actively destructive here: `-XX:+ZGenerational` is JDK 21 (JEP 439) and a Java 17 JVM handed
   it exits before `main()`. On 17-20 it logs a "needs-jvm" advisory instead of writing flags.
5. **`LithiumMixinPlugin` keeps `getModFileById` as a secondary probe** (neo replaced it outright)
   and keeps `canary` in `LITHIUM_EQUIVALENT_MODIDS` — narrowing to neo's `{lithium, radium}` would
   drop detection of the 1.20.1 Canary fork this port already pins in `mods.toml`.
6. **`@Pseudo` added to the three DH mixins.** Forge pins the **0.8.7** mixin AP (0.8.5 has two
   documented bugs — do not downgrade), and 0.8.7 VALIDATES string targets: without `@Pseudo` the
   build fails with "Mixin target ... could not be found" because DH is not on the compile
   classpath. `@Pseudo` is both the fix and the semantically correct annotation, and it is already
   this tree's idiom for third-party targets (`MixinSodiumRenderDistanceCap`). Neo does not need it
   only because NeoGradle's AP does not run that validator.

### Dropped, with the specific missing API
- **`MixinTACSCancelSending` (VMP player-watching) — NOT PORTABLE.** 1.20.1 has no
  `net.minecraft.server.level.ChunkTrackingView`; grepping the whole 1.20.1 source for
  `ChunkTrackingView` / `applyChunkTrackingView` / `setChunkTrackingView` / `onChunkReadyToSend`
  returns zero hits, so both members the neo class targets are untargetable. Independently
  re-confirmed by a second pass. This is a genuine capability gap, not a shortcut.
- **`compatibility.txt`** — not created. Neo's is a record of measurements taken on the 1.21.1
  build against 1.21.1-era mods; reproducing it here would be asserting numbers nobody measured on
  this tree. The per-mod reasoning lives in the compat classes' javadoc instead.
- **A 1.20.1-native `DhC2meAccessor`** (mixing DH 2.3.x's `ModChecker` so `isModLoaded("c2me")`
  answers true) — on DH 2.3.4-b that answer is NOT cosmetic: `BatchGenerationEnvironment`'s
  constructor sets `pullExistingChunkUsingMcAsyncMethod = true` on the hit, changing DH's chunk-pull
  path. Reporting-only parity with neo was preferred over silently altering DH behaviour.

### QA RESULTS (2026-08-15, production install `~/mod_dev/sc-forge-test`, Forge 1.20.1-47.3.0)
Real dedicated-server boots, RTX 3070 / driver 595.84, seed 8675309, Java 21 + Generational ZGC.

- **Boot: clean.** Zero errors, zero exceptions, zero mixin failures. GPU backend up (fp64,
  self-test PASS), decide chain ready, `decide faults=0` throughout.
- **All 14 new mixins confirmed APPLIED** (from `logs/debug.log` `Mixing X from Y into Z`),
  including the two that carried the most compiler-invisible risk:
  `MixinBiomeManagerQuartCache -> BiomeManager` and
  `MixinChunkSerializerFastPalette -> ChunkSerializer` (the retargeted 1.20.1 class).
  The three `WorldCarver` mixins co-apply with **no `@Unique` field conflict and no rename lines** —
  the pre-emptive `SUPERCHUNK$SCRATCH_ENABLED`/`SUPERCHUNK$CTX_ENABLED` split did its job.
  `MixinJigsawPlacementPlacerSkipBlocked` correctly does NOT apply (ships default-off);
  `client.MixinSodiumRenderDistanceCap` correctly does not apply on a dedicated server.
- **`applyChunky` verified on both paths**: with `-Dchunky.maxWorkingCount` on the command line it
  logs "already set on the command line — leaving it alone"; without it, "Chunky in-flight chunk
  limit -> 3072 (auto from a 16.0 GB max heap)" — the 192/GB formula, clamped, exactly as intended.
- **`GcAdvisory` round-trip verified**: under G1 it wrote the marked ZGC block into
  `user_jvm_args.txt`; the next boot came up on ZGC and it correctly reported "concurrent low-pause
  collector active … good for high render distance" instead of re-advising.
- **`BiomeQuartCache` verified LIVE and CORRECT**: `lookups=135,953,230 hits=119,176,014 (87.7%)`,
  **0 verify mismatches** with `-Dsuperchunk.worldgen.biomeQuartCache.verify=true`. Note the
  counters only print every `REPORT_EVERY = 1<<24` misses, so a small pregen produces NO line at
  all — a 4,225-chunk run looks identical to the cache being dead. Use r1536+ to observe it.
- **Chunk-serializer round-trip verified**: a world generated by 0.3.0 restarts and reloads clean,
  4,225 chunks re-read from disk in 0:00:00, zero corruption/recovery messages.

#### The parity gate: the port is BIT-NEUTRAL, and the pre-existing fp32 flip rate is real
`-Dsuperchunk.gpu.compactIds=verify`, r1536:
- At 4,096 chunks / 401,664,914 blocks: **ZERO divergence**, flip census all-zero.
- At 32,768 chunks / **3,212,862,878 blocks**: 20 mismatches, and a flip census of
  `0=32759 1-2=9`, `veinClass=2` on a gate that wants 0.

**That is NOT a port regression.** The stashed 0.1.0 jar was re-run on the same seed and radius and
produced a **byte-for-byte identical deviation fingerprint** — same total (20), same per-class
breakdown (block=9 heightmapSurface=1 heightmapFloor=2 nonEmptyCount=4 fluidCount=3
postprocessExtra=1), same 9 flip-chunks, same transition histogram
(`stone->water=1 air->stone=4 water->stone=2 granite->stone=1 tuff->vein=1`), same `veinClass=2`.
Reproducing the old build flip-for-flip over 3.2 billion blocks is strong positive evidence that the
round-10 kernel port is bit-neutral, as its commit claims.

**But it also means the shipped gate was under-sampled, and this affects neo identically.** The
README's "zero flips over 455.9M blocks" does not hold at 3.2B: the fp32 decide path
(`gpu.decideFp32=true`, and `compactIds` defaults to `on`) has a measurable flip rate of roughly
1 per 350M blocks, including 2 hits on the `veinClass` HARD gate. Users run this by default.
Worth re-deciding upstream — either re-baseline the claim at 3B+ blocks, or reconsider the default.

### FIXED HERE 2026-08-15, STILL OPEN IN NEO — chunk-IO blender scan self-deadlocked the worker pool

**Fix:** in `C2MEStorageThread`, a read with `scanner != null` is now parsed INLINE on the storage
thread instead of being handed to `GlobalExecutors.prioritizedScheduler`. All three scan sites were
affected (cached-CompoundTag, cached-byte[], and the disk read in `scheduleChunkRead`). Non-scan
reads keep the prioritized scheduler — nothing blocks on those, so their scheduling is unchanged.
Inline is the correct home: the disk read already happens on that thread, scanners are
field-selective and cheap (`CollectFields` short-circuits), and vanilla's own `IOWorker` parses
scans on its IO thread too.

**Neo carried the identical defect and is FIXED TOO** (2026-08-15, same three scan sites, adapted
for 1.21.1's `NbtAccounter` argument). Both trees now have 3 scan sites inline and 3 non-scan
`prioritizedScheduler` calls remaining — keep them in sync.

**Benchmarked on neo before/after (the concern being that serialising scans onto ~6 storage threads
instead of the 22-worker pool could cost throughput). It does not:**
- r2048 fresh world, interleaved, 2 pairs + warm-up legs, steady-state 20-90% (the valid metric for
  a uniform-progress pregen): control 1288.0 cps, fixed 1292.2 cps, **+0.33%**.
- Scan-heavy (`sc_blend_ab.sh`, new: seeds a world once at r1024 and restores it per leg, then
  expands to r2048 so new chunks generate inside regions that already hold real chunk NBT — a
  fresh-world pregen barely touches the parse at all, since a chunk absent from disk
  short-circuits before it). 4 pairs + warm-ups, total wall on identical work: control 183 s,
  fixed 185 s, **-1.08%** — two seconds across eight legs against ±1 s per-leg quantization.
  All legs `faults=0 oom=0`, no SIGSEGV.

**Do NOT use `sc_rate.sh`'s 20-90% steady-state window on the blend scenario.** That heuristic
assumes uniform progress. An expansion pregen re-reads the seeded core first (measured: 9240 cps at
1.4% decaying to ~1250 cps at the end), so the percentage window spans a shifting mixture of
instant re-reads and real generation — it swung 11-16% WITHIN the same jar there while wall times
were identical. On the blend scenario compare total wall from an identical restored world; on a
fresh-world pregen use sc_rate.sh as usual.

Consistent with the arithmetic: the blender scan is cached per REGION
(`IOWorker.regionCacheForBlender`), so the whole job is ~16 regions x <=1024 field-selective parses
whichever pool runs it — sub-1% of a 45 s leg.

Validated: r256 fresh pregen, then expand to r1024 so new chunks generate inside regions that
already hold on-disk data — i.e. the scan path actually executes against real chunk NBT. 16,641
chunks in 18 s (924 cps), server responsive throughout, zero errors. Caveat: **the original
deadlock was not reproduced**, since it needs a mod force-loading chunks synchronously from a tick
event; this validates that the fix does not regress normal operation and removes the cycle by
construction, not that the client hang is gone.

Incidental finding while testing: swapping mod jars whose `.cl` kernels differ (0.1.0 vs 0.3.0) in
one install left the disk program cache serving stale binaries — `CL_INVALID_KERNEL_NAME (-46)` at
boot then ~4.8k `CL_OUT_OF_RESOURCES (-5)`, all fail-soft to CPU. Deleting
`config/superchunk-gpu-cache/` cleared it completely. One data point, but if the cache key is meant
to be content-hashed it should not have collided across kernel revisions — worth a look.

#### Original analysis (pre-existing, affects neo TOO) — the deadlock cycle
Found 2026-08-15 on a real 110-mod Forge 1.20.1 client (CurseForge `Custom-Modpack`). **Not
introduced by this port** — `C2MEStorageThread.java` / `C2MEStorageVanillaInterface.java` are
untouched by the 0.1.0→0.3.0 delta, and neo carries the identical
`GlobalExecutors.prioritizedScheduler.executor(16)` call.

The cycle:
1. A mod calls `ServerLevel.getChunk(...)` synchronously from the server tick (here: SculkHorde's
   `Gravemind.serverTick` → `BlockEntityChunkLoaderHelper.forceLoadChunk`). The server thread parks
   in `ServerChunkCache.getChunk` → `managedBlock`.
2. That chunk generates on a `c2me-worker`, reaches `Blender.of()` → `ChunkStorage.isOldChunkAround`
   → vanilla `IOWorker.isOldChunkAround`, which **blocks on `.join()`**.
3. The scan is served by `C2MEStorageVanillaInterface.scanChunk` → `C2MEStorageThread.getChunkData`
   → `scheduleChunkRead`, which submits the NBT parse to
   `GlobalExecutors.prioritizedScheduler.executor(16)`.
4. `prioritizedScheduler` is null-and-@Overwrite-routed to C2ME's scheduler when lighting is
   externally managed — i.e. **it is the same `c2me-worker` pool**. Every worker is already blocked
   at step 2, so the parse never runs and the join never returns.

Evidence from the ModernFix watchdog thread dumps (ticks of 40s → 80s → 120s → 160s → 201s, all the
SAME tick): **170 `c2me-worker` threads, 170 frames blocked in `IOWorker.isOldChunkAround`** — 100%
of the pool — zero `scalablelux-*` threads, all 6 `C2ME Storage` threads idle in `sync.wait()`
(correctly: they already dispatched the parse), and all 92 commonPool / 90 Worker-Main threads idle.

**Why the dedicated-server QA missed it:** a Chunky pregen never force-loads synchronously from the
tick, and a fresh world has no old chunks to blend, so `Blender.of()` never occupies the pool.

**Workaround (applied on that instance):** `c2me.ioSystem.replaceImpl=false` in
`config/superchunk.properties` — disables ONLY the chunk-IO rewrite, so vanilla's `IOWorker`
services blender scans on its own thread. Keeps the chunk system and all worldgen optimizations.
Broader hammer: `-Dsuperchunk.chunksystem.disable=true`.

**Real fix (not attempted):** `scheduleChunkRead` must not schedule onto a pool that its own callers
block on — either give the NBT parse its own executor, or make `isOldChunkAround` non-blocking.
Fixing it in neo first is the right order, since neo is the source of truth.

### STILL NOT VERIFIED
- **Distant Horizons — completely untested.** No DH jar is installed in the test harness, so neither
  the 3.x nor the 2.3.x seam has ever had a live Mixin applied. This remains the port's largest
  unvalidated surface. **Verify:** boot a client with DH 2.3.4-b, `/dh pregen`, confirm
  `DhWorldGenBridge.logSummary` reports a nonzero tally.
- **The Sodium/Embeddium render-distance cap** — client-only, never loads on a dedicated server.
- **No throughput number was taken, deliberately.** Other agent sessions were running their own
  Forge servers on this box during the run, and this tree's own bench notes are explicit that
  wall-clock on a contended box is meaningless. Re-run `sc_ab.sh`-style interleaved legs on a quiet
  box against `build/libs/superchunk-0.1.0*.jar` (retained) before quoting any 1.20.1 figure.

### Original pre-QA caveats (kept for context)
The build is green (`./gradlew build --rerun-tasks`, full rebuild per this file's own warning about
false-green incremental compiles) and the refmap carries SRG entries for every new
Minecraft-targeting mixin (the only two without are the `remap = false` third-party ones, correctly).
Outstanding at the time of writing:
- **DH is a different API generation on this MC line.** Neo targets DH **3.2.0-b**; the 1.20.1 line
  is **2.3.x**, so the port carries seams for both: `MixinDhWorldGenStep` (3.x
  `AbstractWorldGenStep`) plus `MixinDhStepBiomes`/`MixinDhStepNoise` (the 2.3.x `StepBiomes`/
  `StepNoise` `@Redirect` shape). The 2.3.x seams are BYTECODE-verified against a real DH 2.3.4-b
  jar but have never had a live Mixin applied to them. **Verify:** boot a client with DH 2.3.4-b,
  run `/dh pregen`, confirm the seam engages and `DhWorldGenBridge.logSummary` reports a nonzero
  tally. DH 2.3.4-b is client-side-only, so this only ever runs on an integrated server.
- Production (SRG) boot of the new mixins — a dev run does not exercise the refmap.
- The Sodium cap targets **Embeddium 0.3.31+mc1.20.1** (`SodiumGameOptionPages`), not neo's
  `SodiumConfigBuilder`, which does not exist on this line. Untested against a real client.
- No benchmark was run. Neo's +12.6% round-10 figure is a 1.21.1 number on an RTX 3070; treat the
  1.20.1 throughput as unmeasured until a Chunky r2048 leg is run against the old 0.1.0 jar
  (`build/libs/superchunk-0.1.0*.jar` is retained for exactly that A/B).

### Upstream (neo) bugs found while porting — fix them THERE
- **`LithiumMixinPlugin` static-init order.** Neo declares `STANDALONE_LITHIUM =
  detectStandaloneLithium()` ABOVE `LITHIUM_EQUIVALENT_MODIDS`, so the detector reads the field
  before it is initialised. Forge's ordering is already correct.
- ~~**`BiomeQuartCache.encode(8,0,0,0)` collides with the `UNCACHEABLE` sentinel**~~ (fixed in both trees 2026-10-09) (`8L<<60` ==
  `Long.MIN_VALUE`), so quart cell (0,0,0) is silently never cached for whichever manager draws
  generation 8. Inherited verbatim here rather than divergently patched.
- ~~`GcAdvisory` "Exact flags written" claim~~: the file IS always written; the false part was its text saying servers are auto-configured (opt-in). Fixed in both trees 2026-10-09.

## Stage 2 — worldgen GPU offload (this stage)

### Vanilla API delta (1.20.1 vs 1.21.1), measured via javap signature diff
Signature-diffed (mojmap, Forge recomp jar vs neoform jar): DensityFunction(s), NoiseChunk,
NoiseRouter, RandomState, Aquifer, NoiseBasedChunkGenerator, Beardifier, OreVeinifier,
NoiseGeneratorSettings, Climate, CubicSpline, BlendedNoise, NormalNoise/PerlinNoise/ImprovedNoise,
MaterialRuleList, ChunkAccess, Blender, NoiseSettings, SurfaceSystem, ChunkMap.

**The port surface is nearly signature-identical.** Real deltas only:

1. `NoiseBasedChunkGenerator.fillFromNoise` / `createBiomes` take an extra LEADING
   `Executor` param on 1.20.1 (1.21.x dropped it). Mixin @Injects + call sites adapt.
2. `DensityFunctions.bootstrap/register` use `Codec` on 1.20.1 vs `MapCodec` on 1.21.1
   (only matters if ported code touches DF codecs — the AST layer does not).
3. `Beardifier.getBuryContribution(int,int,int)` on 1.20.1 vs `(double,double,double)` on
   1.21.1 — the beard MATH changed upstream. NOT parity-relevant for the GPU: beard blocks
   are CPU-decided via the beard-AABB sentinel (see ScBeardBoxAccess); only
   MixinStructureWeightSampler's box-union/reach must match 1.20.1 semantics.
4. `ChunkMap` internals differ heavily (1.21 chunk-system refactor) — only the debug
   climate-dim-label mixin touches it; adapt or drop.
5. NO `val$…` anonymous-class reflection anywhere in the neo mod (checked) — the capture-name
   differences between the decompiles are irrelevant.
6. Noise synth classes (ImprovedNoise/PerlinNoise/NormalNoise/BlendedNoise), CubicSpline,
   Climate, NoiseChunk, Aquifer, OreVeinifier, RandomState, NoiseRouter: signature-identical →
   kernels + AST port 1:1; in-JVM lockstep parity tests remain the truth source.

### Cross-module deps the offload pulls in (beyond opts/dfc + gpu/**)
- `c2me.base.common.config.ConfigSystem` (config infra) — port as-is.
- Accessor mixins: `IChunkNoiseSampler`, `IDensityFunctionTypesWeirdScaledSamplerRarityValueMapper`,
  `IChunkAccessNoiseChunk`, `IXoroshiro128PlusPlusRandomDeriver` — tiny, port as-is.
- `flowsched.util.Assertions` — tiny, port as-is.
- `c2me.rewrites.chunksystem.common.structs.ChunkSystemExecutors` — used ONLY as
  `observeOn(backgroundScheduler)` in GpuClimatePrefetch/GpuBatchPrefetch. Replace with a
  mod-owned background scheduler shim (no C2ME chunk system on 1.20.1 vanilla).
- Lithium `BlockCountingSection`/`BlockStateFlags`/`TrackedBlockStatePredicate` — used by
  CompactConsume (+census). Port decision recorded at the CompactConsume port.
- Batch entry point: neo hooks C2ME's `VanillaWorldGenerationDelegate`; on 1.20.1 vanilla the
  equivalent seam is `NoiseBasedChunkGenerator.fillFromNoise` (worker threads call it).

### LWJGL scheme (server-LWJGL milestone resolution)
Three-pronged, chosen because ForgeGradle 6 requests the NONEXISTENT `lwjgl-opencl:natives-<os>`
classifier (hardcoded Maven Central lookup) for every org.lwjgl-group module on a run classpath:
1. COMPILE — `compileOnly` real modules (never reach the run classpath).
2. DEV RUNS — the same jars as `files()` deps from `./libs` (no module group → FG's LWJGL
   machinery bypassed; natives-linux jar rides the classpath, LWJGL extracts the .so itself).
3. PRODUCTION — RESOLVED 2026-07-17 as the SINGLE-JAR SHELL (mirror of neo's design):
   the distributed `superchunk-<ver>-shell.jar` = `SuperChunkBootstrapLocator`
   (IDependencyLocator, top-level service file injected at jar-build time only) + stashed
   `superchunk-core.jar` (the reobf'd -all artifact) + lwjgl core. FML hoists the shell to the
   SERVICE layer (fmlloader 47.3 `ModDirTransformerDiscoverer` SERVICES set includes
   IDependencyLocator; `ModsFolderLocator` excludes via `allExcluded()`); the locator
   contributes the EXTRACTED core (`.superchunk-bootstrap/`, content-hashed) as the mod on both
   dists + lwjgl core (module org.lwjgl) on DEDICATED SERVERS only. Loader facts (bytecode-
   verified): dependency loop snapshots per-turn (`List.copyOf` at each locator's turn, so the
   BOOT-layer JarInJar locator running after the SERVICE-layer shell picks up the core's nested
   libs — confirmed empirically on the production install); mod module name = first modid;
   lwjgl claims LIBRARY via `getDefaultJarModType()`. The shell excludes every package but
   `dev/superchunk/loader` (two layers exporting the mod packages = ResolutionException, the
   neo lesson). VALIDATED on a real Forge 1.20.1-47.3.0 install (~/sc-forge-test).

### RESOLVED (dist milestone) — production mixin refmap was missing from the configs
The real production-QA bug: NONE of the five mixin configs declared `"refmap"`, so a production
(SRG) boot failed every injection with "No refMap loaded" (`fillBiomesFromNoise` etc. unmapped).
The AP had always been generating a correct `superchunk.refmap.json` into the jar — it was just
never referenced. Fix: `"refmap": "superchunk.refmap.json"` added to all five configs (they share
the one sourceset refmap). Dev runs still boot (the "could not be read ... development
environment" WARN is the documented benign dev message). With the refmap wired, the production
boot also PROVES the @Shadow SRG story end-to-end (mixin fails loudly otherwise): validated —
production server, all mixins applied, GPU decide chain live, verify gate ZERO divergence over
101M blocks, flip census clean.

### Radium/Canary (1.20.1 Lithium forks) interop — `dev.superchunk.compat.LithiumBlockTracking`
Forge mirror of neo's bridge. The 1.20.1-era forks have NO `lithium$trackBlockStateChange`
(that is the modern-Lithium surface); their counting lives in mixin-merged PRIVATE members of
`LevelChunkSection`: `short[] countsByFlag` + static `addToFlagCount(short[], BlockState, int)`
(radium-0.12.4 + canary-0.3.3, bytecode-identical structure). The bridge probes new-style first,
then binds those (EXTERNAL_COUNTS mode); trackBulk = addToFlagCount(new,+n)/(prev,-n) — their
own bulk primitive with their own inject's deltas. Null counts array = skip (their lazy client
init recounts from the palette, self-healing). Interface present but unbindable → UNSAFE mode:
CompactConsume falls back to the original loop (vanilla setBlockState path). VALIDATED with
radium-0.12.4 on the production install: EXTERNAL_COUNTS bound (4 tracked flags), verify gate
zero divergence incl. lithiumFlags=0, ON-mode 1024 chunks consumed faults=0.

### Dev-run classpath rules (FG6) — bit hard-won
- Third-party libs the MOD code touches at runtime must be on **`minecraftLibrary`** (game
  layer). Plain `implementation`/`runtimeOnly` deps are boot-side only in FG6 dev runs →
  `NoClassDefFoundError` from the mod classloader. Hit twice: org.lwjgl (files() form to
  also dodge the natives bug) and io.reactivex (rxjava + reactive-streams; module form fine).
- `BytecodeGen` must emit `Opcodes.V17` (dev server runs Java 17; neo emits V21 →
  `UnsupportedClassVersionError: DfcCompiled_0`).
- `-Dsuperchunk.*` flags are forwarded from the gradle invocation into run JVMs (see the
  `runs.configureEach` block).

### Live-batch warm-up race (observation, run 6)
The cross-chunk batcher arms only when the FUSED interpolator registers; a COLD fused-kernel
compile (~44 s on driver 595) can outlast spawn generation, so a first-ever boot can show
`prefetched=0` on the batch consumer while everything else works. Warm CLProgramCache →
registration in ms → seam active during spawn gen. Not a port defect; same structure as neo.

### Vanilla-chunk-system batch seams (replaces C2ME delegate seams)
`MixinNoiseBasedChunkGeneratorGpuSeam` (HEAD-cancellable, ThreadLocal reentry guards) +
`VanillaGenSeam` (CompletableFuture↔Completable bridge; wrap-declined identity check = zero
overhead vanilla path; step errors mirrored; cancellation disposes → evicts pending deposits).
`GpuClimatePrefetch.wrapBiomes` signature changed to (BiomeSource, RandomState, chunk, step) —
1.20.1 has no WorldGenContext. `ChunkSystemExecutors` + Lithium `BlockStateFlags` are port
shims (see the files' headers).

### RESOLVED — mixin AP 0.8.5 was silently+loudly broken; use the 0.8.7 PROCESSOR
Two distinct 0.8.5 AP failures (runtime mixin stays Forge's bundled 0.8.5 — only the
`annotationProcessor` artifact is 0.8.7):
1. SILENT: @Shadow members produced NO refmap entries and NO outTsrgFile extra-mappings →
   production (SRG) @Shadow resolution would break. With 0.8.7 the refmap carries field
   entries (78 f_ refs) AND `build/tmp/compileJava/compileJava-mappings.tsrg` is emitted
   (82 shadow renames incl. `this$0 f_188827_`) for MixinGradle's reobf wiring. Verify once
   at dist time that reobfJar actually consumes it.
2. CRASH: with string-target (ASM-resolved) mixins, 0.8.5's `TypeHandleASM.findField` feeds
   FIELD descriptors to the method-descriptor parser → `StringIndexOutOfBoundsException:
   <desc length>` (hit via MixinChunkNoiseSampler1's `@Shadow this$0` on the anonymous
   `NoiseChunk$1`, desc length 47). Fixed in 0.8.7. Do NOT downgrade the AP.

CAUTION for later port stages: incremental compiles repeatedly reported green while full
`--rerun-tasks` rebuilds surfaced real errors (stale-jar/AP-skip artifacts). Trust only a
full rebuild after build-script/AT/AP changes.

### Source jars used for the diff
- 1.20.1: `~/.gradle/caches/forge_gradle/minecraft_user_repo/.../forge-1.20.1-47.4.0_mapped_official_1.20.1-{sources,recomp}.jar`
- 1.21.1: newest `~/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_*_output.jar`

## FULL PORT COMPLETE (2026-07-18) — all neo modules except merged-Lithium

Four implementation waves off three research plans (`portplans/*.md`, each with an
"implementation deviations" appendix — read those before touching ported code):
- **Wave A**: ScalableLux/Starlight (33) + flowsched executor/structs + all 14 round-5
  worldgen-opt mixins (parity arguments verified bit-identical on 1.20.1) + noisium (3).
- **Wave B**: c2me base remainder (77) + flowsched scheduler + opts/{allocs,math,natives-math,
  scheduling,chunkio,worldgen-general} + fixes/* + server-utils + threading-lighting (~205).
- **Wave C**: rewrites/{chunk-serializer,chunkio,chunk-system} (78) — the 1.21-only classes
  (ChunkPyramid/ChunkStep/ChunkResult/...) re-derived onto 1.20.1 equivalents; Forge events
  preserved (fireChunkTicketLevelUpdated re-fired in the updateChunkScheduling overwrite,
  ChunkEvent.Load/Unload via LifecycleEventInvoker, ChunkDataEvent.Save documented-dropped on
  the gc-free serializer which ships default-OFF). Master kill-switch:
  `-Dsuperchunk.chunksystem.disable=true` -> pure vanilla-chunk-system build.
- **Wave D**: VMP (1.19/1.20 upstream shape, area maps + updateChunkTracking listeners) +
  notickvd (chunk-system-aware with vanilla level-33-ticket fallback) + predictive gen +
  priorityBias (re-enabled — Wave C restored its scheduler dependency) + SimpleChannel
  `c2me:ext_render_distance_v1`. Dropped with reasons in-plan: latencyMetrics,
  fullSpeedLoading sender pins (1.20.1 has no PlayerChunkSender to unthrottle).

Boot-gate fixes the waves needed (runtime mixin 0.8.5 realities): NBT anonymous-reader
handlers must be non-static on 1.20.1 (`ListTag$1`/`CompoundTag$1` load is instance);
mid-constructor @Inject is rejected -> c2me commands moved to RegisterCommandsEvent, VMP's
watching-manager ctor hook moved to TAIL with a null-guarded setViewDistance RETURN hook.

**KNOWN-TRANSIENT**: ~2-7 per 66k chunk saves under pregen unload pressure hit a
ConcurrentModificationException while the C2ME storage thread serializes the root chunk tag
(some party mutates the handed-off CompoundTag briefly; mutator not yet identified — the
diagnostic logs storage + root keys per occurrence). Mitigated with a bounded retry
(3 attempts, 100ms) in C2MEStorageThread — retries observed 4/4 successful, zero lost
chunks. Root-cause hunt is a follow-up; grep logs for "retrying" + compare keysets.

**Production QA (real Forge 1.20.1-47.3.0 install, ~/sc-forge-test):** all-modules boot
Done ~4-5s; r300+r2048 pregens clean; compactIds=verify ZERO divergence over 100.6M blocks
(flip census clean); Radium 0.12.4 alongside: bridge EXTERNAL_COUNTS bound, zero divergence
incl. lithiumFlags, overlap pins land in config/lithium.properties (radium reads that same
file); chunk-system kill-switch verified; save -> restart -> reload clean ×2.

**FIRST FORGE BENCHMARK** (r2048 = 66,049 chunks, seed 8675309, same box as the neo numbers,
ambient desktop load): stage-2 jar (vanilla chunk system + GPU) = 10:03 = **109.5 cps** —
the vanilla 1.20.1 scheduler was the wall all along. Full port = 1:04 = **1,032 cps**
(best; 1:12-1:15 = 880-917 cps on traced/retry runs) -> **~9.4x** the stage-2 baseline and
in the neo build's class (neo quiet-box: 815 CPU-only / 1,119.5 peak). Shippable artifact:
`build/libs/superchunk-0.1.0-shell.jar`.

**NOT ported**: merged-Lithium (526 files) — superseded by the Radium/Canary runtime bridge
(dev.superchunk.compat.LithiumBlockTracking); install Radium alongside for lithium-class
game-logic opts. Chunk-serializer gc-free mode ships default-OFF (corruption-risk class).

## MERGED LITHIUM PORTED (2026-07-18) — the port is now module-complete

Plan: `portplans/lithium.md` (+ L1/L2/L3 deviation appendixes). 218 main + 2 client mixin
entries across superchunk-lithium.mixins.json + superchunk-lithium-forge.mixins.json
(~460 java files); radium-0.12.4 bytecode was the 1.20.1 authority for every unclear shape.
Pruned with reasons in-plan: the five C2ME-pinned rule families, 1.21-only rules
(crafter, PathfindingContext, enchantment_ticking, ...), orphans. mixin.block.hopper ships
default-OFF (radium's posture). Bundled defaults live at assets/superchunk/ (assets/lithium/
would collide with an installed Radium reading the same path).

Runtime-mixin-0.8.5 lessons added this round (see also the wave fixes above): interface
mixins support NO injectors at all (@Redirect -> class mixin on the implementor
soft-overriding the default method, per radium's own shape — see
entity/collisions/intersection/LevelMixin.java) and require public members (`default` for
instance helpers); synthetic-lambda @Shadow aliases DO NOT resolve in interface mixins —
inline the vanilla lambda body ([VanillaCopy]) instead.

FORK STANDDOWN: LithiumMixinPlugin defers ALL bundled-lithium configs when modid lithium/
radium/canary is installed (LoadingModList + class-presence fallbacks; one boot line naming
the probe). mods.toml carries mandatory=false ordering="BEFORE" pins for all three — NOTE:
Forge 1.20.1 REQUIRES versionRange="[0,)" on optional deps (an omitted range is EMPTY and
hard-fails the boot when the mod IS present; found in QA, not at review).
`-Dsuperchunk.lithium.disable=true` force-disables the bundled module regardless.

QA (production install): lithium-live boot + r300 pregen + soak clean; radium-alongside =
standdown line + bridge EXTERNAL_COUNTS + zero errors; BUNDLED counting
(lithium.util.block_tracking=true) = bridge BUILTIN, compactIds=verify ZERO divergence incl.
lithiumFlags; save/restart/reload clean; r2048 = 66,049 chunks in 1:10 = 943.6 cps with full
Lithium live (pre-lithium band: 880-1,032) — no regression. The LithiumBlockTracking mode
matrix is now fully production-validated: OFF / BUILTIN / EXTERNAL_COUNTS (+UNSAFE by design).
