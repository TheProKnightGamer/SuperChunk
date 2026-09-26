# JJThunder To The Max: missing chunks and a server freeze — 2026-09-25

Reported from a CurseForge instance (NeoForge 1.21.1, SuperChunk 0.4.0 `062509ef`, Lithium,
Skein, Distant Horizons, JJThunder To The Max 0.9.0 datapack). The logs showed chunks failing with
"Requested chunk unavailable during world generation", and one session froze solid ten seconds
after such a failure.

## What fails, and why only in an explored world

Every crash report is the same: `large_dripstone`, in `moveBackUntilBaseIsInsideStoneAndShrinkRadiusIfNecessary`,
reads a chunk at distance 9 from the one being decorated ("Maximum allowed status: null"); the
features step can read 8. The datapack configures the feature with `floor_to_ceiling_search_range`
512 (vanilla 30) and keeps vanilla's `wind_speed` 0–0.3. The wind shifts each probe sideways by
`wind × (originY − y)`, so across a 512-block cave the probe can land ~160 blocks away — up to 10
chunks. Whether a given dripstone gets that far depends on what its probes read in the chunks 2–8
away, and those are empty in a fresh world but real terrain in an explored one. So the same chunk
generates cleanly in a fresh world and fails in the player's.

Replays of a copy of the player's world, force-loading the six chunks that failed in their game
(`run/jj-world-*`, `run/drive_world.py`):

| Run | Result |
|---|---|
| fresh world, same seed and pack, SuperChunk / vanilla | chunk (0, 799) generates |
| copy of the world, SuperChunk only | all six fail |
| copy of the world, **no mods** (NeoForge only) | fails the same way, then the server thread hangs in `/forceload` |
| copy of the world, pack with `wind_speed` 0, SuperChunk / vanilla | all six generate, saved `minecraft:full` |

Not a SuperChunk bug. The pack fix (wind 0 keeps every probe within the column radius, ≤ 16 blocks)
is in `~/mod_dev/JJThunder_dripstone_fix/`.

## The freeze (SuperChunk bug, fixed)

A chunk whose generation throws is MARK_BROKEN, and since issue #7 its own futures are failed
(`ItemHolder#failPendingFuturesAbove`). Its eight neighbours were not covered: their LIGHT step
needs it at INITIALIZE_LIGHT, dependency satisfaction is ticket-callback only, and a broken item
never upgrades, so they park at INITIALIZE_LIGHT with FULL futures nothing will complete. (The
player's region file shows exactly that ring.) Any server-thread `getChunk` of one of them then
waits forever; in the report it was Lithium's collision sweep as the player flew toward the hole.

Fix: `MixinServerChunkManager#superchunk$abandonUnreachableChunkWait` adds "provably stuck behind a
broken chunk" (`StatusAdvancingScheduler#findBrokenBlocker`, which walks only unsatisfied
dependencies of upgrades in flight) to the wait's stop condition and swaps `getChunk`'s future local
for `UNLOADED_CHUNK_FUTURE`. The neighbour then behaves like the broken chunk itself: `null` for a
non-creating call, "Chunk not there when requested" for a creating one, and one
`SuperChunk-ChunkWait` warning per chunk naming the broken one. With nothing broken the added cost
is one volatile read per poll (`ItemHolder.brokenItemCount()`). Kill switch:
`-Dsuperchunk.chunkSystem.abandonUnreachableWaits=false`.

A/B on a copy of the world with the original pack (`run/drive_freeze.py`): force-load X (0, 799),
which breaks, then its neighbour Y (1, 799), then (2, 799) and (40, 799).

| Jar | Result |
|---|---|
| `062509ef` (0.4.0 as shipped) | server thread hung on Y; `/list` and `stop` never answered |
| fixed | Y: command error + warning naming X; (2, 799) and (40, 799) load; clean stop |
| fixed + Lithium, Skein, Distant Horizons, speedeeentity, ferritecore | same as above |

Vanilla hangs too: in the vanilla replay the server thread waits forever on the failed chunk itself.

Regression test: `SchedulerRegressionTest.checkBrokenDependencyBlocker` drives the real scheduler
through the same shape (item 1 needs item 2 at a status item 2 breaks below), checks the dependent's
future stays pending, and that `findBrokenBlocker` names item 2 and never flags a healthy item.
