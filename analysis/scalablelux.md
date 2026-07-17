# ScalableLux (NeoForge port) — Mixin Surface Analysis

Source analyzed: `upstream/ScalableLux`
Date: 2026-06-22

## 0. Repo structure / important caveat

The clone is a **patch-based fork** (Paper-style), not a flat source tree:

```
ScalableLux/                      <- patch-driver repo (build.sh, patches/, .gitmodules)
├── ScalableLux/                  <- submodule = the actual Starlight FABRIC upstream source (UNPATCHED on disk)
│   └── src/main/java/ca/spottedleaf/starlight/...
│   └── src/main/resources/       (fabric.mod.json, scalablelux.mixins.json, scalablelux.accesswidener)
├── FlowSched/                    <- submodule = com.ishland.flowsched executor library
├── patches/main/
│   ├── 0001-Revert-feat-hardforking.patch
│   └── 0002-Port-to-NeoForge.patch    <- THE NeoForge port (build files + a few source edits)
└── current-upstream  -> tag-485ddc18ebd5131d5d8088b020e980be8c37cef9
```

**The checked-out `ScalableLux/ScalableLux/src` is the Fabric upstream.** The NeoForge port is produced at build time by applying `patches/main/0002-Port-to-NeoForge.patch`. That patch changes ONLY:
build.gradle / gradle.properties / settings.gradle / gradle-wrapper, the mod entrypoint, `Config.java`, lighting source (`BlockStarLightEngine`, `StarLightEngine` — to use NeoForge's `getLightEmission(Level,BlockPos)`), `BlockStateBaseMixin` (adds a `scalablelux$actuallyDynamicLightEmission` duck method), replaces `fabric.mod.json` with `META-INF/neoforge.mods.toml`, and adds the accesswidener via Architectury Loom's `atAccessWideners`.

**It does NOT add, remove, or retarget any `@Mixin`.** The mixin config list and every `@Mixin(...)` target are identical between Fabric and the NeoForge port. Everything in sections 3-7 below is therefore valid for the NeoForge build, with the BlockStateBase duck addition noted inline.

---

## 1. Build / loader facts (post-NeoForge-port, from patch 0002)

| Fact | Value | Source |
|---|---|---|
| Build tool | Gradle **8.12** (`gradle-8.12-all.zip`) | patch 0002 → gradle-wrapper.properties |
| Loom plugin | **Architectury Loom** `dev.architectury.loom` `1.9-SNAPSHOT` (was `fabric-loom 1.7` on Fabric) | patch 0002 → build.gradle |
| Other plugins | `maven-publish`, `com.github.johnrengelman.shadow 8.1.1` | build.gradle |
| `loom.platform` | `neoforge` | patch 0002 → gradle.properties |
| Minecraft version | **1.21.1** | gradle.properties (`minecraft_version=1.21.1`) |
| NeoForge version | **21.1.92** | patch 0002 → gradle.properties (`forge_version=21.1.92`), dep `net.neoforged:neoforge:${forge_version}` |
| Mappings | **Official Mojang mappings** (`loom.officialMojangMappings()`; Yarn line commented out) | build.gradle |
| Java | **21** (source & target VERSION_21; was 17 on Fabric) | patch 0002 → build.gradle |
| Mixin compatibilityLevel | `JAVA_17` (in mixins.json, not bumped) | scalablelux.mixins.json |
| Mod id / version | `scalablelux`, `0.1.0.1` + `+neoforge.<gitcommit>` | gradle.properties / build.gradle |
| Mod loader entry | `modLoader="javafml"`, `loaderVersion="*"` | patch 0002 → neoforge.mods.toml |
| FlowSched dep | `com.ishland.flowsched:flowsched`, shadow-bundled (`shadowInclude … transitive false`) + `forgeRuntimeLibrary implementation` | build.gradle |
| Access widener wiring | `remapJar { atAccessWideners.add("scalablelux.accesswidener") }` (converted to NeoForge AT at remap) | patch 0002 → build.gradle |
| MixinExtras | used (`com.llamalad7.mixinextras.injector.wrapoperation.*` in ThreadedLevelLightEngineMixin) — bundled by Loom | source |

Maven repos added for NeoForge: `https://maven.neoforged.net/releases/`, `https://maven.architectury.dev/`.

The mod **`provides: starlight`** and **`breaks: phosphor`** (Fabric metadata; the NeoForge toml drops these but keeps the same intent).

---

## 2. Source layout

Base package `ca.spottedleaf.starlight`:

- **`common.light`** — the new (Starlight) lighting engine, the wholesale replacement:
  - `StarLightEngine` (abstract, 1573 lines) — core SWMR/"stateless" propagation engine
  - `SkyStarLightEngine` (extends StarLightEngine) — sky light
  - `BlockStarLightEngine` (extends StarLightEngine) — block light
  - `StarLightInterface` (763 lines) — per-Level facade; owns the `LightQueue` (inner class) + `ChunkTasks`; block/sky reader views; parallel scheduling entry (`propagateChanges` / `schedulePropagation0`)
  - `StarLightLightingProvider` — duck interface implemented onto vanilla `LevelLightEngine` (see §7)
  - `SWMRNibbleArray` — single-writer-multiple-reader nibble (light) storage (replaces vanilla `DataLayer` storage), with `SaveState` inner class
- **`common.thread`** — parallel-update plumbing (FlowSched glue): `GlobalExecutors`, `SchedulingUtil`, `SimpleTask`, `LockTokenImpl`
- **`common.config`** — `Config` (reads `scalablelux.properties`)
- **`common.chunk` / `common.world` / `common.blockstate`** — duck interfaces (`ExtendedChunk`, `ExtendedWorld`, `ExtendedAbstractBlockState`)
- **`common.util`** — `CoordinateUtils`, `IntegerUtil`, `SaveUtil`, `WorldUtil`
- **`common.ScalableLuxEntrypoint`** — `@Mod("scalablelux")` (NeoForge) / `ModInitializer` (Fabric)
- **`mixin.*`** — all mixins (see §3/§4)

FlowSched library lives at `FlowSched/src/main/java/com/ishland/flowsched/` (packages `executor`, `scheduler`, `structs`, `util`). ScalableLux only uses the **`executor`** subset: `ExecutorManager`, `Task`, `LockToken`, `SimpleTask`, `WorkerThread`, `DynamicPriorityQueue`.

---

## 3. Mixin config — `scalablelux.mixins.json`

```
required: true,  minVersion: 0.8,  compatibilityLevel: JAVA_17
package: ca.spottedleaf.starlight.mixin
injectors.defaultRequire: 1
```

**`mixins` (common, both sides):**
| entry | class |
|---|---|
| common.blockstate.BlockStateBaseMixin | mixin/common/blockstate/BlockStateBaseMixin |
| common.chunk.ChunkAccessMixin | mixin/common/chunk/ChunkAccessMixin |
| common.chunk.EmptyLevelChunkMixin | mixin/common/chunk/EmptyLevelChunkMixin |
| common.chunk.ImposterProtoChunkMixin | mixin/common/chunk/ImposterProtoChunkMixin |
| common.chunk.LevelChunkMixin | mixin/common/chunk/LevelChunkMixin |
| common.chunk.ProtoChunkMixin | mixin/common/chunk/ProtoChunkMixin |
| common.lightengine.LevelLightEngineMixin | mixin/common/lightengine/LevelLightEngineMixin |
| common.lightengine.ThreadedLevelLightEngineMixin | mixin/common/lightengine/ThreadedLevelLightEngineMixin |
| common.world.ChunkSerializerMixin | mixin/common/world/ChunkSerializerMixin |
| common.world.LevelMixin | mixin/common/world/LevelMixin |
| common.world.ServerWorldMixin | mixin/common/world/ServerWorldMixin |
| common.world.WorldGenRegionMixin | mixin/common/world/WorldGenRegionMixin |

**`client` (client-only):**
| entry | class |
|---|---|
| client.multiplayer.ClientPacketListenerMixin | mixin/client/multiplayer/ClientPacketListenerMixin |
| client.world.ClientLevelMixin | mixin/client/world/ClientLevelMixin |

14 mixin classes total. No `server` block; all `client` entries are client-side.

---

## 4. MIXIN TARGET TABLE (all 14)

Targets are fully-qualified `net.minecraft.*` (Mojang mapped). "Heaviest" = most invasive injector on that target.

### Lighting engine (the heart)

**LevelLightEngineMixin** → `net.minecraft.world.level.lighting.LevelLightEngine`  *(implements `LightEventListener`, `StarLightLightingProvider`)*
- `@Inject(method="<init>", at=TAIL)` `construct(...)` — builds the `StarLightInterface`, then **nulls vanilla `blockEngine`/`skyEngine`** (intentionally destroys vanilla light engine state)
- `@Overwrite` `checkBlock(BlockPos)` → StarLight blockChange
- `@Overwrite` `hasLightWork()` → StarLight hasUpdates
- `@Overwrite` `runLightUpdates()` → StarLight propagateChanges
- `@Overwrite` `updateSectionStatus(SectionPos, boolean)`
- `@Overwrite` `setLightEnabled(ChunkPos, boolean)`
- `@Overwrite` `propagateLightSources(ChunkPos)` (no-op)
- `@Overwrite` `getLayerListener(LightLayer)` → StarLight block/sky reader
- `@Overwrite` `queueSectionData(LightLayer, SectionPos, DataLayer)` (no-op)
- `@Overwrite` `getDebugData(LightLayer, SectionPos)`
- `@Overwrite` `getDebugSectionType(LightLayer, SectionPos)`
- `@Overwrite` `retainData(ChunkPos, boolean)` (no-op)
- `@Overwrite` `getRawBrightness(BlockPos, int)`
- `@Overwrite` `lightOnInSection(SectionPos)`
- `@Shadow` mutable `blockEngine`, `skyEngine` (`LightEngine<?,?>`); plus `@Unique`/`@Override` client hooks (`clientUpdateLight`, `clientRemoveLightData`, `clientChunkLoad`) and maps.
- **Heaviest: OVERWRITE** (14 @Overwrite — wholesale replacement of the public LevelLightEngine API).

**ThreadedLevelLightEngineMixin** → `net.minecraft.server.level.ThreadedLevelLightEngine`  *(extends `LevelLightEngine`, implements `StarLightLightingProvider`)*
- `@Overwrite` `checkBlock(BlockPos)`
- `@Overwrite` `updateChunkStatus(ChunkPos)` (no-op)
- `@Overwrite` `updateSectionStatus(SectionPos, boolean)`
- `@Overwrite` `propagateLightSources(ChunkPos)` (no-op)
- `@Overwrite` `setLightEnabled(ChunkPos, boolean)` (no-op)
- `@Overwrite` `queueSectionData(LightLayer, SectionPos, DataLayer)` (no-op)
- `@Overwrite` `retainData(ChunkPos, boolean)` (no-op)
- `@Overwrite` `initializeLight(ChunkAccess, boolean)` → completed future
- `@Overwrite` `lightChunk(ChunkAccess, boolean)` → StarLight lighting / load
- `@WrapOperation`(MixinExtras) on `tryScheduleUpdate`, wrapping `LevelLightEngine.hasLightWork()` — `scheduleOnlyWhenDirty` (parallel-update throttle, see §6)
- `@Shadow` `chunkMap` (`ChunkMap`), `LOGGER`, `tryScheduleUpdate()`
- **Heaviest: OVERWRITE** (9 @Overwrite + 1 WrapOperation/REDIRECT-class).

### Chunk family

**ChunkAccessMixin** → `net.minecraft.world.level.chunk.ChunkAccess`  *(implements `ExtendedChunk`)*
- `@Inject(method="<init>", at=RETURN)` `nullSources(...)` — sets `skyLightSources=null`, allocates SWMR nibble arrays
- `@Redirect(method="initializeLightSources")` on `ChunkSkyLightSources.fillFrom(ChunkAccess)` — skip
- `@Redirect(method="...")` — (the `initializeLightSources` redirect above)
- `@Shadow` `skyLightSources` (`ChunkSkyLightSources`); adds 4 unique nibble/emptiness fields + ExtendedChunk getters/setters
- **Heaviest: REDIRECT** (plus duck fields/INJECT).

**LevelChunkMixin** → `net.minecraft.world.level.chunk.LevelChunk`  *(implements `ExtendedChunk`)*
- `@Inject(method="<init>(ServerLevel,ProtoChunk,LevelChunk$PostLoadProcessor)", at=TAIL)` `onTransitionToFull(...)` — copies nibbles/emptiness from ProtoChunk
- `@Redirect(method="setBlockState")` on `ChunkSkyLightSources.update(BlockGetter,III)` — skip
- **Heaviest: REDIRECT.**

**ProtoChunkMixin** → `net.minecraft.world.level.chunk.ProtoChunk`  *(implements `ExtendedChunk`)*
- `@Redirect(method="setBlockState")` on `ChunkSkyLightSources.update(BlockGetter,III)` — skip
- **Heaviest: REDIRECT.**

**ImposterProtoChunkMixin** → `net.minecraft.world.level.chunk.ImposterProtoChunk`  *(extends ProtoChunk, implements `ExtendedChunk`)*
- No injectors; `@Shadow final wrapped` (`LevelChunk`); overrides ExtendedChunk methods to delegate to wrapped chunk
- **Heaviest: ACCESSOR/duck** (interface impl + @Shadow only).

**EmptyLevelChunkMixin** → `net.minecraft.world.level.chunk.EmptyLevelChunk`  *(extends LevelChunk, implements `ExtendedChunk`)*
- No injectors; provides empty/no-op ExtendedChunk impl returning filled-empty light
- **Heaviest: ACCESSOR/duck.**

### World family

**LevelMixin** → `net.minecraft.world.level.Level`  *(implements `LevelAccessor`, `AutoCloseable`, `ExtendedWorld`)*
- No injectors; adds `getChunkAtImmediately`, `getAnyChunkImmediately` (ExtendedWorld duck)
- **Heaviest: ACCESSOR/duck.**

**ServerWorldMixin** → `net.minecraft.server.level.ServerLevel`  *(extends Level, implements `WorldGenLevel`, `ExtendedWorld`)*
- No injectors; `@Shadow final chunkSource` (`ServerChunkCache`); implements ExtendedWorld via ChunkMap visible-chunk lookup
- **Heaviest: ACCESSOR/duck.**

**ClientLevelMixin** (client) → `net.minecraft.client.multiplayer.ClientLevel`  *(extends Level, implements `ExtendedWorld`)*
- No injectors; `@Shadow getChunkSource()`; ExtendedWorld duck
- **Heaviest: ACCESSOR/duck.**

**WorldGenRegionMixin** → `net.minecraft.server.level.WorldGenRegion`  *(implements `WorldGenLevel`)*
- `@Override` (default-method override via implemented `WorldGenLevel`, no Mixin injector annotation) `getBrightness(LightLayer, BlockPos)` and `getRawBrightness(BlockPos, int)` — return 0 during worldgen if chunk not light-correct
- `@Shadow getChunk(int,int)`
- **Heaviest: effectively OVERWRITE** (replaces the inherited brightness methods on the target) — no @Inject/@Redirect; it is a Java `@Override` on an interface default that Mixin merges in.

**ChunkSerializerMixin** → `net.minecraft.world.level.chunk.storage.ChunkSerializer`
- `@Inject(method="write", at=RETURN)` `saveLightHook(...)` — write SWMR light into NBT
- `@Inject(method="read", at=RETURN)` `loadLightHook(...)` — load SWMR light from NBT
- **Heaviest: INJECT.**

### Blockstate

**BlockStateBaseMixin** → `net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase`  *(extends `StateHolder<Block,BlockState>`, implements `ExtendedAbstractBlockState`)*
- `@Inject(method="initCache", at=RETURN)` `initLightAccessState(...)` — caches opacity / conditionally-opaque flag
  - **(NeoForge port, patch 0002)** the inject body is extended to detect NeoForge dynamic light emission and set a new `scalablelux$actuallyDynamicLightEmission` flag; `@Shadow getBlock()` added; new duck method `scalablelux$actuallyDynamicLightEmission()` added.
- `@Shadow final` `useShapeForLightOcclusion`, `canOcclude`; `@Shadow cache` (`BlockBehaviour$BlockStateBase$Cache`)
- **Heaviest: INJECT** (+ duck/accessor fields).

### Client packet

**ClientPacketListenerMixin** (client) → `net.minecraft.client.multiplayer.ClientPacketListener`  *(priority = 1001; implements `ClientGamePacketListener`)*
- `@Redirect(method="handleLightUpdatePacket")` on `ClientLevel.queueLightUpdate(Runnable)` — run immediately
- `@Redirect(method="readSectionList")` on `LevelLightEngine.queueSectionData(...)` (ordinal 0) — route to StarLight `clientUpdateLight`
- `@Redirect(method="handleForgetLevelChunk")` on `ClientPacketListener.queueLightRemoval(ClientboundForgetLevelChunkPacket)` — StarLight `clientRemoveLightData`
- `@Redirect(method="handleLevelChunkWithLight")` on `ClientLevel.queueLightUpdate(Runnable)` (ordinal 0) — drop vanilla
- `@Inject(method="handleLevelChunkWithLight", at=RETURN)` `postChunkLoadHook(...)` — apply light data, `clientChunkLoad`, `enableChunkLight`
- `@Shadow` `level` (`ClientLevel`), `applyLightData(...)`, `enableChunkLight(...)`
- **Heaviest: REDIRECT** (4 redirects + 1 inject).

---

## 5. @Overwrite / full lighting-engine replacement

Starlight replaces the vanilla light engine **wholesale** rather than augmenting it:

- The entire public surface of `LevelLightEngine` is `@Overwrite`-d (14 methods) and the vanilla `blockEngine`/`skyEngine` fields are **nulled in the constructor inject** — vanilla's `LayerLightEngine`/`BlockLightEngine`/`SkyLightEngine`/`LightEngine`/`LayerLightSectionStorage` machinery is left dead/unused. (Notably ScalableLux does **not** mixin into `LayerLightEngine`, `BlockLightEngine`, `SkyLightEngine`, or `LightEngine` directly — it bypasses them by replacing the `LevelLightEngine` facade.)
- `ThreadedLevelLightEngine` is `@Overwrite`-d (9 methods) so server scheduling/`lightChunk`/`initializeLight` route into StarLight; `updateChunkStatus`/`setLightEnabled`/`queueSectionData`/`retainData`/`propagateLightSources` become no-ops.
- `WorldGenRegion.getBrightness`/`getRawBrightness` are replaced (via merged `@Override`).

**New lighting engine classes added** (the "stateless" SWMR star light engine), package `ca.spottedleaf.starlight.common.light`:
`StarLightEngine` (abstract core), `SkyStarLightEngine`, `BlockStarLightEngine`, `StarLightInterface` (+ inner `LightQueue`, `LightQueue.ChunkTasks`), `SWMRNibbleArray` (+ `SaveState`), `StarLightLightingProvider`. Light is stored per-chunk in `SWMRNibbleArray[]` attached to `ChunkAccess` via the `ExtendedChunk` duck (replacing vanilla `DataLayer`/section storage). Persistence is via `SaveUtil` hooked into `ChunkSerializer` read/write.

---

## 6. Parallel light updates (the `scalablelux.properties` thread-count knob)

**Config:** `Config.PARALLELISM` (static, in `common/config/Config.java`).
- Reads file `<configdir>/scalablelux.properties`, key **`parallelism`** (default written as `-1`).
- Resolved: if `parallelism < 1` → `max(1, availableProcessors / 3)`. If externally managed → `max(1, availableProcessors / 3)`.
- Config dir: Fabric `FabricLoader.getConfigDir()`; **NeoForge port** `FMLPaths.CONFIGDIR.get()` (patch 0002).

**Enable gate:** `GlobalExecutors` (`common/thread/`).
- `ENABLED = SchedulingUtil.isExternallyManaged() || FORCE_ENABLED || Config.PARALLELISM > 1`
- `FORCE_ENABLED = -Dscalablelux.force_enabled` system property.
- `SchedulingUtil.isExternallyManaged()` currently hardcoded `false`.
- So: parallel mode turns on when `parallelism > 1` (or forced). Prints a `[ScalableLux] Lighting scaling is enabled/disabled …` banner.

**Thread pool = FlowSched `ExecutorManager`:**
- `GlobalExecutors.prioritizedScheduler = new ExecutorManager(Config.PARALLELISM, threadInit)` — daemon worker threads named `scalablelux-%d`. `ExecutorManager` (FlowSched `executor` package) runs N `WorkerThread`s pulling from a `DynamicPriorityQueue<Task>` with per-`LockToken` mutual exclusion (chunk-coordinate locks).
- `SchedulingUtil.scheduleTask(ownerTag, task, x, z, radius)` builds a `LockTokenImpl(ownerTag, chunkKey)` for every chunk in the `radius` square, wraps in `SimpleTask(task, lockTokens, priority=60)`, and submits to `prioritizedScheduler.schedule(...)`. The locks guarantee no two tasks touching overlapping chunk regions run concurrently.

**Where it plugs into the engine:**
- `StarLightInterface.propagateChanges()` (light.../StarLightInterface.java:543): if `GlobalExecutors.ENABLED && lightEngine instanceof ThreadedLevelLightEngine` → `schedulePropagation0(...)`; otherwise runs synchronously on the calling thread.
- `schedulePropagation0` (line 572): under `synchronized(lightQueue)`, iterates pending `LightQueue.ChunkTasks` per chunk; for each not-already-in-flight chunk, submits a FlowSched task via `SchedulingUtil.scheduleTask(instanceId, runnable, chunkX, chunkZ, radius=2)`; tracks `chunkFutures` to avoid double-scheduling; clears `lightQueue.queueDirty`; calls `threadedLevelLightEngine.tryScheduleUpdate()` on completion.
- `StarLightInterface.isQueueDirty()` (line 622) → `lightQueue.queueDirty`.
- `ThreadedLevelLightEngineMixin.scheduleOnlyWhenDirty` (`@WrapOperation` on `tryScheduleUpdate`'s `hasLightWork()` call): when `ENABLED`, throttles vanilla scheduling — only allows it if the queue is dirty or ≥10 ms since last update (avoids spamming the main thread while async workers drain the queue).

**FlowSched usage summary:** ScalableLux depends only on FlowSched's `executor` package (`ExecutorManager`, `Task`, `LockToken`, `SimpleTask`, `WorkerThread`, internally `DynamicPriorityQueue`). The larger `scheduler` package in the submodule is unused by ScalableLux. FlowSched is shadow-bundled into the jar.

---

## 7. Access wideners / accessors / duck interfaces injected onto vanilla classes

**`scalablelux.accesswidener`** (`accessWidener v1 named`; on NeoForge converted to an AT via `remapJar.atAccessWideners`):
- `accessible class` `net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache`
- `accessible field` `…$Cache lightBlock I`
- `accessible field` `net/minecraft/world/level/chunk/LevelChunkSection states Lnet/.../PalettedContainer;`
- `accessible method` `net/minecraft/world/level/chunk/PalettedContainer get (I)Ljava/lang/Object;`
- `accessible field` `net/minecraft/server/level/ChunkMap level Lnet/minecraft/server/level/ServerLevel;`
- `accessible field` `net/minecraft/server/level/ChunkMap mainThreadExecutor Lnet/minecraft/util/thread/BlockableEventLoop;`
- `accessible method` `ChunkMap getUpdatingChunkIfPresent (J)Lnet/.../ChunkHolder;`
- `accessible method` `ChunkMap getVisibleChunkIfPresent (J)Lnet/.../ChunkHolder;`
- `accessible method` `ChunkMap getChunkQueueLevel (J)Ljava/util/function/IntSupplier;`
- `mutable field` `net/minecraft/world/level/lighting/LevelLightEngine blockEngine Lnet/.../LightEngine;`
- `mutable field` `net/minecraft/world/level/lighting/LevelLightEngine skyEngine Lnet/.../LightEngine;`
- `accessible class` `net/minecraft/server/level/ThreadedLevelLightEngine$TaskType`

**Duck interfaces implemented onto vanilla classes (via mixin `implements`):**
| Duck interface | Implemented onto (vanilla target) |
|---|---|
| `ExtendedChunk` (block/sky nibbles + emptiness maps) | `ChunkAccess`, `LevelChunk`, `ProtoChunk`(via redirect mixin only — not duck), `ImposterProtoChunk`, `EmptyLevelChunk` |
| `ExtendedWorld` (`getChunkAtImmediately`, `getAnyChunkImmediately`) | `Level`, `ServerLevel`, `ClientLevel` |
| `ExtendedAbstractBlockState` (`isConditionallyFullOpaque`, `getOpacityIfCached`, +NeoForge `scalablelux$actuallyDynamicLightEmission`) | `BlockBehaviour$BlockStateBase` |
| `StarLightLightingProvider` (`getLightEngine`, client light hooks) | `LevelLightEngine`, `ThreadedLevelLightEngine` |

**`@Shadow` accessors used (not via AW):** `LevelLightEngine.blockEngine/skyEngine`; `ThreadedLevelLightEngine.chunkMap`, `LOGGER`, `tryScheduleUpdate()`; `ChunkAccess.skyLightSources`; `BlockStateBase.useShapeForLightOcclusion/canOcclude/cache/getBlock()`; `ServerLevel.chunkSource`; `ClientLevel.getChunkSource()`; `WorldGenRegion.getChunk()`; `ImposterProtoChunk.wrapped`; `ClientPacketListener.level/applyLightData/enableChunkLight`.

---

## 8. License

**LGPL-3.0-only** (GNU Lesser General Public License v3, top-level `LICENSE`; declared `license="LGPL-3.0-only"` in both fabric.mod.json and neoforge.mods.toml). Authors: Spottedleaf, ishland. Original Starlight by PaperMC/Spottedleaf; ScalableLux fork by RelativityMC/ishland.

---

## Appendix — deduplicated target-class list (see returned message)
See the compact list returned to the caller; identical content.
