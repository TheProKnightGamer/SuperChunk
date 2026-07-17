# NoisiumForked — Mixin Surface Analysis

Source root: `upstream/noisium-forked`
Analyzed for: superchunk source-merge project (conflict mapping).

> **Mapping caveat (read first):** This repo uses **Yarn** mappings, so every `@Mixin`
> target below is written in *Yarn* class names (e.g. `net.minecraft.world.gen.chunk.NoiseChunkGenerator`).
> For cross-referencing against a Mojang-mapped (NeoForge official) codebase, the
> Mojang-name equivalent is given in parentheses in the target table and in the final list.

---

## 1. Build / Loader Facts

All quoted from `gradle.properties` and the `*.gradle` files.

| Fact | Value | Source |
|---|---|---|
| Build tool | **Gradle** (wrapper present) | `gradle/wrapper`, `*.gradle` |
| Multiloader framework | **Architectury** | `build.gradle`: `id "architectury-plugin"`, `id "dev.architectury.loom"` |
| Architectury plugin | `3.4-SNAPSHOT` | `gradle.properties` `architectury_plugin_version=3.4-SNAPSHOT` |
| Architectury Loom | `1.6-SNAPSHOT` | `gradle.properties` `architectury_loom_version=1.6-SNAPSHOT` |
| Enabled platforms | `fabric,neoforge` | `gradle.properties` `enabled_platforms=fabric,neoforge` |
| **Minecraft version** | **`1.21.1`** | `gradle.properties` `minecraft_version=1.21.1` (supported range `>=1.21 <=1.21.1`) |
| **NeoForge version** | **`21.1.22`** | `gradle.properties` `neoforge_version=21.1.22`; `neoforge/build.gradle`: `neoForge "net.neoforged:neoforge:${rootProject.neoforge_version}"` |
| **Mappings** | **Yarn** `1.21.1+build.3` + Architectury yarn→neoforge patch `1.21+build.4` | `build.gradle` `loom.layered { it.mappings("net.fabricmc:yarn:${project.yarn_mappings}:v2"); it.mappings("dev.architectury:yarn-mappings-patch-neoforge:1.21+build.4") }`; `gradle.properties` `yarn_mappings=1.21.1+build.3` |
| **Java version** | **21** | `gradle.properties` `java_version=21`; `options.release = "${rootProject.java_version}"`; mixin config `"compatibilityLevel": "JAVA_21"` |
| Fabric loader (compile/runtime) | `0.15.11` | `gradle.properties` `fabric_loader_version=0.15.11` |
| MixinExtras | `0.3.5` | `gradle.properties` `mixin_extras_version=0.3.5` (common: `mixinextras-common`; fabric bundles `mixinextras-fabric`) |
| Mod version | `2.7.0` | `gradle.properties` `mod_version=2.7.0` |
| Mod id / namespace | `noisium` | `gradle.properties` |
| Group | `io.github.steveplays28` | `gradle.properties` `maven_group` |
| Shadow plugin | `7.1.2` (johnrengelman shadow) — used to shade common into loader jars | fabric/neoforge `build.gradle` |

> **NOTE for superchunk:** Despite the "1.21.11" note in project memory, *this fork targets MC 1.21.1 / NeoForge 21.1.22*. Yarn class/field/method names below are 1.21.1-era.

NeoForge wiring (`neoforge/build.gradle`):
- `architectury { platformSetupLoomIde(); neoForge() }`
- `loom { accessWidenerPath = project(":common").loom.accessWidenerPath }` — **the common AW is applied to NeoForge** and converted to a NeoForge Access Transformer at remap time (`remapJar { atAccessWideners.add(loom.accessWidenerPath.get().asFile.name) }`).
- Common code is shaded in via `shadowCommon(project(path: ":common", configuration: "transformProductionNeoForge"))`.

NeoForge mod metadata (`neoforge/src/main/resources/META-INF/neoforge.mods.toml`):
- `modLoader = "javafml"`, entrypoint class `io.github.steveplays28.noisium.neoforge.NoisiumNeoForge`.
- Mixin config registered: `[[mixins]] config = "noisium-common.mixins.json"` (single shared config).
- Hard incompatibility declared: `biox` (`type = "incompatible"`, "Crashes the game during world generation.").

