# Save and shutdown fixes — 2026-10-08

Prompted by a modpack report that showed only one line:
`[Server thread/ERROR] [net.minecraft.server.MinecraftServer/]: Failed to save chunk 2,18`.

That line is vanilla's generic message, logged by `MinecraftServer.reportChunkSaveFailure`, so it
comes from a 1.21.x build. Forge 1.20.1 logs save failures under `ChunkMap`. With the gc-free
serializer off (always, here), the save runs vanilla `ChunkMap.save`. SuperChunk's storage returns
already-completed write futures, so in this build the line can only come from an exception thrown
inside `save` on the server thread: the serializer, a `ChunkDataEvent.Save` listener, or the
storage call. The cause is in the stack trace that follows the line in the log, which the report
did not include. The stress runs on a 321-mod pack (below) produced the same kind of failure
(section 3) from a mod's block entity. Without the reporter's trace, their chunk 2,18 cannot be
tied to that, or to SuperChunk.

The stress test found three bugs, all fixed here. The third (a mod's block entity throwing on
save) is a reproducible source of that exact line.

## Test bed

Create Chronicles: The Endventure (CurseForge, 321 mods). Dedicated server on NeoForge 21.1.229,
the pack's own version: on 21.1.248 the pack fails to boot with duplicate registry entries from
BOMD and End Remastered, with or without SuperChunk. Client-only mods were removed and
`betterchunkloading` left out (known incompatible). The tests: Chunky pregens with
`save-all` / `save-all flush` sent every few seconds, a rolling forceload churn, armor stands and
chests in forceloaded chunks, `stop` during a pregen, and SIGKILL straight after a flush.

Note: when an unfinished Chunky task is saved, `chunky start` only asks for `chunky confirm`.
Scripts must send it, or no pregen runs.

## 1. `/save-all flush` during chunk loading never finished, and the watchdog killed the server

`opts/scheduling/mixin/shutdown/MixinServerLevel` (upstream C2ME's shutdown fix, ported
2026-09-26) pumped the main-thread queue from `ServerLevel.save` until a fresh
`getAllChunksToSave()` pass came back empty. Each pass ran one queued task, fsynced every entity
region file (`C2MEStorageVanillaInterface.synchronize` ignored its `sync` flag) and slept 10 ms.
The pump also ran chunk loads, and each new chunk's entity load was still pending in the next
pass. While a pregen or fast exploration kept chunks loading, the loop never ended. With the
default `max-tick-time` the watchdog killed the server: "A single server tick took 60.00
seconds", with `superchunk$flushEntitiesPumpingTasks` on the stack. Reproduced on the second
flush during a pregen. With `max-tick-time=-1` one flush was still running after 7 minutes.

Fix: vanilla's own loop now works again.
- `task_scheduling.MixinEntityChunkDataAccess` still runs entity deserialization on the main
  thread. Each task now goes through a per-storage queue, and `EntityStorage.flush` drains that
  queue at its tail, where vanilla drains `entityDeserializerQueue`. Vanilla's
  `PersistentEntitySectionManager.saveAll` loop relies on that drain, and works over a fixed set
  of chunks.
- `C2MEStorageVanillaInterface.synchronize(sync)` now fsyncs only when `sync` is true, as vanilla
  `IOWorker.synchronize` does. Every write is still awaited. Only the entity `saveAll` loop passes
  `false`, and it ends with `true`.
- Removed: the `shutdown` pump (`MixinServerLevel`, `MixinPersistentEntitySectionManager`) and
  `ITryFlushable`.

Results: 25 flushes during a pregen took 0.3–0.5 s each. The watchdog never fired.

Durability: 144 tagged armor stands, one per forceloaded chunk, then `save-all flush`, then
SIGKILL. All 144 were back after a restart. The same test run during a 200 chunks/s pregen also
kept all 144. Loading them back exercises the new deserialization queue.

## 2. Stopping the server during chunk generation skipped the world save

`stop` during a Chunky pregen failed 5 times out of 5 with "Exception stopping the server",
`NullPointerException` in `Long2ObjectOpenHashMap$MapIterator.nextEntry` from
`DistanceManager.removeTicketsOnClosing`. `stopServer` ends there, before `saveAllChunks`, so
no world was saved or closed.

The chain:
1. When the chunk system unloads a holder, FlowSched (`ItemHolder.flushUnloadedStatus`) fails its
   pending futures on a worker thread.
