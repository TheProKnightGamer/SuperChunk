# C2ME (NeoForge port) — Mixin Surface Analysis

Repo: `upstream/C2ME-neoforge`
Engine source actually lives in the **`C2ME-fabric`** git submodule; the NeoForge port is produced by **applying patches** (`patches/main/*.patch`) on top of that Fabric source — a Paper-style patch workflow driven by `build.sh` + `scripts/`. There is **no checked-out generated NeoForge tree** in this repo; you patch then build. All mixin Java lives under `C2ME-fabric/c2me-*/src/main/java`.

> IMPORTANT mapping note: the source is written against **Yarn** names (e.g. `ServerChunkLoadingManager`, `ChunkNoiseSampler`, `ThreadedAnvilChunkStorage`). The NeoForge port remaps to NeoForge (Mojmap/SRG) at build time via Architectury's `yarn-mappings-patch-neoforge`. The fully-qualified target classes in this report use **Yarn** package/class names. The Mojmap equivalents differ in name only (e.g. Yarn `ServerChunkLoadingManager` = Mojmap `net.minecraft.server.level.ChunkMap`, Yarn `ServerChunkManager` = Mojmap `ServerChunkCache`, Yarn `ChunkNoiseSampler` = Mojmap `NoiseChunk`). Cross-reference by vanilla concept, not by literal string.

---

## 1. Build / loader facts