---

## 2. Source Layout

```
common/                     <-- ALL mixins + all real logic live here (loader-agnostic)
  src/main/java/io/github/steveplays28/noisium/
    Noisium.java                         (init log)
    util/ModUtil.java                    (@ExpectPlatform isModPresent)
    compat/lithium/NoisiumLithiumCompat.java
    mixin/
      ChainedBlockSourceMixin.java
      ChunkSectionMixin.java
      GenerationShapeConfigMixin.java
      NoiseChunkGeneratorMixin.java
      NoisiumMixinPlugin.java            (IMixinConfigPlugin)
      compat/lithium/LithiumNoiseChunkGeneratorMixin.java
  src/main/resources/
    noisium-common.mixins.json           (THE mixin config)
    noisium.accesswidener                (THE access widener)
    architectury.common.json
fabric/
  src/main/java/.../fabric/NoisiumFabric.java
  src/main/java/.../util/fabric/ModUtilImpl.java   (@ExpectPlatform impl)
  src/main/resources/fabric.mod.json
neoforge/
  src/main/java/.../neoforge/NoisiumNeoForge.java
  src/main/java/.../util/neoforge/ModUtilImpl.java (@ExpectPlatform impl)
  src/main/resources/META-INF/neoforge.mods.toml
  src/main/resources/pack.mcmeta
```

**Loader split:** Standard Architectury. 100% of the mixins and worldgen logic are in `common`.
The only platform-specific Java is the entrypoint class and the `ModUtil.isModPresent`
`@ExpectPlatform` implementation (`util/fabric/ModUtilImpl.java`, `util/neoforge/ModUtilImpl.java`).
Both loaders reference the **same single mixin config** `noisium-common.mixins.json`. There are
**no NeoForge-only or Fabric-only mixins** and **no client mixin config** (`"client": []` is empty).

---

## 3. Mixin Configs

Only one config exists. (No `*.neoforge.mixins.json`, no `*.fabric.mixins.json`, no client config.)

### `common/src/main/resources/noisium-common.mixins.json`
```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.steveplays28.noisium.mixin",
  "compatibilityLevel": "JAVA_21",
  "plugin": "io.github.steveplays28.noisium.mixin.NoisiumMixinPlugin",
  "mixins": [
    "ChainedBlockSourceMixin",
    "ChunkSectionMixin",
    "GenerationShapeConfigMixin",
    "NoiseChunkGeneratorMixin",
    "compat.lithium.LithiumNoiseChunkGeneratorMixin"
  ],
  "client": [],
  "injectors": { "defaultRequire": 1 }
}
```
- Package: `io.github.steveplays28.noisium.mixin`
- 5 mixin classes, all server/common side.
- **Mixin plugin** `NoisiumMixinPlugin` gates two mutually-exclusive mixins by Lithium presence (see §4).

---

## 4. MIXIN TARGET TABLE (all mixins enumerated)

Side = common (no client mixins). "Heaviest" = the most conflict-prone injector on that target.

### 4.1 `NoiseChunkGeneratorMixin`
- **File:** `common/.../mixin/NoiseChunkGeneratorMixin.java`
- **`@Mixin` target:** `net.minecraft.world.gen.chunk.NoiseChunkGenerator`
  *(Mojang: `net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator`)*
