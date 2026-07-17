# Lithium (CaffeineMC) — Mixin Surface Analysis

Source analyzed: `upstream/lithium`
Purpose: map Lithium's mixin surface for a source-merge with chunk / worldgen / lighting mods (C2ME, ScalableLux, Noisium). Special attention to **worldgen, chunk load/serialization, chunk ticking, lighting, collision/region**.

> NOTE ON SCOPE: Lithium in this version has **no lighting engine mixins at all** (no `mixin/lighting`, no targets in `net.minecraft.world.level.lighting.*`). Light-related overlap risk with ScalableLux is essentially nil except indirectly through `LevelChunkSection` block-count tracking and heightmap updates. This is called out explicitly below.

---

## 1. Build / loader facts (quoted)

From `build.gradle.kts` (root):
```
id("java")
id("fabric-loom") version ("1.8.9") apply (false)
id("me.modmuss50.mod-publish-plugin") version ("0.8.1") apply (false)
id("net.caffeinemc.mixin-config-plugin") version ("1.0-SNAPSHOT") apply (false)

val MINECRAFT_VERSION by extra { "1.21.1" } //MUST manually update fabric.mod.json and neoforge.mods.toml
val NEOFORGE_VERSION by extra { "21.1.125" }
val FABRIC_LOADER_VERSION by extra { "0.16.4" }
val FABRIC_API_VERSION by extra { "0.103.0+1.21.1" }
val PARCHMENT_VERSION by extra { null }   // Parchment disabled
val MOD_VERSION by extra { "0.15.3" }

java.toolchain.languageVersion = JavaLanguageVersion.of(21)
options.release.set(21)
group = "net.caffeinemc.mods"
```

- **Build tool**: Gradle (Kotlin DSL), multiloader.
- **Minecraft**: **1.21.1** (NOT 1.21.11 — important; targets/descriptors below are 1.21.1).
- **NeoForge**: **21.1.125**, via `net.neoforged.moddev` plugin `2.0.42-beta` (neoforge `build.gradle.kts`).
- **Fabric**: loader 0.16.4, API 0.103.0+1.21.1, `fabric-loom` 1.8.9.
- **Mappings**: **official Mojang mappings** (`officialMojangMappings()`); Parchment is wired but disabled (`PARCHMENT_VERSION = null`). So class/method names in mixins are Mojmap.
- **Java**: 21 (`JAVA_21` mixin compatibilityLevel; toolchain 21; release 21).
- **Mixin refmap**: `lithium.refmap.json`, `useLegacyMixinAp = false`. NeoForge build excludes the refmap (`exclude("/lithium.refmap.json")`) because NeoForge doesn't use a refmap at runtime (Mojmap-native) — see `PlatformRuntimeInformation.platformUsesRefmap()`.

---

## 2. Source layout & common/neoforge split

```
common/    -> shared mixins + logic  (net.caffeinemc.mods.lithium.*)
  src/main/java/net/caffeinemc/mods/lithium/
     mixin/          <- 228 mixin .java files (the bulk; all cross-platform)
     common/         <- non-mixin logic, duck-interfaces, helpers, config, services
     api/ (separate sourceSet 'api')
  src/main/resources/  lithium.mixins.json, lithium.accesswidener, assets/
fabric/    -> 11 fabric-only mixin files + loader entrypoints
  src/main/java/.../fabric/mixin/...
  src/main/resources/lithium-fabric.mixins.json
neoforge/  -> 6 neoforge-only mixin files
  src/main/java/.../neoforge/mixin/...
  src/main/resources/lithium-neoforge.mixins.json
  src/main/resources/META-INF/accesstransformer.cfg   (NeoForge AT, mirrors the AW)
components/mixin-config-plugin/  <- Gradle plugin (build-time) that GENERATES the
                                    per-feature config defaults from annotations
```