2. `NewChunkHolderVanillaInterface` wrapped them with `handle`, so callbacks ran on that worker.
3. Chunky removes its ticket in `whenCompleteAsync(..., server)`. Once the server is stopped,
   `MinecraftServer.scheduleExecutables()` is false and `execute` runs the task inline, so the
   removal ran on the worker while the main thread iterated the ticket map.
4. A diagnostic build logged 200 such off-thread `removeTicket` calls across the 5 stops.

Vanilla does not hit this because its chunk futures complete on the main thread.

Fix: the three wrappers (`wrapOptionalChunkFuture`, `...ProtoFuture`, `...WorldChunkFuture`) use
`handleAsync` with `TheChunkSystem.vanillaCompletionExecutor`. That executor runs a task at once
on the server thread and queues it there from any other thread. During a pregen, Chunky's FULL
futures already completed on the main thread (9,000 of 9,000 sampled), so those see no change.

Results: 5 out of 5 clean stops with "All dimensions are saved", 0 off-thread ticket changes.

Chunks on disk after `stop` 20 s into a fresh-terrain pregen (radius 400 = 2,601 chunks):

| build | Chunky progress at stop | chunks on disk |
|---|---|---|
| 0.4.1 | 2,601 (incl. cancelled) | 1,926 |
| 0.4.1 | 1,520 | 272 |
| fixed | 2,601 | 2,601 (65 partly generated, saved as proto as vanilla does) |
| fixed | 2,601 | 2,601 |

Upstream C2ME has the same `handle` wrappers and the same pump, so both bugs come from upstream.
forge-1.20.1 has the same code and the same vanilla shutdown sequence (not yet changed there).

## 3. A block entity that throws on save failed the whole unload, reported as a load failure

This is the reproducible source of the reported kind of error. In an End pregen, Iron's
Spellbooks 3.15.6 `PortalFrameBlockEntity.saveAdditional` throws an NPE: it calls
`level.isLoaded(...)` while the block entity sits in a proto chunk with no level.

- **0.4.1:** `ReadFromDiskAsync.asyncSave` builds its `AsyncSerializationManager.Scope` on the
  main thread, before its error handling exists, and the scope serializes block entities. The
  throw failed the whole unload: "Error downgrading chunk [35, 76]", then "Failed to load chunk
  35,76" (wrong direction), and the chunk was marked broken.
- **Fix:** a failure building the scope takes the existing fallback (vanilla `save`). The logs
  then read "Failed to save chunk 35,76 asynchronously, falling back to sync saving" and
  `[Server thread/ERROR] [minecraft/MinecraftServer]: Failed to save chunk 35,76`, with the mod's
  stack trace. That is the reported line, word for word. The unload goes on, and the chunk is
  not saved, as vanilla does when a save throws.
- **Reproduction:** deterministic on a fresh world, seed -987654321, End pregen
  `chunky center 568 1100` radius 100. Chunk 35,76 is in the partly generated border ring.

Vanilla NeoForge with the same pack logs nothing in the same test, for a different reason. It
saved only the 225 full chunks. SuperChunk (C2ME's chunk system) also saves the dependency ring:
928 chunks at structure_starts, 80 at biomes, 72 at carvers and 64 at initialize_light. Saving the
ring's inner row is what runs a mod's block-entity save code on a chunk that has no level. Mod
save bugs on partly generated chunks can therefore surface as "Failed to save chunk" under
SuperChunk but not under vanilla. The cause is still the mod; the stack trace names it.

## Throughput

Identical terrain: fresh world each leg, same seed, Chunky radius 800 at (20000, 20000),
10,201 chunks, alternating builds.

| build | Chunky total time per leg | mean |
|---|---|---|
| 0.4.1 | 57, 57, 57, 58 s | 57.25 s |
| fixed | 58, 57, 57, 57 s | 57.25 s |

## Checked and clean

- `FastPalettePacking` (save) and `FastChunkPaletteRead` (load) both match vanilla step for step,
  and fall back to vanilla on anything unusual.
- The ScalableLux light-save hook catches and logs its own errors.
- The async-serialization block-entity redirects pass straight through on the main thread, where
  no scope is open.
- The biome caches never write to chunks, and `BiomeQuartCache` cannot share an entry between
  managers.
- Main-thread chunk saves have no SuperChunk-specific way to throw that was found.

## Verification

`./gradlew build`: all regression programs pass. The pack tests above used
`build/libs/superchunk-0.4.1.jar` as built.