- Declared `extends ChunkGenerator` (`net.minecraft.world.gen.chunk.ChunkGenerator` / Mojang `net.minecraft.world.level.levelgen.ChunkGenerator`).
- **Activation:** ONLY when Lithium/Canary/Radium is **NOT** loaded (gated by `NoisiumMixinPlugin`).
- Injections:
  | Member | Mechanism | Target method | Notes |
  |---|---|---|---|
  | `noisium$populateNoiseWrapSetBlockStateOperation` | **`@Redirect`** | `populateNoise(Blender, StructureAccessor, NoiseConfig, Chunk, int, int)` redirecting `ChunkSection.setBlockState(IIILBlockState;Z)` | Bypasses `ChunkSection.setBlockState`; writes directly into `blockStateContainer.data.palette` / `.storage()`, manually maintaining `nonEmptyBlockCount`/`nonEmptyFluidCount`/`randomTickableBlockCount`. **Palette-abstraction bypass.** |
  | `method_38332` | **`@Overwrite`** | `method_38332` (lambda used by `populateNoise`, the per-chunk noise/lock body) | Full method replacement. Locks chunk sections via `getSectionArray()`, calls `populateNoise`, unlocks. `@author Steveplays28`. |
  | `populateNoise(...)` | `@Shadow` (abstract) | — | shadow of `populateNoise` so the overwrite can call it. |
- **HEAVIEST: OVERWRITE** (also has a palette-bypass REDIRECT).

### 4.2 `compat.lithium.LithiumNoiseChunkGeneratorMixin`
- **File:** `common/.../mixin/compat/lithium/LithiumNoiseChunkGeneratorMixin.java`
- **`@Mixin` target:** `net.minecraft.world.gen.chunk.NoiseChunkGenerator`
  *(Mojang: `net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator`)* — **same target as 4.1**.
- **Activation:** ONLY when Lithium/Canary/Radium **IS** loaded (mutually exclusive with 4.1 via plugin).
- Injections:
  | Member | Mechanism | Target method | Notes |
  |---|---|---|---|
  | `noisium$populateNoiseWrapSetBlockStateOperation` | **`@Redirect`** | same `populateNoise` → `ChunkSection.setBlockState` redirect | Like 4.1 but does NOT manually bump the count fields (Lithium recalculates). Still a direct palette write. |
  | `method_38332` | **`@Overwrite`** | `method_38332` | Same as 4.1 but additionally calls `chunkSection.calculateCounts()` per section on unlock (Lithium compat). |
  | `populateNoise(...)` | `@Shadow` (abstract) | — | |
- **HEAVIEST: OVERWRITE** (+ palette-bypass REDIRECT).

> The two NoiseChunkGenerator mixins are **never active at the same time** (plugin-gated), but both
> target the same class with the same Overwrite, so for conflict purposes treat
> `NoiseChunkGenerator` / `NoiseBasedChunkGenerator` as **OVERWRITTEN** regardless of env.

### 4.3 `ChunkSectionMixin`
- **File:** `common/.../mixin/ChunkSectionMixin.java`
- **`@Mixin` target:** `net.minecraft.world.chunk.ChunkSection`
  *(Mojang: `net.minecraft.world.level.chunk.LevelChunkSection`)*
- **Activation:** always (no plugin gate).
- Injections:
  | Member | Mechanism | Target method | Notes |
  |---|---|---|---|
  | `populateBiomes` | **`@Overwrite`** | `populateBiomes(BiomeSupplier, MultiNoiseUtil.MultiNoiseSampler, int x, int y, int z)` | Full replacement; reorders the 4x4x4 biome fill loop (axis-order micro-opt) and uses `PalettedContainer.swapUnsafe`. **Biome population rewrite.** `@author Steveplays28`. |
  | `biomeContainer` | `@Shadow` field | `ReadableContainer<RegistryEntry<Biome>> biomeContainer` | |
  | `noisium$sliceSize` | `@Unique` | — | constant 4. |
- **HEAVIEST: OVERWRITE.**

### 4.4 `ChainedBlockSourceMixin`
- **File:** `common/.../mixin/ChainedBlockSourceMixin.java`
- **`@Mixin` target:** `net.minecraft.world.gen.ChainedBlockSource`
  *(Mojang: `net.minecraft.world.level.levelgen.material.MaterialRuleList`)*
- **Activation:** always.
- Injections:
  | Member | Mechanism | Target method | Notes |
  |---|---|---|---|
  | `sample` | **`@Overwrite`** | `sample(DensityFunction.NoisePos)` | Full replacement; `foreach` → indexed `for` micro-opt. `@author Steveplays28`. |
  | `samplers` | `@Shadow @Final` field | `List<ChunkNoiseSampler.BlockStateSampler> samplers` | |