How the split works:
- `common` holds 99% of the mixins, all written against vanilla Mojmap classes. It is consumed by both `fabric` and `neoforge` subprojects (the platform jar `from`-copies common's compiled classes + resources).
- Each platform jar declares **two** mixin configs: the common `lithium.mixins.json` plus the platform one (`lithium-fabric.mixins.json` / `lithium-neoforge.mixins.json`).
- Platform abstraction is via a small **service layer** in `common/.../common/services/` (`PlatformRuntimeInformation`, `PlatformMixinOverrides`, `PlatformModCompat`, `PlatformMappingInformation`), with `META-INF/services/...` provider files on each platform. This is what lets the shared `LithiumMixinPlugin` know whether to emit a refmap, and lets other mods push config overrides.
- The NeoForge subproject does NOT use the accesswidener; it ships an equivalent **AccessTransformer** (`neoforge/src/main/resources/META-INF/accesstransformer.cfg`). Fabric uses `lithium.accesswidener`.

---

## 3. Mixin configs & feature packages

Three configs, all using the same plugin `net.caffeinemc.mods.lithium.mixin.LithiumMixinPlugin`, `compatibilityLevel JAVA_21`, `required true`, `defaultRequire 1`. Platform configs additionally set `"overwrites": { "conformVisibility": true }`.

### `common/.../lithium.mixins.json`  (package `net.caffeinemc.mods.lithium.mixin`)
Feature packages (top-level groups; bracket = relevance to merge):
- `ai` — pathing, POI, raids, sensors, brain/behavior tasks (unrelated)
- `alloc` — allocation reduction (chunk_random, composter, entity_iteration, entity_tracker, enum_values, explosion_behavior, nbt, deep_passengers) (mostly unrelated; `chunk_random` touches Level/ServerLevel)
- `block` — fluid flow, hopper (huge subpackage), redstone wire, moving_block_shapes, flatten_states (unrelated except shapes overlap)
- `block_pattern_matching` (unrelated)
- `chunk` — **[CHUNK]** entity_class_groups, no_locking, no_validation, palette, serialization
- `collections` — attributes, block_entity_tickers, brain, chunk_tickets, entity_by_type/filtering/ticking, fluid_submersion, gamerules, mob_spawning (mostly unrelated; chunk_tickets/block_entity_tickers are chunk-adjacent)
- `debug` (client; palette debug) — **[CHUNK]** touches PalettedContainer + chunk packet
- `entity` — collisions **[COLLISION]**, equipment_tracking, inactive_navigations, replace_entitytype_predicates, fast_* checks, sprinting_particles, fast_retrieval
- `experimental` — entity block-caching, item_entity_merging
- `gen` — **[WORLDGEN]** cached_generator_settings (NoiseBasedChunkGenerator)
- `math` — fast_blockpos, fast_util, sine_lut
- `minimal_nonvanilla` — ai sensor, collisions/empty_space **[COLLISION]**, spawning, world/block_entity_ticking, world/expiring_chunk_tickets **[CHUNK]**
- `shapes` — **[COLLISION]** voxel-shape optimizations (blockstate_cache, lazy_shape_context, optimized_matching, precompute_shape_arrays, shape_merging, specialized_shapes)
- `util` — accessors, block_tracking **[CHUNK]**, chunk_access **[CHUNK]**, chunk_status_tracking **[CHUNK]**, entity_*, inventory_*, item_*, block_entity_retrieval, data_storage, world_border_listener
- `world` — **[WORLDGEN/CHUNK]** block_entity_ticking, chunk_access **[CHUNK]**, chunk_ticking, combined_heightmap_update, game_events, inline_block_access, inline_height, raycast, temperature_cache, tick_scheduler **[CHUNK]**

### `fabric/.../lithium-fabric.mixins.json` (package `...fabric.mixin`)
`block.hopper.LevelMixin`, `collections.poi_types.PoiTypesMixin`, `compat.worldedit.LevelChunkMixin` **[CHUNK]**, `entity.collisions.fluid.EntityMixin`, `experimental.entity.block_caching.fluid_pushing.EntityMixin`, `util.inventory_change_listening.BlockEntityMixin`, `util.inventory_change_listening.ChestBlockEntityMixin`.

### `neoforge/.../lithium-neoforge.mixins.json` (package `...neoforge.mixin`)
`block.hopper.LevelMixin`, `chunk_load_tricks.ChunkLoadTricksMixin` (targets a **Lithium-internal** class, not vanilla), `util.inventory_change_listening.ChestBlockEntityMixin`, and client `startup.MinecraftMixin`.

### Orphaned (present on disk but NOT registered in any mixins.json → INACTIVE in this build)
These compile but are not applied; flagged so they aren't mistaken for live conflicts:
- `world/explosions/block_raycast/ExplosionMixin`, `world/explosions/cache_exposure/ExplosionMixin`, `world/explosions/cache_exposure/ExplosionDamageCalculatorMixin`
- `alloc/chunk_ticking/ServerChunkCacheMixin` (target `ServerChunkCache`)
- `profiler/ServerLevelMixin` (target `ServerLevel`)
- `cached_hashcode/Block$BlockStatePairKeyMixin`
- `collections/goals/GoalSelectorMixin`
- `entity/collisions/unpushable_cramming/AbstractMinecartMixin`, `.../BoatMixin`
- `entity/replace_entitytype_predicates/AbstractMinecartMixin`
- `block/hopper/AbstractMinecartMixin`

---

## 4. MIXIN TARGET TABLE

Injection legend: `OW`=@Overwrite, `RED`=@Redirect, `WRAP`=@WrapOperation/@WrapMethod, `MVAR`=@ModifyVariable, `MARG`=@ModifyArg, `MCON`=@ModifyConstant, `INJ`=@Inject, `ACC`=@Accessor/@Invoker only. "Heaviest" listed first when a class has several.

### === [WORLDGEN] / [CHUNK] / [LIGHT] / [COLLISION] — exhaustive ===

#### `gen` (worldgen)
| Mixin | Target (FQ) | Inj | Notes |
|---|---|---|---|
| gen.cached_generator_settings.NoiseBasedChunkGeneratorMixin | `net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator` | **OW** + INJ(`<init>`) | @Overwrite of a generator-settings getter; caches `NoiseGeneratorSettings`/`Aquifer`-related lookups. **High worldgen overlap risk (Noisium/C2ME).** |

#### `chunk` (chunk storage / serialization)
| Mixin | Target (FQ) | Inj | Notes |
|---|---|---|---|
| chunk.serialization.PalettedContainerMixin | `net.minecraft.world.level.chunk.PalettedContainer` | **OW** (`pack(...)`) + INJ(`count`) | Rewrites chunk **serialization/packing** with Lithium hash-palette + compacting array. **High overlap with C2ME chunk IO.** |
| chunk.serialization.SimpleBitStorageMixin | `net.minecraft.util.SimpleBitStorage` | (impl iface) | implements CompactingPackedIntegerArray duck. |
| chunk.no_locking.PalettedContainerMixin | `net.minecraft.world.level.chunk.PalettedContainer` | **OW** (acquire/release) + INJ | Removes thread-lock from palette access. **Conflicts with anything mixing acquire/release.** |
| chunk.no_locking.LevelChunkSectionMixin | `net.minecraft.world.level.chunk.LevelChunkSection` | RED(`setBlockState`) | drops lock acquisition. |
| chunk.no_validation.SimpleBitStorageMixin | `net.minecraft.util.SimpleBitStorage` | RED(get/set/getAndSet) | removes bounds checks. |
| chunk.no_validation.ZeroBitStorageMixin | `net.minecraft.util.ZeroBitStorage` | RED(get/set/getAndSet) | removes bounds checks. |
| chunk.palette.PalettedContainer$StrategyMixin | `net.minecraft.world.level.chunk.PalettedContainer$Strategy` | (impl) | adds Lithium palette strategy. |
| chunk.entity_class_groups.ClassInstanceMultiMapMixin | `net.minecraft.util.ClassInstanceMultiMap` | MVAR(add/remove) | entity grouping. |
| chunk.entity_class_groups.ClientLevelMixin (client) | `net.minecraft.client.multiplayer.ClientLevel` | INJ | |
| debug.palette.PalettedContainerMixin (client) | `net.minecraft.world.level.chunk.PalettedContainer` | (debug) | |
| debug.palette.ClientBoundLevelChunkPacketDataAccessor (client) | `net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData` | ACC | |
| debug.palette.ClientPacketListenerMixin (client) | `net.minecraft.client.multiplayer.ClientPacketListener` | INJ | |

#### `world` (chunk access / heightmap / chunk tick / chunk ticks)
| Mixin | Target (FQ) | Inj | Notes |
|---|---|---|---|
| world.chunk_access.LevelMixin | `net.minecraft.world.level.Level` | **OW** (`getChunk`/`getChunkAt`) | Replaces chunk-lookup path. **[CHUNK] high overlap (C2ME/Moonrise).** |
| world.chunk_access.ServerChunkCacheMixin | `net.minecraft.server.level.ServerChunkCache` | **OW** + INJ(`tick`, `clearCache`) | adds a single-entry chunk cache; @Overwrite of getChunk fast path. **[CHUNK] high overlap.** |
| world.chunk_access.ChunkHolderMixin | `net.minecraft.server.level.ChunkHolder` | INJ | |
| world.chunk_access.GenerationChunkHolderAccessor | `net.minecraft.server.level.GenerationChunkHolder` | ACC (`futures`, invoke `isStatusDisallowed`) | |
| world.combined_heightmap_update.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` | RED + INJ(`setBlockState`) | combines heightmap updates; touches `Heightmap.update`. **[CHUNK] overlaps heightmap-touching mods.** |
| world.combined_heightmap_update.HeightmapAccessor | `net.minecraft.world.level.levelgen.Heightmap` | ACC (`setHeight`, `isOpaque`) | |
| world.inline_block_access.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` (priority 500) | **OW** (`getBlockState`,`getFluidState`) | Hot-path block access rewrite. **[CHUNK] high overlap.** |
| world.inline_block_access.LevelMixin | `net.minecraft.world.level.Level` | **OW** (`getBlockState`) + RED(`getFluidState`) | **[CHUNK] high overlap.** |
| world.inline_height.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` | (impl height) | |
| world.inline_height.LevelMixin | `net.minecraft.world.level.Level` | INJ(`<init>`) | caches min/max build height. |
| world.tick_scheduler.LevelChunkTicksMixin | `net.minecraft.world.ticks.LevelChunkTicks` | **OW ×9** + INJ(`<init>`) | Rewrites the per-chunk scheduled-tick queue. **[CHUNK] overlaps tick-scheduler mods.** |
| world.chunk_ticking.spread_ice.BiomeMixin | `net.minecraft.world.level.biome.Biome` | RED ×3 (`shouldFreeze`) | random-tick ice spread. |
| world.temperature_cache.BiomeMixin | `net.minecraft.world.level.biome.Biome` | **OW** (`getTemperature`) | caches biome temp per BlockPos. |
| world.raycast.BlockGetterMixin | `net.minecraft.world.level.BlockGetter` | **OW** (`clip`) | block raycast rewrite (reads chunk sections directly). **[COLLISION/CHUNK].** |
| world.raycast.ClipContextAccessor | `net.minecraft.world.level.ClipContext` | ACC (`fluid`) | |
| world.game_events.dispatch.GameEventDispatcherMixin | `net.minecraft.world.level.gameevent.GameEventDispatcher` | RED ×3 (`post`) | |
| world.game_events.dispatch.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` | INJ ×3 (`<init>`, listener registry) | |
| world.block_entity_ticking.chunk_tickable.LevelMixin | `net.minecraft.world.level.Level` | RED(`tickBlockEntities` → `shouldTickBlocksAt`) | |
| world.block_entity_ticking.sleeping.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` | INJ | sleeping-BE optimization. |
| world.block_entity_ticking.sleeping.LevelMixin | `net.minecraft.world.level.Level` | WRAP | |
| world.block_entity_ticking.sleeping.ServerLevelMixin | `net.minecraft.server.level.ServerLevel` | RED | |
| world.block_entity_ticking.sleeping.BlockEntityMixin | `net.minecraft.world.level.block.entity.BlockEntity` | INJ(`setChanged`) | |
| world.block_entity_ticking.sleeping.WrappedBlockEntityTickInvokerAccessor | `net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper` | ACC | |
| world.block_entity_ticking.sleeping.{brewing_stand,campfire,campfire.lit,campfire.unlit,crafter,furnace,hopper,shulker_box}.* | resp. `BrewingStandBlockEntity`, `CampfireBlockEntity` (×3), `CrafterBlockEntity`, `AbstractFurnaceBlockEntity`, `HopperBlockEntity`, `ShulkerBoxBlockEntity` (all `net.minecraft.world.level.block.entity.*`) | INJ | per-BE sleep predicates |
| world.block_entity_ticking.world_border.DirectBlockEntityTickInvokerMixin | `net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity` | RED | |

#### `util` chunk/region-adjacent
| Mixin | Target (FQ) | Inj | Notes |
|---|---|---|---|
| util.chunk_access.LevelReaderMixin | `net.minecraft.world.level.LevelReader` | (default-iface) | adds `lithium$getLoadedChunk`-style fast chunk access. **[CHUNK].** |
| util.chunk_access.PathNavigationRegionMixin | `net.minecraft.world.level.PathNavigationRegion` | (impl) | region chunk cache. **[COLLISION/region].** |
| util.chunk_status_tracking.ChunkHolderMixin | `net.minecraft.server.level.ChunkHolder` (priority 1010, "moonrise compat") | INJ | tracks chunk status transitions. **[CHUNK].** |
| util.chunk_status_tracking.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` | INJ | **[CHUNK].** |
| util.block_tracking.LevelChunkSectionMixin | `net.minecraft.world.level.chunk.LevelChunkSection` | INJ + RED(`recalcBlockCounts`, set) | per-section block-type tracking lists. **[CHUNK] — touches section block counts, adjacent to lighting opacity counts.** |
| util.block_tracking.BlockStateBaseMixin | `net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase` (priority 1010) | (impl) | flags block states for tracking. |
| util.block_tracking.BootstrapMixin | `net.minecraft.server.Bootstrap` (priority 1010) | INJ | bootstrap hook to build tracking tables. |
| util.block_entity_retrieval.LevelMixin | `net.minecraft.world.level.Level` | (default-iface) | fast BE retrieval. |
| util.data_storage.LevelMixin | `net.minecraft.world.level.Level` | INJ(`<init>`) | attaches Lithium per-level data. |
| util.world_border_listener.WorldBorderMixin | `net.minecraft.world.level.border.WorldBorder` | (listener) | |

#### `entity.collisions` + `shapes` + `minimal_nonvanilla.collisions` (collision/region)  **[COLLISION]**
| Mixin | Target (FQ) | Inj | Notes |
|---|---|---|---|
| entity.collisions.intersection.LevelMixin | `net.minecraft.world.level.Level` | (impl) | collision iteration. |
| entity.collisions.intersection.EntityGetterMixin | `net.minecraft.world.level.EntityGetter` | RED | |
| entity.collisions.movement.EntityMixin | `net.minecraft.world.entity.Entity` | **OW** + RED ×2 + MVAR | rewrites `collide`/movement collision. **High collision overlap.** |
| entity.collisions.unpushable_cramming.EntityMixin | `net.minecraft.world.entity.Entity` | INJ ×3 | |
| entity.collisions.unpushable_cramming.LivingEntityMixin | `net.minecraft.world.entity.LivingEntity` | RED | |
| entity.collisions.unpushable_cramming.EntitySectionMixin | `net.minecraft.world.level.entity.EntitySection` | INJ(add/remove) | |
| entity.collisions.unpushable_cramming.EntitySelectorMixin | `net.minecraft.world.entity.EntitySelector` | RED | |
| shapes.specialized_shapes.ShapesMixin | `net.minecraft.world.phys.shapes.Shapes` | **OW** | specialized voxel shapes. |
| shapes.specialized_shapes.VoxelShapeMixin | `net.minecraft.world.phys.shapes.VoxelShape` | **OW ×2** | |
| shapes.shape_merging.ShapesMixin | `net.minecraft.world.phys.shapes.Shapes` | INJ | |
| shapes.optimized_matching.ShapesMixin | `net.minecraft.world.phys.shapes.Shapes` | INJ | |
| shapes.blockstate_cache.BlockMixin | `net.minecraft.world.level.block.Block` | **OW** | caches collision/occlusion shapes on block states. |
| shapes.lazy_shape_context.EntityCollisionContextMixin | `net.minecraft.world.phys.shapes.EntityCollisionContext` | MCON ×2 + INJ ×4 | |
| shapes.precompute_shape_arrays.CubePointRangeMixin | `net.minecraft.world.phys.shapes.CubePointRange` | **OW** + INJ(`<init>`) | |
| shapes.precompute_shape_arrays.CubeVoxelShapeMixin | `net.minecraft.world.phys.shapes.CubeVoxelShape` | **OW** + INJ(`<init>`) | |
| block.moving_block_shapes.VoxelShapeMixin | `net.minecraft.world.phys.shapes.VoxelShape` | (cache) | |
| block.moving_block_shapes.PistonMovingBlockEntityMixin | `net.minecraft.world.level.block.entity.PistonMovingBlockEntity` | (cache) | |
| minimal_nonvanilla.collisions.empty_space.LevelMixin | `net.minecraft.world.level.Level` | (impl) | |
| minimal_nonvanilla.collisions.empty_space.ArrayVoxelShapeInvoker | `net.minecraft.world.phys.shapes.ArrayVoxelShape` | ACC | |
| minimal_nonvanilla.collisions.empty_space.BitSetDiscreteVoxelShapeAccessor | `net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape` | ACC | |
| (fabric) entity.collisions.fluid.EntityMixin | `net.minecraft.world.entity.Entity` | (fabric-only) | |

#### `minimal_nonvanilla` chunk/world (chunk tickets / BE ticking)
| Mixin | Target (FQ) | Inj | Notes |
|---|---|---|---|
| minimal_nonvanilla.world.expiring_chunk_tickets.DistanceManagerMixin | `net.minecraft.server.level.DistanceManager` | RED ×2 + INJ ×3 | adds expiring chunk tickets; redirects `SortedArraySet.create`. **[CHUNK] overlaps ticket-system mods.** |
| minimal_nonvanilla.world.block_entity_ticking.support_cache.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` | RED + INJ | |
| minimal_nonvanilla.world.block_entity_ticking.support_cache.BlockEntityMixin | `net.minecraft.world.level.block.entity.BlockEntity` | (impl) | |
| minimal_nonvanilla.world.block_entity_ticking.support_cache.DirectBlockEntityTickInvokerMixin | `net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity` | (impl) | |
| minimal_nonvanilla.spawning.ServerChunkCacheMixin | `net.minecraft.server.level.ServerChunkCache` | RED | spawn-helper hooks. **[CHUNK].** |
| minimal_nonvanilla.spawning.ServerLevelAccessor | `net.minecraft.server.level.ServerLevel` | ACC | |
| minimal_nonvanilla.spawning.EntitySectionAccessor | `net.minecraft.world.level.entity.EntitySection` | ACC | |
| minimal_nonvanilla.spawning.EntitySectionStorageMixin | `net.minecraft.world.level.entity.EntitySectionStorage` | (impl) | |
| minimal_nonvanilla.spawning.PersistentEntitySectionManagerAccessor | `net.minecraft.world.level.entity.PersistentEntitySectionManager` | ACC | |
| (fabric) compat.worldedit.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` | (compat) | **[CHUNK]** WorldEdit compat. |

#### `alloc.chunk_random` (chunk-adjacent)
| Mixin | Target (FQ) | Inj |
|---|---|---|
| alloc.chunk_random.LevelMixin | `net.minecraft.world.level.Level` | (alloc) |
| alloc.chunk_random.ServerLevelMixin | `net.minecraft.server.level.ServerLevel` | (alloc) |

#### `collections` chunk-adjacent
| Mixin | Target (FQ) | Inj |
|---|---|---|
| collections.block_entity_tickers.LevelChunkMixin | `net.minecraft.world.level.chunk.LevelChunk` | (collection swap) **[CHUNK]** |
| collections.chunk_tickets.SortedArraySetMixin | `net.minecraft.util.SortedArraySet` | (collection) — used by ticket system |

---

### === Unrelated packages (target classes listed, grouped) ===

**ai** (pathing / POI / raid / sensors / tasks):
`HoglinSpecificSensor`, `MoveToBlockGoal`, `PiglinSpecificSensor`, `RemoveBlockGoal` (block-search) — INJ/RED; `BlockBehaviour$BlockStateBase` (priority, pathing flags); `Bootstrap` (INJ); `FlyNodeEvaluator`; `PathfindingContext` (+Accessor); `PathNavigationRegion` (**OW**); `WalkNodeEvaluator` (priority 990); `PoiManager` (**OW**, ×2 incl fast_portals); `PoiSection`; `SectionStorage`; `PortalForcer` (**OW**); `LocateHidingPlace`; `Raider$RaiderMoveThroughVillageGoal` (targets); `Raider`, `Raider$ObtainRaidLeaderBannerGoal`, `Raid` (**OW**); `SecondaryPoiSensor`; `Brain` (**OW** launch + memory_counting + useless_sensors ACC); `Behavior` (**OW**); `GateBehavior` (**OW**); `ShufflingList`; `LongJumpToRandomPos`; `Sensor` (ACC); `Goat`; `AgeableMob`. All under `net.minecraft.world.entity.ai.*`, `...village.poi.*`, `...entity.raid.*`, `...entity.animal.*`.

**alloc** (besides chunk_random above): `ComposterBlock$InputContainer` (targets); `Entity` (deep_passengers); `ClassInstanceMultiMap` (ACC); `EntitySection`; `ChunkMap$TrackedEntity`; `PistonBaseBlock`; `PistonStructureResolver`; `RedStoneWireBlock`; `EntityBasedExplosionDamageCalculator` (**OW**); `CompoundTag` (**OW**).

**block** (besides shapes above): `FluidState` (**OW**, flatten_states); `FlowingFluid` (**OW**, fluid flow); hopper subpackage → `AbstractContainerMenu`, `BlockBehaviour`, `ChestBoat`, `ChiseledBookShelfBlockEntity`, `ClassInstanceMultiMap`, `ComposterBlock$InputContainer`, `CompoundContainer` (ACC), `Container`, `Entity` (ACC), `EntityDataAccessor`, `EntitySection` (ACC), `HopperBlockEntity` (priority 950), `HopperBlock`, `AbstractFurnaceBlockEntity`+`AbstractMinecartContainer`+`BarrelBlockEntity`+`BrewingStandBlockEntity`+`ChestBlockEntity`+`DispenserBlockEntity`+`HopperBlockEntity`+`ShulkerBoxBlockEntity` (InventoryAccessors, ACC), `NonNullList` (ACC), plus fabric/neoforge `Level` (hopper.LevelMixin); `RedStoneWireBlock` (redstone_wire).

**block_pattern_matching**: `BlockPattern`, `EndDragonFight`.

**collections** (besides chunk above): `AttributeMap`; `Brain`; `ClassInstanceMultiMap` (entity_by_type / entity_filtering **OW**); `EntityTickList` (**OW**); `Entity` (fluid_submersion); `GameRules`; `MobSpawnSettings`; `WeightedRandomList` (**OW**); (fabric) `PoiTypes`.

**entity** (non-collision): `ArmorStand`, `LivingEntity` (×many: equipment_tracking, enchantment_ticking, equipment_changes, inactive_navigations, fast_elytra_check, fast_hand_swing, fast_powder_snow_check), `Mob` (equipment_tracking, inactive_navigations), `Drowned`, `Drowned$DrownedGoToBeachGoal` (targets), `PathNavigation`, `ServerLevel`, `ServerLevel$EntityCallbacks`, `HangingEntity`, `ItemFrame`, `LlamaFollowCaravanGoal`, `GolemRandomStrollInVillageGoal`, `Entity` (sprinting_particles), `EntitySectionStorage` (fast_retrieval).

**experimental**: `Entity` (block_caching: base + block_support + block_touching + fire_lava_touching + suffocation; fabric fluid_pushing), `ItemEntity` (item_entity_merging).

**math**: `BlockPos` (**OW**), `Direction` (**OW** ×2), `AABB` (**OW**), `AxisCycle$2` & `AxisCycle$3` (targets, **OW**), `Mth` (**OW**, sine_lut).

**minimal_nonvanilla.ai**: `FrogAttackablesSensor`.

**util** (non-chunk): accessors — `EntitySection`, `ItemEntity`, `ItemStack`, `Level`, `PersistentEntitySectionManager`, `ServerLevel`, `TransientEntitySectionManager` (client) (all ACC); entity_collection_replacement `ClassInstanceMultiMap`; entity_movement_tracking — `EntitySection` (MVAR), `PersistentEntitySectionManager` (ACC), `PersistentEntitySectionManager$Callback` (targets), `ServerLevel` (ACC); entity_section_position — `EntitySection`, `EntitySectionStorage`; inventory_change_listening — `BaseContainerBlockEntity`, `BlockEntity`, + StackListReplacementTracking on `AbstractFurnaceBlockEntity`/`BarrelBlockEntity`/`BaseContainerBlockEntity`/`BrewingStandBlockEntity`/`ChestBlockEntity`/`DispenserBlockEntity`/`HopperBlockEntity`/`ShulkerBoxBlockEntity`, + (fabric/neoforge) `ChestBlockEntity`, (fabric) `BlockEntity`; inventory_comparator_tracking — `BlockEntity`, `DiodeBlock`; item_component_and_count_tracking — `ItemEntity`, `ItemStack`, `PatchedDataComponentMap`.

**neoforge platform**: `chunk_load_tricks.ChunkLoadTricksMixin` → **`net.caffeinemc.mods.lithium.common.world.ChunkLoadTricks`** (Lithium-internal, **OW** of own method — NOT a vanilla target); `startup.MinecraftMixin` → `net.minecraft.client.Minecraft` (INJ, logs/compat at startup).

---

## 5. @Overwrite / heavy redirects (flagged)

**@Overwrite** (full method replacement — highest merge-conflict risk). Files containing `@Overwrite`:
- **Priority domains**:
  - `chunk/serialization/PalettedContainerMixin` (`pack`) **[CHUNK]**
  - `chunk/no_locking/PalettedContainerMixin` **[CHUNK]**
  - `gen/cached_generator_settings/NoiseBasedChunkGeneratorMixin` **[WORLDGEN]**
  - `world/chunk_access/LevelMixin`, `world/chunk_access/ServerChunkCacheMixin` **[CHUNK]**
  - `world/inline_block_access/LevelChunkMixin`, `world/inline_block_access/LevelMixin` **[CHUNK]**
  - `world/tick_scheduler/LevelChunkTicksMixin` (×9) **[CHUNK]**
  - `world/temperature_cache/BiomeMixin`, `world/raycast/BlockGetterMixin`
  - `entity/collisions/movement/EntityMixin` **[COLLISION]**
  - `shapes/specialized_shapes/{ShapesMixin,VoxelShapeMixin}`, `shapes/blockstate_cache/BlockMixin`, `shapes/precompute_shape_arrays/{CubePointRangeMixin,CubeVoxelShapeMixin}` **[COLLISION]**
- **Unrelated**: `ai/pathing/PathNavigationRegionMixin`, `ai/poi/{PoiManagerMixin,fast_portals/PoiManagerMixin,fast_portals/PortalForcerMixin}`, `ai/raid/RaidMixin`, `ai/task/launch/BrainMixin`, `ai/task/memory_change_counting/BehaviorMixin`, `ai/task/replace_streams/GateBehaviorMixin`, `alloc/composter/ComposterMixin`, `alloc/deep_passengers/EntityMixin`, `alloc/explosion_behavior/EntityBasedExplosionDamageCalculatorMixin`, `alloc/nbt/CompoundTagMixin`, `block/flatten_states/FluidStateMixin`, `block/fluid/flow/FlowingFluidMixin`, `collections/entity_filtering/ClassInstanceMultiMapMixin`, `collections/entity_ticking/EntityTickListMixin`, `collections/mob_spawning/WeightedRandomListMixin`, `math/fast_blockpos/{BlockPosMixin,DirectionMixin}`, `math/fast_util/{AABBMixin,AxisCycleDirectionMixin,DirectionMixin}`, `math/sine_lut/MthMixin`, and `cached_hashcode/Block$BlockStatePairKeyMixin` (orphaned).

Platform configs set `"overwrites": { "conformVisibility": true }` so @Overwrite methods inherit target visibility.

**Heavy @Redirect / @WrapOperation hotspots** in priority domains: `world/combined_heightmap_update/LevelChunkMixin` (heightmap update redirect inside `setBlockState`), `world/game_events/dispatch/GameEventDispatcherMixin` (×3), `minimal_nonvanilla/world/expiring_chunk_tickets/DistanceManagerMixin` (redirect `SortedArraySet.create`), `chunk/no_validation/*` (redirect all bit-storage get/set), `chunk/no_locking/LevelChunkSectionMixin` (redirect setBlockState), `entity/collisions/movement/EntityMixin` (×2 redirect + modifyvar).

---

## 6. Lithium config system (mixin toggling) — critical for merge

Lithium gates EVERY mixin through its own per-feature option system, implemented by `LithiumMixinPlugin` (`common/.../mixin/LithiumMixinPlugin.java`) + `LithiumConfig` (`common/.../common/config/LithiumConfig.java`) + `Option`.

How it works:
1. `LithiumMixinPlugin.shouldApplyMixin(target, mixinClass)` is called by the Mixin subsystem for every mixin. It strips the package root (`net.caffeinemc.mods.lithium.mixin.` / `.fabric.mixin.` / `.neoforge.mixin.`) to get a dotted feature path, then asks `CONFIG.getEffectiveOptionForMixin(path)`.
2. **Option names = mixin sub-package paths**, prefixed `mixin.` (e.g. `mixin.world.tick_scheduler`, `mixin.chunk.serialization`). `getEffectiveOptionForMixin` walks the package path from root to leaf; the **effective** option is "disabled at the earliest disabled ancestor, else the deepest matching rule." So disabling `mixin.chunk` disables every `chunk.*` mixin; disabling `mixin.world.tick_scheduler` disables just that feature.
3. Defaults come from a generated resource `/assets/lithium/lithium-mixin-config-default.properties` and dependencies from `/assets/lithium/lithium-mixin-config-dependencies.properties`. **These are generated at build time** by the `components/mixin-config-plugin` Gradle plugin (`net.caffeinemc.gradle.CreateMixinConfigTask`) by scanning `@MixinConfigOption(description=...)` annotations placed in each feature package's `package-info.java`. They are NOT committed to source. (The neoforge build wires `neoforgeCreateMixinConfig` → outputs to `assets/lithium`, also emits a `lithium-neoforge-mixin-config.md` summary.)
4. **Dependencies**: a feature can require another to be enabled/disabled (`addRuleDependency`). `applyDependencies()` iterates to a fixpoint, disabling options whose deps aren't met. (Example pattern: caching features depending on the tracking feature that feeds them.)
5. **User config**: `./config/lithium.properties` (loaded in `onLoad`). Keys are the option names; values true/false. Missing file → a default (empty + notice) file is written.
6. **Other mods can override** options at runtime through `PlatformMixinOverrides.applyModOverrides()` (per-platform service) and `applyLithiumCompat(options)` — disabling takes precedence over enabling. This is the mechanism a sibling perf mod (or your merged mod) would use to turn Lithium features off to avoid conflicts.

**Merge implication**: you can disable any conflicting Lithium feature *surgically* by feeding a config rule (user `lithium.properties` OR a `PlatformMixinOverrides` provider) — e.g. set `mixin.chunk.serialization=false`, `mixin.world.chunk_access=false`, `mixin.gen.cached_generator_settings=false`, `mixin.world.tick_scheduler=false` to neutralize the highest-risk overlaps with C2ME/Noisium without forking. If a mixin's feature path has no matching rule, the plugin **disables it** ("treating as foreign and disabling") — so the generated defaults file is mandatory at runtime.

---

## 7. Access wideners / accessors / duck-interfaces

**AccessWidener** (Fabric): `common/src/main/resources/lithium.accesswidener` (`accessWidener v1 named`). Key entries (chunk/shape/region relevant in **bold**):
- classes: `ChunkMap$TrackedEntity`, `ServerChunkCache$MainThreadExecutor`, `ServerLevel$EntityCallbacks`, `WorldBorder$BorderExtent`, **`PaletteResize`**, **`PalettedContainer$Configuration`**, **`PalettedContainer$Data`**, **`IndexMerger`** (voxel shapes).
- fields: `Ticket.createdTick`, `Mth.SIN`, **`VoxelShape.shape`** (DiscreteVoxelShape), `ChunkMap.level`.
- methods: `CompoundTag.<init>(Map)`, `SortedArraySet.<init>(int,Comparator)`, `PoiSection.isValid()`, **`PalettedContainer$Configuration.<init>`**, **`PalettedContainer$Strategy.<init>(int)`**, **`PalettedContainer$Strategy.calculateBitsForSerialization`**, `Fluid.isEmpty()`, `WalkNodeEvaluator.getPathTypeFromState`, **`Shapes.findBits(DD)`**, `SavedTick.saveTick(...)`.

**AccessTransformer** (NeoForge): `neoforge/src/main/resources/META-INF/accesstransformer.cfg` — mirrors the AW (Mojmap `public` lines) and adds a few NeoForge-only ones: `FlowingFluid$BlockStatePairKey`, `RedstoneWireEvaluator.getWireSignal`, `ExperimentalRedstoneWireEvaluator.getWireSignal` (NeoForge split the redstone evaluator out). `Fluid.isEmpty()` is commented out on NeoForge.

**Accessor/Invoker-only mixins** (no behavior change, lowest conflict risk): all `*Accessor`/`*Invoker` classes — e.g. `util/accessors/*` (Level, ServerLevel, EntitySection, ItemEntity, ItemStack, PersistentEntitySectionManager, TransientEntitySectionManager), `world/chunk_access/GenerationChunkHolderAccessor`, `world/combined_heightmap_update/HeightmapAccessor`, `world/raycast/ClipContextAccessor`, `chunk` palette accessors via AW, `minimal_nonvanilla/collisions/empty_space/{ArrayVoxelShapeInvoker,BitSetDiscreteVoxelShapeAccessor}`, hopper `*Accessor`s, etc.

**Duck interfaces** (Lithium attaches behavior to vanilla classes via interfaces implemented by mixins, in `common/.../common/`): packages `common/world`, `common/block`, `common/entity`, `common/hopper`, `common/shapes`, `common/tracking`, `common/util`, `common/ai`, `common/client`, `common/reflection`. These are the `lithium$`-prefixed extension interfaces (e.g. block-tracking section interfaces, chunk-access fast getters, sleeping-block-entity tick logic, hash-palette `LithiumHashPalette`, `CompactingPackedIntegerArray`). When merging, these interfaces + their `lithium$`-namespaced methods are what other mixins call into.

---

## 8. License

**GNU Lesser General Public License v3 (LGPL-3.0)** — `LICENSE.md` (full LGPLv3 text; "This version of the GNU Lesser General Public License incorporates the terms and conditions of version 3 of the GNU General Public License, supplemented by the additional permissions"). Relevant for source-merge/redistribution: LGPL is copyleft on the library; combining/derivative source must respect LGPLv3 terms.

---

## Summary for merge planning

- **Lighting**: no overlap (Lithium has no lighting mixins here).
- **Worldgen**: single hotspot — `NoiseBasedChunkGenerator` (@Overwrite). Toggle `mixin.gen.cached_generator_settings`.
- **Chunk storage/IO**: high overlap — `PalettedContainer` (serialization @Overwrite, no_locking @Overwrite), `SimpleBitStorage`/`ZeroBitStorage`, `LevelChunkSection`, `LevelChunkTicks` (9× @Overwrite). Toggle `mixin.chunk.*`, `mixin.world.tick_scheduler`.
- **Chunk access/ticketing**: high overlap — `Level`/`ServerChunkCache`/`LevelChunk` (@Overwrite getChunk/getBlockState fast paths), `ChunkHolder`, `DistanceManager`. Toggle `mixin.world.chunk_access`, `mixin.world.inline_block_access`, `mixin.util.chunk_*`, `mixin.minimal_nonvanilla.world.expiring_chunk_tickets`.
- **Collision/region**: `Shapes`/`VoxelShape`/`Entity` movement (@Overwrite), `PathNavigationRegion`. Toggle `mixin.shapes.*`, `mixin.entity.collisions.*`.
- Use Lithium's own config (`lithium.properties` or a `PlatformMixinOverrides` service) to disable overlapping features rather than editing mixins.