| Fact | Value | Source |
|---|---|---|
| Build tool | **Architectury Loom** `1.11-SNAPSHOT` (`id 'dev.architectury.loom'`). Upstream Fabric uses plain `fabric-loom`; the port patch (`0001-Port-to-NeoForge.patch`) swaps it. | `C2ME-fabric/build.gradle` + patch |
| Loader | **NeoForge** (`loom.platform=neoforge`, `neoForge "net.neoforged:neoforge:${forge_version}"`) | patch `gradle.properties` |
| Minecraft version | **1.21.1** (`minecraft_version=1.21.1`) | `gradle.properties` |
| NeoForge version | **21.1.209** (`forge_version=21.1.209`) | patch `gradle.properties` |
| Mappings | **Yarn** `1.21.1+build.1` layered with **`dev.architectury:yarn-mappings-patch-neoforge` 1.21+build.4** (Yarn-named source, NeoForge-remapped output) | patch `build.gradle` |
| Java | **21** (`sourceCompatibility/targetCompatibility = VERSION_21`); JMH subset compiled at 22 | `build.gradle` |
| Mixin AP | Legacy mixin AP forced on (`mixin { useLegacyMixinAp = true }`, `remapJar { useMixinAP = true }`) | patch |
| MixinSquared | `com.github.bawnorton.mixinsquared:mixinsquared-neoforge:0.2.0-beta.6` (allows targeting other mods' mixins) | patch |
| MixinExtras | Used heavily (`@WrapOperation`, `@WrapMethod`, `@ModifyReturnValue`, `@ModifyExpressionValue`, `@WrapWithCondition`) — bundled by NeoForge | source |
| Access mechanism | A Loom **access widener** (`c2me-base.accesswidener`) is converted to a NeoForge **AccessTransformer** at remap (`atAccessWideners.add("c2me-base.accesswidener")`) | patch `c2me-base/build.gradle` |
| Notable libs shaded | asyncutil, exp4j, jctools, rxjava3, reactive-streams, FlowSched (included build) | `build.gradle` |
| NeoForge port patches | 13 patches in `patches/main/` — key one is `0001-Port-to-NeoForge.patch`. Others: enable DFC compiler, publishing, delayFullChunkEvents, sodium 0.8.11 view-distance backport, prevent item drops on worldgen threads, drop `MixinConfiguredFeature`. | `patches/main/` |

---

## 2. Module map (gradle subprojects)

All under `C2ME-fabric/`. `c2me-base` is the umbrella mod (`@Mod("c2me_base")`); other modules are gated by `ModuleMixinPlugin` reading config.

| Module | Purpose |
|---|---|
| `c2me-base` | Core: config system, mixin plugin framework, accesswidener, ~59 accessor/instrumentation mixins, shared util. |
| `c2me-threading-lighting` | Off-thread lighting engine integration (Starlight/ScalableLux compat). |
| `c2me-fixes-chunkio-threading-issues` | Thread-safety fix for chunk IO (StructurePoolElement). |
| `c2me-fixes-general-threading-issues` | Async catchers + ticket-manager concurrency fix. |
| `c2me-fixes-worldgen-threading-issues` | **Largest fix module (35 mixins)** — makes vanilla structure generators / random / transforms thread-safe. |
| `c2me-fixes-worldgen-vanilla-bugs` | Vanilla worldgen bug fix (chunk-status callback ordering). |
| `c2me-opts-worldgen-vanilla` | Worldgen perf rewrites: aquifer, structure-weight sampler, end biome cache, block thread-local cache. |
| `c2me-opts-natives-math` | **Native SIMD math** (OpenCL/JNI-free hand-written C). Off-heap noise samplers; CMake-built `.so`/`.dll`. |
| `c2me-opts-dfc` | **Density Function Compiler** — JIT/AST compiler for density functions (huge non-mixin engine). |
| `c2me-opts-worldgen-biome-cache` | (declared, **no mixins present** — empty/disabled). |
| `c2me-opts-worldgen-general` | Random-instance reuse for worldgen (redirect random ctor sites). |
| `c2me-opts-allocs` | Allocation reduction: NBT, Identifier, Util, surface builder, ore feature pooling. |
| `c2me-opts-math` | Faster noise/ChunkPos math (overwrites perlin/octave samplers). |
| `c2me-opts-scheduling` | Mid-tick chunk tasks, enhanced autosave, shutdown ordering, task scheduling. |
| `c2me-opts-chunkio` | Chunk IO tweaks (hide sync disk writes, limit nbt cache). |
| `c2me-opts-chunk-access` | ASM-only target marker (`asm.ASMTargets`), no runtime mixin injectors. |
| `c2me-rewrites-chunk-serializer` | Rewritten chunk (de)serializer (GC-free), Starlight save-state. |
| `c2me-rewrites-chunkio` | Rewritten chunk storage IO layer (recreation/versioned storage). |
| `c2me-rewrites-chunk-system` | **The new async chunk system (21 mixins + large common engine)** built on FlowSched. Highest-impact module. |
| `c2me-server-utils` | Server command additions. |
| `c2me-client-uncapvd` | Client: uncap render/view distance (GameOptions, SimpleOption, Sodium/VulkanMod compat). |
| `c2me-notickvd` | No-tick view distance (16 mixins) — render chunks beyond simulation distance. |
| `FlowSched` (included build) | Custom async **status-advancing scheduler** library (`com.ishland.flowsched`). Backbone of the chunk-system rewrite. |
| `tests:*` | Test harness mixins (`c2metests.mixins.json`, `c2meworlddiff.mixins.json`) — **NOT shipped**, excluded from conflict analysis. |

---

## 3. Mixin configs

All configs declare `"parent": "c2me.mixins.json"` (the empty root config in `c2me-base`, `mixinPriority: 1100`, `overwrites.conformVisibility: true`). Each module config:

| Config file | package | plugin |
|---|---|---|
| `c2me-base.mixins.json` | `com.ishland.c2me.base.mixin` | `base.TheMixinPlugin` |
| `c2me.mixins.json` (root) | `com.ishland.c2me.mixin` | — (empty container) |
| `c2me-client-uncapvd.mixins.json` | `...client.uncapvd.mixin` | `ModuleMixinPlugin` |
| `c2me-fixes-chunkio-threading-issues.mixins.json` | `...fixes.chunkio.threading_issues.mixin` | `ModuleMixinPlugin` |
| `c2me-fixes-general-threading-issues.mixins.json` | `...fixes.general.threading_issues.mixin` | `ModuleMixinPlugin` |
| `c2me-fixes-worldgen-threading-issues.mixins.json` | `...fixes.worldgen.threading_issues.mixin` | `worldgen.threading_issues.MixinPlugin` |
| `c2me-fixes-worldgen-vanilla-bugs.mixins.json` | `...fixes.worldgen.vanilla_bugs.mixin` | `ModuleMixinPlugin` |
| `c2me-notickvd.mixins.json` | `...notickvd.mixin` | `ModuleMixinPlugin` |
| `c2me-opts-allocs.mixins.json` | `...opts.allocs.mixin` | `opts.allocs.MixinPlugin` |
| `c2me-opts-chunkio.mixins.json` | `...opts.chunkio.mixin` | `opts.chunkio.MixinPlugin` |
| `c2me-opts-dfc.mixins.json` | `...opts.dfc.mixin` | `ModuleMixinPlugin` |
| `c2me-opts-math.mixins.json` | `...opts.math.mixin` | `ModuleMixinPlugin` |
| `c2me-opts-natives-math.mixins.json` | `...opts.natives_math.mixin` | `ModuleMixinPlugin` |
| `c2me-opts-scheduling.mixins.json` | `...opts.scheduling.mixin` | `opts.scheduling.mixin.MixinPlugin` |
| `c2me-opts-worldgen-general.mixins.json` | `...opts.worldgen.general.mixin` | `ModuleMixinPlugin` |
| `c2me-opts-worldgen-vanilla.mixins.json` | `...opts.worldgen.vanilla.mixin` | `worldgen.vanilla.MixinPlugin` |
| `c2me-rewrites-chunk-serializer.mixins.json` | `...rewrites.chunk_serializer.mixin` | `ModuleMixinPlugin` |
| `c2me-rewrites-chunk-system.mixins.json` | `...rewrites.chunksystem.mixin` | `chunksystem.MixinPlugin` |
| `c2me-rewrites-chunkio.mixins.json` | `...rewrites.chunkio.mixin` | `ModuleMixinPlugin` |
| `c2me-server-utils.mixins.json` | `...server.utils.mixin` | `ModuleMixinPlugin` |
| `c2me-threading-lighting.mixins.json` | `...threading.lighting.mixin` | `ModuleMixinPlugin` |
| `c2metests.mixins.json` / `c2meworlddiff.mixins.json` | test packages | (test-only, not shipped) |

(See section 4 for the full mixin class membership of each.)

---

## 4. MIXIN TARGET TABLE (every shipped mixin class)

`ACC` = pure Accessor/Invoker interface. Injection types are the heaviest present in the class file.

### c2me-base (`...base.mixin`)
**access.* (all accessor/invoker interfaces):**
- `IAquiferSamplerFluidLevel` → `AquiferSampler$FluidLevel` (ACC)
- `IAtomicSimpleRandomDeriver` → `CheckedRandom$Splitter` — @Accessor
- `IBelowZeroRetrogen` → `world.chunk.BelowZeroRetrogen` — @Accessor/@Invoker
- `IBlender` → `world.gen.chunk.Blender` — @Accessor
- `IBlendingData` → `world.gen.chunk.BlendingData` — @Accessor
- `IBlockEntity` → `block.entity.BlockEntity` (ACC)
- `IChunkGenerator` → `world.gen.chunk.ChunkGenerator` — @Invoker
- `IChunkHolder` → `server.world.ChunkHolder` — @Invoker
- `IChunkNoiseSampler` → `world.gen.chunk.ChunkNoiseSampler` — @Accessor
- `IChunkNoiseSamplerDensityInterpolator` → `ChunkNoiseSampler$DensityInterpolator` — @Invoker
- `IChunkSection` → `world.chunk.ChunkSection` — @Accessor
- `IChunkTicket` → `server.world.ChunkTicket` — @Invoker
- `IChunkTicketManager` → `server.world.ChunkTicketManager` — @Accessor/@Invoker
- `IChunkTicketManagerDistanceFromNearestPlayerTracker` → `ChunkTicketManager$DistanceFromNearestPlayerTracker` — @Accessor
- `IChunkTicketManagerNearbyChunkTicketUpdater` → `ChunkTicketManager$NearbyChunkTicketUpdater` — @Accessor
- `IChunkTickScheduler` → `world.tick.ChunkTickScheduler` — @Accessor
- `IDensityFunctionsCaveScaler` → `DensityFunctions$CaveScaler` — @Invoker
- `IDensityFunctionTypesWeirdScaledSamplerRarityValueMapper` → `DensityFunctionTypes$WeirdScaledSampler$RarityValueMapper` — @Accessor
- `IFlowableFluid` → `fluid.FlowableFluid` — @Invoker
- `IInterpolatedNoiseSampler` → `util.math.noise.InterpolatedNoiseSampler` — @Accessor
- `IMultiNoiseBiomeSource` → `world.biome.source.MultiNoiseBiomeSource` (ACC)
- `INbtCompound` → `nbt.NbtCompound` (ACC)
- `IOctavePerlinNoiseSampler` → `util.math.noise.OctavePerlinNoiseSampler` — @Accessor
- `IPerlinNoiseSampler` → `util.math.noise.PerlinNoiseSampler` — @Accessor
- `IRegionBasedStorage` → `world.storage.RegionBasedStorage` — @Invoker
- `IRegionFile` → `world.storage.RegionFile` — @Accessor/@Invoker
- `ISerializingRegionBasedStorage` → `world.storage.SerializingRegionBasedStorage` — @Accessor
- `IServerChunkManager` → `server.world.ServerChunkManager` — @Accessor/@Invoker
- `IServerEntityManager` → `server.world.ServerEntityManager` — @Invoker
- `IServerLightingProvider` → `server.world.ServerLightingProvider` — @Invoker
- `ISimpleRandom` → `util.math.random.LocalRandom` — @Accessor/@Invoker
- `ISimpleTickScheduler` → `world.tick.SimpleTickScheduler` — @Accessor
- `ISimplexNoiseSampler` → `util.math.noise.SimplexNoiseSampler` — @Accessor
- `ISimulationDistanceLevelPropagator` → `world.SimulationDistanceLevelPropagator` — @Accessor
- `IState` → `state.State` — @Accessor
- `IStorageIoWorker` → `world.storage.StorageIoWorker` — @Invoker
- `IStructurePiece` → `structure.StructurePiece` — @Accessor
- `IStructureStart` → `structure.StructureStart` — @Accessor
- `IStructureWeightSampler` → `world.gen.StructureWeightSampler` (ACC)
- `ISyncedClientOptions` → `network.packet.c2s.common.SyncedClientOptions` — @Accessor/@Mutable
- `ITACSTicketManager` / `IThreadedAnvilChunkStorageTicketManager` → `ServerChunkLoadingManager$TicketManager` — @Accessor
- `IThreadedAnvilChunkStorage` → `server.world.ServerChunkLoadingManager` — @Accessor/@Invoker
- `IUpgradeData` → `world.chunk.UpgradeData` — @Accessor
- `IVersionedChunkStorage` → `world.storage.VersionedChunkStorage` — @Accessor/@Invoker
- `IWeightedList` → `util.collection.WeightedList` — @Accessor
- `IWeightedListEntry` → `WeightedList$Entry` — @Invoker
- `IWorldChunk` → `world.chunk.WorldChunk` — @Accessor
- `IXoroshiro128PlusPlusRandom` → `util.math.random.Xoroshiro128PlusPlusRandom` — @Accessor
- `IXoroshiro128PlusPlusRandomDeriver` → `Xoroshiro128PlusPlusRandom$Splitter` — @Accessor
- `IXoroshiro128PlusPlusRandomImpl` → `util.math.random.Xoroshiro128PlusPlusRandomImpl` — @Accessor
- `access.fapi.IArrayBackedEvent` → SOFT `net.fabricmc.fabric.impl.base.event.ArrayBackedEvent` — @Accessor

**non-accessor:**
- `instrumentation.MixinServerChunkManager` → `ServerChunkManager` — @WrapOperation, @WrapMethod
- `report.MixinDedicatedServerWatchdog` → `DedicatedServerWatchdog` — @Inject
- `scheduler.MixinServerChunkManager` → `ServerChunkManager` — @WrapOperation
- `scheduler.MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — @Inject
- `theinterface.MixinStorageIoWorker` → `StorageIoWorker` — interface inject (duck)
- `util.log4j2shutdownhookisnomore.MixinMain` → `server.Main` — @Inject
- `util.log4j2shutdownhookisnomore.MixinMinecraftDedicatedServer` → `MinecraftDedicatedServer` — @Inject

### c2me-client-uncapvd (`...client.uncapvd.mixin`)
- `ISimpleOption` → `client.option.SimpleOption` — @Accessor/@Mutable
- `MixinGameOptions` → `client.option.GameOptions` — @Inject
- `MixinSodiumUserConfigCategories` → SOFT (Sodium: `me.jellysquid...SodiumGameOptionPages` / `net.caffeinemc...SodiumGameOptionPages` / `UserConfigCategories`) — @ModifyConstant
- `MixinSyncedClientOptions` → `SyncedClientOptions` — @WrapOperation
- `MixinVKModOptions` → SOFT `net.vulkanmod.config.option.Options` — @ModifyConstant

### c2me-fixes-chunkio-threading-issues
- `MixinStructurePoolElement` → `structure.pool.StructurePoolElement` — @Inject/@Mutable

### c2me-fixes-general-threading-issues
- `MixinChunkTicketManager` → `server.world.ChunkTicketManager` — @Redirect
- `asynccatchers.MixinMinecraftServer` → `MinecraftServer` — @Inject
- `asynccatchers.MixinServerChunkManager` → `ServerChunkManager` — @Inject
- `asynccatchers.MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — @Inject

### c2me-fixes-worldgen-threading-issues (35) — thread-safety on vanilla structure generators
- `deadlocks.MixinDataFixerType` → SOFT `com.mojang.datafixers.types.Type` — @Redirect
- `threading.MixinChunk` → `world.chunk.Chunk` — @Inject/@Mutable
- `threading.MixinDesertTempleGenerator` → `structure.DesertTempleGenerator` — @Inject/@Redirect
- `threading.MixinJungleTempleGenerator` → `structure.JungleTempleGenerator` — @Redirect
- `threading.MixinMineshaftGeneratorMineshaftCorridor` → `MineshaftGenerator$MineshaftCorridor` (no-injector / field setup)
- `threading.MixinMineshaftGeneratorMineshaftRoom` → `MineshaftGenerator$MineshaftRoom` — @Inject/@Mutable
- `threading.MixinNetherFortressGeneratorBridgePlatform` → `NetherFortressGenerator$BridgePlatform`
- `threading.MixinNetherFortressGeneratorCorridorLeftTurn` → `NetherFortressGenerator$CorridorLeftTurn`
- `threading.MixinNetherFortressGeneratorCorridorRightTurn` → `NetherFortressGenerator$CorridorRightTurn`
- `threading.MixinNetherFortressGeneratorPiece` → `NetherFortressGenerator$Piece` — @Redirect
- `threading.MixinNetherFortressGeneratorPieceData` → `NetherFortressGenerator$PieceData` — @Redirect
- `threading.MixinNetherFortressGeneratorStart` → `NetherFortressGenerator$Start` — @Inject/@Redirect
- `threading.MixinNoiseChunkGenerator` → `world.gen.chunk.NoiseChunkGenerator` — @Inject/@Redirect
- `threading.MixinOceanMonumentGeneratorBase` → `OceanMonumentGenerator$Base`
- `threading.MixinOceanMonumentGeneratorPieceSetting` → `OceanMonumentGenerator$PieceSetting`
- `threading.MixinRandomizedIntBlockStateProvider` → `world.gen.stateprovider.RandomizedIntBlockStateProvider` — **@Overwrite**
- `threading.MixinShiftableStructurePiece` → `structure.ShiftableStructurePiece`
- `threading.MixinStrongholdGenerator` → `structure.StrongholdGenerator` — @Redirect
- `threading.MixinStrongholdGeneratorChestCorridor` → `StrongholdGenerator$ChestCorridor`
- `threading.MixinStrongholdGeneratorPieceData` → `StrongholdGenerator$PieceData` — @Redirect
- `threading.MixinStrongholdGeneratorPortalRoom` → `StrongholdGenerator$PortalRoom`
- `threading.MixinStrongholdGeneratorSpiralStaircase` → `StrongholdGenerator$SpiralStaircase` — @Redirect
- `threading.MixinStrongholdGeneratorStart` → `StrongholdGenerator$Start` — @Inject/@Mutable
- `threading.MixinStructure` → `structure.StructureTemplate` — @Inject/@Mutable
- `threading.MixinStructureChecker` → `world.StructureLocator` — @Inject/@Redirect/@Mutable
- `threading.MixinStructurePalettedBlockInfoList` → `StructureTemplate$PalettedBlockInfoList` — @Inject/@Mutable
- `threading.MixinStructurePlacementData` → `structure.StructurePlacementData` — @Inject/@Mutable
- `threading.MixinStructureStart` → `structure.StructureStart` — @Redirect/**@Overwrite**
- `threading.MixinSwampHutGenerator` → `structure.SwampHutGenerator`
- `threading.MixinWoodlandMansionGeneratorGenerationPiece` → `WoodlandMansionGenerator$GenerationPiece`
- `threading.MixinWoodlandMansionGeneratorMansionParameters` → `WoodlandMansionGenerator$MansionParameters` — @Redirect
- `threading.math.MixinAffineTransformation` → `util.math.AffineTransformation` — @Inject
- `threading.math.MixinDirectionTransformation` → `util.math.DirectionTransformation` — @Inject
- `threading_detections.random_instances.MixinWorld` → `world.World` — @Redirect
- `threading_detections.readonly_protection.MixinChunkRegion` → `world.ChunkRegion` — @WrapMethod

### c2me-fixes-worldgen-vanilla-bugs
- `ensure_chunk_status_before_callback.MixinChunkHolder` → `server.world.ChunkHolder` — @WrapWithCondition

### c2me-notickvd (16)
- `MixinChunkHolder` → `server.world.ChunkHolder` — @Redirect
- `MixinChunkTicketManager` → `server.world.ChunkTicketManager` — @Inject/**@Overwrite**/@Mutable
- `MixinChunkTicketManagerNearbyChunkTicketUpdater` → `ChunkTicketManager$NearbyChunkTicketUpdater` — @ModifyVariable
- `MixinMinecraftServer` → `MinecraftServer` — @Inject
- `MixinPlayerManager` → `server.PlayerManager` (field/no-injector)
- `MixinServerAccessibleChunkSending` → SOFT C2ME `chunksystem...statuses.ServerAccessibleChunkSending` — @Inject/**@Overwrite**/@Mutable
- `MixinServerBlockTicking` → SOFT C2ME `chunksystem...statuses.ServerBlockTicking` — @Inject
- `MixinServerChunkManager` → `server.world.ServerChunkManager` — @Redirect/@WrapOperation
- `MixinSimulationDistanceLevelPropagator` → `world.SimulationDistanceLevelPropagator` — @ModifyConstant
- `MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — @Inject/@Redirect/@ModifyArg/@WrapWithCondition/**@Overwrite**
- `MixinWorld` → `world.World` — @ModifyArg
- `MixinWorldChunk` → `world.chunk.WorldChunk` — @ModifyArg
- `client.MixinIntegratedServer` → `server.integrated.IntegratedServer` — @Inject
- `ext_render_distance.MixinServerConfigurationNetworkHandler` → `server.network.ServerConfigurationNetworkHandler` — @WrapOperation
- `ext_render_distance.MixinServerPlayNetworkHandler` → `server.network.ServerPlayNetworkHandler` — @WrapOperation
- `servercore.MixinServerChunkManager` → `server.world.ServerChunkManager` — @Redirect

### c2me-opts-allocs (13)
- `MixinIdentifier` → `util.Identifier` — **@Overwrite**
- `MixinNbtCompound` → `nbt.NbtCompound` — @Redirect/@ModifyArg/**@Overwrite**
- `MixinNbtCompound1` → SOFT `nbt.NbtCompound$1` — @Redirect/@ModifyVariable
- `MixinNbtList` → `nbt.NbtList` — @Redirect/@ModifyArg/**@Overwrite**
- `MixinNbtList1` → SOFT `nbt.NbtList$1` — @Redirect/@ModifyVariable
- `MixinUtil` → `util.Util` — **@Overwrite**
- `asm.ASMTargets` → `server.world.ServerChunkManager` (ASM marker, no injectors)
- `noise.MixinChainedBlockSource` → `world.gen.ChainedBlockSource` — @Inject/**@Overwrite**
- `object_pooling_caching.MixinConfiguredFeature` → `world.gen.feature.ConfiguredFeature` — **@Overwrite** (NOTE: dropped by port patch 0010)
- `object_pooling_caching.MixinOreFeature` → `world.gen.feature.OreFeature` — @Redirect
- `surfacebuilder.MixinMaterialRuleContext` → `MaterialRules$MaterialRuleContext` — @Inject/**@Overwrite**
- `surfacebuilder.MixinMaterialRulesSequenceBlockStateRule` → `MaterialRules$SequenceBlockStateRule` — @Inject/**@Overwrite**
- `surfacebuilder.MixinMaterialRulesSequenceMaterialRule` → `MaterialRules$SequenceMaterialRule` — @Inject/**@Overwrite**

### c2me-opts-chunk-access
- `asm.ASMTargets` → `server.world.ServerChunkManager` (ASM bytecode transform marker, no mixin injector)

### c2me-opts-chunkio
- `hide_sync_disk_writes_behind_flag.MixinRegionBasedStorage` → `world.storage.RegionBasedStorage` — @Inject/@Mutable
- `limit_nbt_cache.MixinStorageIoWorker` → `world.storage.StorageIoWorker` — @Inject

### c2me-opts-dfc (11) — density function compiler hooks (mostly @WrapMethod + duck `implements`)
- `MixinChunkNoiseSampler` → `world.gen.chunk.ChunkNoiseSampler` — @Inject/@ModifyArg
- `MixinChunkNoiseSampler1` → SOFT `ChunkNoiseSampler$1` (duck implements)
- `MixinChunkNoiseSamplerCache2D` → `ChunkNoiseSampler$Cache2D` — @Mutable (duck)
- `MixinChunkNoiseSamplerCacheOnce` → `ChunkNoiseSampler$CacheOnce` — @WrapMethod/@Mutable
- `MixinChunkNoiseSamplerCellCache` → `ChunkNoiseSampler$CellCache` — @WrapMethod/@Mutable
- `MixinChunkNoiseSamplerDensityInterpolator` → `ChunkNoiseSampler$DensityInterpolator` — @WrapMethod/@Mutable
- `MixinChunkNoiseSamplerFlatCache` → `ChunkNoiseSampler$FlatCache` — @Mutable (duck)
- `MixinDFTBinaryOperation` → `DensityFunctionTypes$BinaryOperation` — @WrapMethod
- `MixinDFTWrapping` → `DensityFunctionTypes$Wrapping` — @WrapMethod/@Mutable
- `MixinNoiseConfig` → `world.gen.noise.NoiseConfig` — @Inject/@Mutable
- `MixinSplineImplementation` → `util.math.Spline$Implementation` — **@Overwrite**

### c2me-opts-math
- `MixinChunkNoiseSampler` → `world.gen.chunk.ChunkNoiseSampler` — @Inject/@Redirect/@Mutable
- `MixinChunkPos` → `util.math.ChunkPos` — **@Overwrite**
- `MixinOctavePerlinNoiseSampler` → `util.math.noise.OctavePerlinNoiseSampler` — @Inject/**@Overwrite**
- `MixinPerlinNoiseSampler` → `util.math.noise.PerlinNoiseSampler` — **@Overwrite**

### c2me-opts-natives-math (8) — SIMD-backed noise (all @Overwrite math kernels)
- `MixinBiomeAccess` → `world.biome.source.BiomeAccess` — **@Overwrite**
- `MixinDFTypesEndIslands` → `DensityFunctionTypes$EndIslands` — @Inject/**@Overwrite**
- `MixinDoublePerlinNoiseSampler` → `util.math.noise.DoublePerlinNoiseSampler` — @Inject/**@Overwrite**
- `MixinInterpolatedNoiseSampler` → `util.math.noise.InterpolatedNoiseSampler` — @Inject/**@Overwrite**
- `df.MixinDFTNoise` → `DensityFunctionTypes$Noise` — **@Overwrite**
- `df.MixinDFTShift` → `DensityFunctionTypes$Shift` (duck/field)
- `df.MixinDFTShiftA` → `DensityFunctionTypes$ShiftA` (duck/field)
- `df.MixinDFTShiftB` → `DensityFunctionTypes$ShiftB` (duck/field)

### c2me-opts-scheduling (15)
- `general_overheads.MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — @Redirect
- `idle_tasks.autosave.disable_vanilla_mid_tick_autosave.MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — @Redirect
- `idle_tasks.autosave.enhanced_autosave.MixinMinecraftServer` → `MinecraftServer` — @ModifyReturnValue
- `idle_tasks.autosave.enhanced_autosave.MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — @Inject
- `mid_tick_chunk_tasks.MixinMinecraftServer` → `MinecraftServer` (field/no-injector)
- `mid_tick_chunk_tasks.MixinServerChunkManager` → `ServerChunkManager` — @Inject
- `mid_tick_chunk_tasks.MixinServerWorld` → `server.world.ServerWorld` — @Inject
- `mid_tick_chunk_tasks.MixinWorld` → `world.World` — @Inject
- `ordering.player_move.MixinServerPlayNetworkHandler` → `ServerPlayNetworkHandler` — @Inject/@Redirect
- `shutdown.MixinMinecraftServer` → `MinecraftServer` — @Inject
- `shutdown.MixinServerEntityManager` → `ServerEntityManager` (field/no-injector)
- `shutdown.MixinServerWorld` → `ServerWorld` — @Inject
- `task_scheduling.MixinChunkHolder` → `server.world.ChunkHolder` — @Inject
- `task_scheduling.MixinEntityChunkDataAccess` → `world.storage.EntityChunkDataAccess` — @ModifyArg
- `task_scheduling.MixinServerChunkManager` → `server.world.ServerChunkManager` — **@Overwrite**

### c2me-opts-worldgen-general
- `random_instances.MixinAtomicSimpleRandomFactory` → `CheckedRandom$Splitter` — **@Overwrite**
- `random_instances.MixinRedirectAtomicSimpleRandom` → multi-target {`NoiseChunkGenerator`, `world.gen.feature.GeodeFeature`, `world.gen.chunk.ChunkGenerator`, `world.gen.chunk.placement.RandomSpreadStructurePlacement`} — @Redirect
- `random_instances.MixinRedirectAtomicSimpleRandomStatic` → `world.gen.chunk.placement.StructurePlacement` — @Redirect

### c2me-opts-worldgen-vanilla
- `aquifer.MixinAquiferSamplerImpl` → `AquiferSampler$Impl` — @Inject/**@Overwrite**
- `structure_weight_sampler.MixinStructureWeightSampler` → `world.gen.StructureWeightSampler` — **@Overwrite**
- `the_end_biome_cache.MixinTheEndBiomeSource` → `world.biome.source.TheEndBiomeSource` — **@Overwrite**
- `tlcache.MixinBlock` → `block.Block` — **@Overwrite**

### c2me-rewrites-chunk-serializer
- `ChunkStatusMixin` → `world.chunk.ChunkStatus` (field/no-injector)
- `GenerationStepCarverMixin` → `world.gen.GenerationStep$Carver` — @Inject
- `HeightMapTypeMixin` → `world.Heightmap$Type` — @Inject
- `IdentifierMixin` → `util.Identifier` (field/no-injector)
- `IStarlightSaveState` → SOFT `ca.spottedleaf.starlight.common.light.SWMRNibbleArray$SaveState` — @Accessor
- `MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — **@Overwrite**
- (`StructureFeatureMixin` exists but is fully commented out / disabled — NOT shipped)

### c2me-rewrites-chunk-system (21) — the new async chunk system
- `MixinChunkGenerator` → `world.gen.chunk.ChunkGenerator` — @Redirect
- `MixinChunkHolder` → `server.world.ChunkHolder` — @Inject/@WrapWithCondition
- `MixinChunkTicketManager` → `server.world.ChunkTicketManager` — @Inject/@Redirect/@WrapOperation
- `MixinChunkTicketManagerTicketDistanceLevelPropagator` → `ChunkTicketManager$TicketDistanceLevelPropagator` — @Inject/**@Overwrite**
- `MixinMinecraftServer` → `MinecraftServer` — @Inject
- `MixinNoiseChunkGenerator` → `world.gen.chunk.NoiseChunkGenerator` — @Redirect
- `MixinPointOfInterestStorage` → `world.poi.PointOfInterestStorage` (duck implements)
- `MixinSerializingRegionBasedStorage` → `world.storage.SerializingRegionBasedStorage` (duck implements)
- `MixinServerChunkManager` → `server.world.ServerChunkManager` — @Inject/@Redirect/@WrapOperation/**@Overwrite**
- `MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — @Inject/@Redirect/@ModifyReturnValue/**@Overwrite**
- `MixinWorldGenerationProgressLogger` → `server.WorldGenerationProgressLogger` — @Inject/**@Overwrite**
- `async_serialization.MixinBlender` → `world.gen.chunk.Blender` — @Redirect
- `async_serialization.MixinChunkRegion` → `world.ChunkRegion` — @WrapOperation
- `async_serialization.MixinChunkSerializer` → `world.ChunkSerializer` — @Redirect
- `async_serialization.MixinProtoChunk` → `world.chunk.ProtoChunk` (duck/field)
- `async_serialization.MixinSerializingRegionBasedStorage` → `world.storage.SerializingRegionBasedStorage` (duck/field)
- `async_serialization.MixinStorageIoWorker` → `world.storage.StorageIoWorker` — @Inject/@Redirect/**@Overwrite**
- `async_serialization.MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — **@Overwrite**
- `async_serialization.gc_free_serializer.MixinChunkDataSerializer` → SOFT C2ME `chunk_serializer.common.ChunkDataSerializer` — @Redirect
- `fixes.MixinServerEntityManager` → `server.world.ServerEntityManager` — @Inject/**@Overwrite**/@Mutable
- `fluid_postprocessing.MixinWorldChunk` → `world.chunk.WorldChunk` — @Redirect

### c2me-rewrites-chunkio
- `MixinChunkPosKeyedStorage` → `world.storage.ChunkPosKeyedStorage` — @Redirect
- `MixinRecreatedChunkStorage` → `world.storage.RecreatedChunkStorage` — @Redirect
- `MixinRecreationStorage` → `world.storage.RecreationStorage` — @Redirect
- `MixinStorageIoWorker` → `world.storage.StorageIoWorker` — @Inject/@Mutable
- `MixinVersionedChunkStorage` → `world.storage.VersionedChunkStorage` — @Redirect

### c2me-server-utils
- `MixinCommandManager` → `server.command.CommandManager` — @Inject

### c2me-threading-lighting
- `MixinServerLightingProvider` → `server.world.ServerLightingProvider` — @Inject
- `MixinThreadedAnvilChunkStorage` → `ServerChunkLoadingManager` — @Inject/@Redirect
- `scalablelux.MixinSchedulingUtil` → SOFT `ca.spottedleaf.starlight.common.thread.SchedulingUtil` — **@Overwrite**

---

## 5. @Overwrite and heavy-redirect HOTSPOTS (highest merge conflict risk)

**Whole-method / class @Overwrite (will hard-conflict with any other mod touching same method):**
- `ServerChunkLoadingManager` (= Mojmap `ChunkMap`) — overwritten in **4 modules** (chunk-system, chunk-system/async_serialization, chunk-serializer, notickvd). The single most contested vanilla class.
- `ServerChunkManager` (= `ServerChunkCache`) — @Overwrite in c2me-opts-scheduling/task_scheduling + heavy redirect/wrap in chunk-system.
- `ChunkTicketManager` (+ `$TicketDistanceLevelPropagator`) — @Overwrite in notickvd + chunk-system.
- `StorageIoWorker` — @Overwrite in chunk-system/async_serialization.
- `StructureWeightSampler`, `BiomeAccess`, `TheEndBiomeSource`, `Block`(tlcache), `ChunkPos`, `Identifier`, `Util`, `NbtCompound`, `NbtList` — full @Overwrite of methods.
- Noise kernels: `PerlinNoiseSampler`, `OctavePerlinNoiseSampler`, `DoublePerlinNoiseSampler`, `InterpolatedNoiseSampler`, `SimplexNoiseSampler`(accessor) — @Overwrite (replaced by C2ME math/native-math). **Conflicts with any noise-optimizing mod (Lithium/Noisium/etc.).**
- Density function types: `DensityFunctionTypes$Noise/$EndIslands`, `Spline$Implementation`, `AquiferSampler$Impl`, `ChainedBlockSource`, `MaterialRules$*`, `RandomizedIntBlockStateProvider`, `ConfiguredFeature`, `CheckedRandom$Splitter` — @Overwrite.
- `StructureStart` — @Overwrite + @Redirect (worldgen threading).
- `WorldGenerationProgressLogger` — @Overwrite.
- SOFT @Overwrite of `ca.spottedleaf.starlight...SchedulingUtil` (Starlight/ScalableLux internals).

**Heavy whole-behavior @Redirect / @WrapOperation:** `ServerChunkManager`, `ChunkHolder`, `ChunkGenerator`, `NoiseChunkGenerator`, `ChunkNoiseSampler` (+ inner caches), `ChunkRegion`, `ChunkSerializer`, `Blender`, `World`, `WorldChunk`, and the many `*Generator$*` structure inner classes.

**Multi-target redirect:** `MixinRedirectAtomicSimpleRandom` redirects random-construction across `NoiseChunkGenerator`, `GeodeFeature`, `ChunkGenerator`, `RandomSpreadStructurePlacement` simultaneously.

---

## 6. Non-mixin core systems

| System | Location | Notes |
|---|---|---|
| **New async chunk system** | `c2me-rewrites-chunk-system/.../chunksystem/common/` — `TheChunkSystem`, `NewChunkHolderVanillaInterface`, `NewChunkStatus`, `ChunkState`, `ChunkLoadingContext`, `TheSpeedyObjectFactory`, `Config`, subpkgs `statuses/` (per-status loaders: ReadFromDisk, Deferred, ServerAccessibleChunkSending, ServerBlockTicking…), `async_chunkio/`, `threadstate/`, `quirks/`, `compat/`, `fapi/`, `structs/`, `ducks/`. | Replaces vanilla ChunkMap state machine; driven by FlowSched. The mixins above just splice it into vanilla. |
| **FlowSched scheduler** | included build `FlowSched/src/main/java/com/ishland/flowsched/` — `scheduler/` (`StatusAdvancingScheduler`, `ItemHolder`, `ItemStatus`, `ItemTicket`, `KeyStatusPair`, `TicketSet`, `BusyRefCounter`, `Cancellable`…) and `executor/` (`ExecutorManager`, `WorkerThread`, `Task`, `LockToken`, `SimpleTask`). | Generic status-advancing async task graph. Backbone of the chunk system. |
| **Density Function Compiler (DFC)** | `c2me-opts-dfc/.../common/` — `ast/`, `gen/` (bytecode gen), `vif/`, `util/`, `ducks/`. | Compiles density functions to bytecode/vectorized form; enabled by port patch 0002. |
| **Native SIMD math** | `c2me-opts-natives-math/src/c/` (CMake: `exports.c`, `flibc.c`, `system_isa_*.c`, per-OS toolchains in `targets/`) + Java `common/isa/ISA_x86_64.java`, `ISA_aarch64.java`, `common/util`, `common/ducks/INativePointer`. Builds `libc2me-opts-natives-math.{so,dll}`. | Hand-written native vector math (no OpenCL); JNI-loaded. Off-heap noise. |
| **Async chunk serializer** | `c2me-rewrites-chunk-serializer/.../common/` (`ChunkDataSerializer`, GC-free serialization) + `c2me-rewrites-chunkio/.../common/` (recreation/versioned storage IO). | Async + allocation-free chunk save/load. |
| **Off-thread lighting** | `c2me-threading-lighting` — integrates Starlight/ScalableLux scheduling. | |
| **Config system** | `c2me-base/.../common/config/ConfigSystem` (exp4j + night-config TOML on Fabric; toml dropped on NeoForge port). | Drives `ModuleMixinPlugin` enable/disable of each module. |

---

## 7. Access wideners / accessors / duck-interfaces

- **Access widener** `c2me-base/src/main/resources/c2me-base.accesswidener` (v1 named) — converted to a NeoForge AccessTransformer at build. Widens ~55 classes/members. Highlights:
  - `accessible class` many inner classes of `ChunkTicketManager`, `ServerChunkManager$MainThreadExecutor`, `StorageIoWorker$Result`, `MaterialRules$*`, all structure-generator inner classes, `ServerChunkLoadingManager$TicketManager`, `ServerLightingProvider$Stage`, **the entire `DensityFunctionTypes$*` family**, `ChunkNoiseSampler$*` caches, `SimpleOption$Callbacks`.
  - `extendable class` `SimpleOption`, `RegionBasedStorage`, `Identifier`.
  - `extendable method` on `ChunkHolder` (combineSavingFuture, setCompletedLevel) and the whole `AbstractChunkHolder` chunk-loading API (generate, createLoader, getOrCreateFuture, unload, completeChunkFuture, getMaxPendingStatus, progressStatus, cannotBeLoaded…).
  - `accessible field` on `ChunkHolder.UNLOADED_WORLD_CHUNK_FUTURE`, `StorageIoWorker$Result`, **`BlockPos` bit-packing constants** (BIT_SHIFT_X/Z, SIZE_BITS_X/Z, BITS_X/Y/Z), `SimplexNoiseSampler.GRADIENTS`, `AquiferSampler$FluidLevel.{y,state}`.
- **Accessor/Invoker interfaces:** the entire `c2me-base.mixin.access.*` package (~50 interfaces, listed in §4) plus `client.uncapvd.ISimpleOption`, `chunk-serializer.IStarlightSaveState`.
- **Duck interfaces injected onto vanilla classes (via mixin `implements`):**
  - dfc: `IArrayCacheCapable`, `IBlendingAwareVisitor`, `ICoordinatesFilling`, `IEqualityOverriding`, `IFastCacheLike` → onto `ChunkNoiseSampler` + inner caches + `DensityFunctionTypes` types.
  - natives-math: `INativePointer` → noise samplers.
  - chunk-system: `IChunkSystemAccess`, `IPOIUnloading`, `TicketDistanceLevelPropagatorExtension` → onto `ServerChunkManager`/`PointOfInterestStorage`/`SerializingRegionBasedStorage`/`ChunkTicketManager`/`ProtoChunk` etc.

---

## 8. License

- `LICENSE` (root) — **MIT**, Copyright (c) 2021-2024 ishland.
- `C2ME-fabric/LICENSE` — **MIT** (same).
- `FlowSched/LICENSE` — **MIT**, Copyright (c) 2023 ishland.

All MIT — permissive, source-merge friendly with attribution.

---

## Deduplicated Minecraft target list (heaviest injection tag)

118 vanilla classes + 11 non-MC/soft targets. See the final returned list / the dedup output for the full machine-readable set. Severity tags: `OVERWRITE` > `REDIRECT` (incl. @WrapMethod) > `MODIFY/WRAP` > `INJECT` > `ACCESSOR`.