- **HEAVIEST: OVERWRITE.**

### 4.5 `GenerationShapeConfigMixin`
- **File:** `common/.../mixin/GenerationShapeConfigMixin.java`
- **`@Mixin` target:** `net.minecraft.world.gen.chunk.GenerationShapeConfig`
  *(Mojang: `net.minecraft.world.level.levelgen.NoiseSettings`)*
- **Activation:** always.
- Injections (all `@Inject`, no overwrites):
  | Member | Mechanism | Target method | Notes |
  |---|---|---|---|
  | `noisium$createCacheHorizontalAndVerticalCellBlockCountInject` | **`@Inject`** `at TAIL` | `<init>(int,int,int,int)` | Caches `BiomeCoords.toBlock(horizontalSize/verticalSize)` into unique fields. |
  | `noisium$horizontalCellBlockCountGetFromCacheInject` | **`@Inject`** `at HEAD, cancellable` | `horizontalCellBlockCount()` | `cir.setReturnValue(cache)` — short-circuits the getter. |
  | `noisium$verticalCellBlockCountGetFromCacheInject` | **`@Inject`** `at HEAD, cancellable` | `verticalCellBlockCount()` | `cir.setReturnValue(cache)` — short-circuits the getter. |
  | `noisium$horizontalCellBlockCount`, `noisium$verticalCellBlockCount` | `@Unique` fields | — | added int state. |
