# C2ME chunk-system Yarn→Mojmap key (1.21.1)

The C2ME source is **1.21.1** (Yarn `1.21.1+build.1`). Earlier translators MISREAD these
1.21.1 **Yarn** names as "1.21.2/1.21.4 classes" because their **Mojmap** equivalents are
renamed, and wrongly DROPPED/disabled load-bearing chunk-system mixins. Do NOT drop these —
map them. All exist in 1.21.1 Mojmap (verify in `build/moddev/artifacts/neoforge-21.1.215-sources.jar`).

## Class names (Yarn → Mojmap)
- `AbstractChunkHolder` → `net.minecraft.server.level.GenerationChunkHolder`
- `ChunkHolder` → `net.minecraft.server.level.ChunkHolder`
- `BoundedRegionArray` → `net.minecraft.util.StaticCache2D`
- `ChunkGenerationStep` → `net.minecraft.world.level.chunk.status.ChunkStep`
- `ChunkGenerationSteps` → `net.minecraft.world.level.chunk.status.ChunkPyramid` (`GENERATION`/`LOADING` → `GENERATION_PYRAMID`/`LOADING_PYRAMID`; `.get(s)` → `.getStepTo(s)`)
- `GenerationDependencies` → `ChunkDependencies` (`getMaxLevel()` → `getRadius()`)
- `OptionalChunk<Chunk>` → `ChunkResult<ChunkAccess>` (`isPresent`→`isSuccess`, value via `orElse(null)`)
- `ChunkLevelType` → `net.minecraft.server.level.FullChunkStatus`
- `ChunkLevels` → `net.minecraft.server.level.ChunkLevel` (`INACCESSIBLE`→`MAX_LEVEL`)
- `ServerChunkLoadingManager` → `ChunkMap`; `ServerChunkManager` → `ServerChunkCache`;
  `ChunkTicketManager` → `DistanceManager`; `StorageIoWorker` → `IOWorker` (`Result`→`PendingStore`,
  fields nbt/future→data/result); `ChunkGenerationTask`/`GeneratingChunkMap`/`ChunkTaskPriorityQueue` keep meaning.

## GenerationChunkHolder / ChunkHolder method+field surface (Mojmap 1.21.1)
- holder future fields (Yarn → Mojmap): `accessibleFuture`→`fullChunkFuture`, `tickingFuture`→`tickingChunkFuture`,
  `entityTickingFuture`→`entityTickingChunkFuture`, `savingFuture`→`saveSync`, `levelIncreaseFuture`→`pendingFullStateConfirmation`, sendSync→`sendSync`.
- `GenerationChunkHolder.applyStep(ChunkStep, GeneratingChunkMap, StaticCache2D<GenerationChunkHolder>)`,
  `getOrCreateFuture(ChunkStatus)→ChunkResult<ChunkAccess>`, `replaceProtoChunk(ImposterProtoChunk)`,
  `removeTask(ChunkGenerationTask)`, `currentlyLoading`, `getPersistedStatus()`, `getTicketLevel()`,
  `getChunkIfPresent(ChunkStatus)`, `getLatestChunk()`. (Yarn `generate`≈`applyStep`; `createLoader`/`load`/`clearLoader` have no 1:1 — re-bridge as `NewChunkHolderVanillaInterface` already does.)
- `ServerChunkCache`: `updateChunks`→`runDistanceManagerUpdates`, `updateHolderMap`→`promoteChunkMap`,
  `tick`/`tickChunks` present; `mainThread` field (was `serverThread`).
- `DistanceManager`: `update`→`runAllUpdates`, ticket trackers `ChunkTicketTracker`/`PlayerTicketTracker`/`FixedPlayerDistanceChunkTracker`.

## NBT scanning (rewrites-chunkio / chunk-serializer) — also 1.21.1, not 1.21.4
- `SelectiveNbtCollector`/`NbtScanQuery`/`NbtScanner`/`scanChunk(NbtScanner)` are 1.21.1 Yarn → Mojmap
  `net.minecraft.nbt.SnbtPrinterTagVisitor`? NO — use `net.minecraft.nbt.visitors.{CollectToTag,SkipFields,FieldSelector,FieldTree}` + `StreamTagVisitor`/`TagValueInput`. Verify exact names in the sources jar before porting; do NOT assume absent.

## RULE for translators
If a Yarn class/method "doesn't seem to exist" in Mojmap, it's almost certainly RENAMED — look it up
in the sources jar by shape (fields/return types), don't drop the mixin. The chunk-system DRIVER mixins
(`MixinThreadedAnvilChunkStorage`→ChunkMap, `MixinServerChunkManager`→ServerChunkCache, `MixinChunkHolder`,
ticket managers) are LOAD-BEARING — they must end up ENABLED for C2ME's multithreading to work.
