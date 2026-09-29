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

Not a SuperChunk bug. The pack-side fix is `wind_speed` 0 on its large_dripstone, which keeps every
probe within the column radius (≤ 16 blocks). (Since 2026-09-26 SuperChunk also generates these
chunks with the original pack; see the follow-up below.)

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

## Follow-up, 2026-09-26: crashes instead of the freeze, and a 52 MB log

The player kept the original pack and played on with `5c4b28eb`. Two sessions crashed with
"Chunk not there when requested: Unloaded chunk" from the player's own tick (`checkInsideBlocks`,
fluid pushing) at (−1, 802), next to the chunks around (0, 799) that fail. That is the freeze fix
doing what it said: a *creating* `getChunk` for a chunk that can never load now fails instead of
waiting forever, and on the player's tick a failure is a server crash. A new world ("New World (2)")
failed 12 chunks in 30 minutes, and `latest.log` reached 52 MB: 185,587 lines of vanilla's
"Detected setBlock in a far chunk", every one from `minecraft:large_dripstone`.

Fixes (`FeatureRegionReads`, `MixinFeatureOutsideRegion`, `MixinWorldGenRegionFarWrites`):
- A placed feature whose read leaves the decoration region (`WorldGenRegion.getChunk`'s "Requested
  chunk unavailable during world generation") is skipped in that chunk instead of failing it: one
  WARN per feature, then a count at most every 10 minutes. The wrap sits inside vanilla's
  per-feature `try`, so other exceptions reach vanilla's handler unchanged, and the next feature
  reseeds its random. No chunk vanilla can generate changes. With no broken chunk there is nothing
  for a neighbour to wait on, so neither the freeze nor the crash can start this way. Kill switch
  `-Dsuperchunk.worldgen.skipOutOfRegionFeatures=false`.
- `ensureCanWrite` still drops the far writes; the log gets the first per feature and then a count
  at most every 10 minutes. `-Dsuperchunk.worldgen.logEveryFarWrite=true` restores vanilla's lines.
- If a chunk does break some other way, an abandoned wait now fails with a message naming the
  broken chunk ("Chunk not there when requested: chunk [1, 799] can never reach minecraft:full
  because it waits on chunk [0, 799], which failed to generate ...") instead of "Unloaded chunk".

Replays on copies of both worlds (SuperChunk only, `run/jj26/`): force-load the chunks that failed in
the player's game, then their neighbours (a creating `getChunk` on the server thread, the call that
crashed), then a far chunk.

| World | `5c4b28eb` | fixed |
|---|---|---|
| New World (1): (0, 799), (−2, 800), (−11, 814), (−14, 813) + 6 neighbours incl. (−1, 802) | 4 chunks fail; all 10 loads error; failed chunks saved at biomes…carvers, neighbours at initialize_light | every chunk loads and is saved `minecraft:full`; 1 skip warning |
| New World (2): 7 failed chunks + 4 neighbours | 11 errors; 394 far-write lines in 20 s | every chunk `minecraft:full`; 1 skip warning, 1 far-write line |