- **HEAVIEST: INJECT** (cancellable HEAD injects effectively override two getters' return values; conflict-relevant though not `@Overwrite`).

### Mixin plugin gating — `NoisiumMixinPlugin` (`IMixinConfigPlugin`)
```
NoiseChunkGeneratorMixin                       -> apply iff NOT lithium/canary/radium present
compat.lithium.LithiumNoiseChunkGeneratorMixin -> apply iff lithium/canary/radium present
(all others)                                   -> always apply
```
`NoisiumLithiumCompat.isLithiumLoaded()` checks mod ids `lithium`, `canary`, `radium` via
`ModUtil.isModPresent` (`@ExpectPlatform`; NeoForge impl in `util/neoforge/ModUtilImpl.java`).

---

## 5. @Overwrite / Heavy Redirects (CONFLICT-CRITICAL)

These full-method replacements / abstraction bypasses are the high-risk surface for a source merge.

| # | Mixin | Target class (Yarn → Mojang) | Member | Type | Why critical |
|---|---|---|---|---|---|
| 1 | NoiseChunkGeneratorMixin / LithiumNoiseChunkGeneratorMixin | `NoiseChunkGenerator` → `NoiseBasedChunkGenerator` | `method_38332` (populateNoise lock body lambda) | **@Overwrite** | Replaces the chunk-locking noise driver; both variants overwrite it. Any other mod touching `populateNoise` body / chunk-section locking conflicts. |
| 2 | NoiseChunkGeneratorMixin / LithiumNoiseChunkGeneratorMixin | `NoiseChunkGenerator` → `NoiseBasedChunkGenerator` | `populateNoise` → `ChunkSection.setBlockState` | **@Redirect** | **Bypasses the palette abstraction** — writes directly to `PalettedContainer.data.palette` + `.storage()`, manually maintaining `nonEmpty*Count`. Requires the AW-widened `ChunkSection`/`PalettedContainer` internals. Conflicts with any mod redirecting the same `setBlockState` call or relying on it firing. |
| 3 | ChunkSectionMixin | `ChunkSection` → `LevelChunkSection` | `populateBiomes` | **@Overwrite** | Full rewrite of biome population (4³ loop + `swapUnsafe`). Conflicts with any biome-placement mixin. |
| 4 | ChainedBlockSourceMixin | `ChainedBlockSource` → `MaterialRuleList` | `sample` | **@Overwrite** | Full rewrite of the block-state material rule sampler hot path. |
| 5 | GenerationShapeConfigMixin | `GenerationShapeConfig` → `NoiseSettings` | `horizontalCellBlockCount`, `verticalCellBlockCount` | **@Inject HEAD cancellable** (return-value override) | Not `@Overwrite`, but unconditionally replaces two getter return values — behaves like an overwrite for conflict purposes. |

**Palette bypass note:** items #2 are the classic Noisium "bypass palette abstractions" pattern.
They depend entirely on the access-widened internals (§6). If superchunk also writes blockstates
during noise population, these are the lines to reconcile.

---

## 6. Access Wideners / Accessors / Duck Interfaces

### Access Widener — `common/src/main/resources/noisium.accesswidener` (v2 named, Yarn)
Applied to **both** loaders. On NeoForge it is converted to an Access Transformer at remap
(`atAccessWideners.add(...)` in `neoforge/build.gradle`).

```
accessible field net/minecraft/world/chunk/ChunkSection blockStateContainer Lnet/minecraft/world/chunk/PalettedContainer;
accessible field net/minecraft/world/chunk/ChunkSection nonEmptyBlockCount S
accessible field net/minecraft/world/chunk/ChunkSection nonEmptyFluidCount S
accessible field net/minecraft/world/chunk/ChunkSection randomTickableBlockCount S
accessible field net/minecraft/world/chunk/PalettedContainer data Lnet/minecraft/world/chunk/PalettedContainer$Data;
accessible field net/minecraft/world/chunk/PalettedContainer paletteProvider Lnet/minecraft/world/chunk/PalettedContainer$PaletteProvider;
accessible class net/minecraft/world/chunk/PalettedContainer$Data
accessible field net/minecraft/world/chunk/PalettedContainer$Data palette Lnet/minecraft/world/chunk/Palette;
```
Widened types (Yarn → Mojang):
- `ChunkSection` → `LevelChunkSection`: fields `blockStateContainer`(`states`), `nonEmptyBlockCount`, `nonEmptyFluidCount`(`tickingFluidCount`?), `randomTickableBlockCount`(`tickingBlockCount`).
- `PalettedContainer` → `PalettedContainer`: fields `data`, `paletteProvider`; nested class `PalettedContainer$Data` made accessible + its `palette` field.

These exist solely to support the §5 #2 palette-bypass redirects.

### Accessors / Invokers
**None.** No `@Accessor` / `@Invoker` mixins anywhere (verified by grep). Internal access is done
entirely via the access widener/transformer above plus `@Shadow`.

### `@Shadow` members
- `NoiseChunkGeneratorMixin` / `LithiumNoiseChunkGeneratorMixin`: `@Shadow protected abstract Chunk populateNoise(...)`.
- `ChunkSectionMixin`: `@Shadow ReadableContainer<RegistryEntry<Biome>> biomeContainer`.
- `ChainedBlockSourceMixin`: `@Shadow @Final List<ChunkNoiseSampler.BlockStateSampler> samplers`.

### Duck / `@Unique` additions
- **No public duck interfaces.** No `implements`-based interface injection.
- `@Unique` private state only: `ChunkSectionMixin.noisium$sliceSize`;
  `GenerationShapeConfigMixin.noisium$horizontalCellBlockCount` / `noisium$verticalCellBlockCount`.

---

## 7. License

- **`LGPL-3.0`** (`gradle.properties` `mod_license=LGPL-3.0`; declared in `fabric.mod.json` and `neoforge.mods.toml`).
- `LICENSE` file: GNU **Lesser** General Public License v3 (or later). Copyright "(c) 2023-present Darion Spaargaren".

---

## 8. Quick summary for the merge

- 5 mixins, all in `common`, all server/common-side, one shared config.
- Worldgen targets: **NoiseChunkGenerator** (OVERWRITE+REDIRECT, env-gated dual mixin),
  **ChunkSection** (OVERWRITE of `populateBiomes`), **ChainedBlockSource** (OVERWRITE of `sample`),
  **GenerationShapeConfig** (cancellable HEAD injects on two getters).
- Conflict hotspots: palette-bypass redirect into `ChunkSection.setBlockState` during `populateNoise`,
  and the `populateBiomes` rewrite. Both depend on the access widener internals.
- No accessors/invokers, no client mixins, no NeoForge-specific mixins.
