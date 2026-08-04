#!/usr/bin/env python3
"""
Parity census for the post-process neighbour-shape skip (dev.superchunk.mixin.
MixinLevelChunkPostProcessSkip / worldgen.PostProcessNeighbourShapes).

This is the acceptance gate for the census-gated whitelist (Tier 2). It wraps the
SuperChunk worldgen oracle (worldhash.py) — the same bit-exact-generation check the
README uses for every worldgen optimisation — and, on any mismatch, localises the
divergence per dimension with compare_terrain.py so you can see WHICH block/chunk
broke and drop it from the whitelist.

Unlike a synthetic block-by-block differential, this exercises real worldgen: it
catches the context-dependent cases (e.g. LeavesBlock `distance` at chunk borders,
where neighbouring chunks generate in a later step) that a static test cannot.

--------------------------------------------------------------------------------
PRODUCING THE TWO WORLDS (do this on the box with the GPU/CPU worldgen runtime)
--------------------------------------------------------------------------------
Generate the SAME fixed seed twice, same region/radius (Chunky pregen or any
deterministic generation), changing ONLY the post-process flags:

  A = ground truth (all post-process skipping OFF -> verbatim vanilla):
        -Dsuperchunk.worldgen.postProcessSkipNoOp=false

  B = candidate (Tier 1 on by default + Tier 2 whitelist on):
        -Dsuperchunk.worldgen.postProcessSkipWhitelist=true

NOTE ON RELEVANCE: postProcessGeneration runs at the BLOCK_TICKING chunk status.
A pure notickvd pregen holds chunks at SERVER_ACCESSIBLE (= vanilla FULL) and never
post-processes, so to exercise this code path the pregen must bring chunks to a
ticking level (e.g. Chunky's full generation, or load a region near a player).
If worldhash reports identical digests with the flags flipped AND you expected
post-processing to run, confirm your pregen actually reaches BLOCK_TICKING.

--------------------------------------------------------------------------------
USAGE
--------------------------------------------------------------------------------
    python tools/postprocess_parity.py <world_A_baseline> <world_B_candidate>
                                       [--dims=overworld,nether,end]

Exit code 0 = parity holds (digests identical). Exit code 1 = divergence.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable or "python3"

# dimension -> region subdirectory within a world save
DIM_REGION = {
    "overworld": "region",
    "nether": os.path.join("DIM-1", "region"),
    "end": os.path.join("DIM1", "region"),
}

LINE = re.compile(r"^(\w+)\s+.*chunks=\s*(\d+)\s+sha256=([0-9a-f]+)", re.M)


def worldhash(world_dir, dims):
    """Return {name: (chunks, sha)} parsed from worldhash.py, incl. the TOTAL row."""
    cmd = [PY, os.path.join(HERE, "worldhash.py"), world_dir, "--dims", ",".join(dims)]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"worldhash.py failed for {world_dir}:\n{out.stderr}")
    result = {}
    for name, chunks, sha in LINE.findall(out.stdout):
        result[name.lower()] = (int(chunks), sha)
    if "total" not in result:
        sys.exit(f"could not parse worldhash output for {world_dir}:\n{out.stdout}")
    return result


def localize(dim, base_world, cand_world):
    """Run compare_terrain.py on the two region dirs for a mismatching dimension."""
    ra = os.path.join(base_world, DIM_REGION[dim])
    rb = os.path.join(cand_world, DIM_REGION[dim])
    if not (os.path.isdir(ra) and os.path.isdir(rb)):
        print(f"  ({dim}: region dir missing, cannot localize)")
        return
    print(f"  localizing {dim} with compare_terrain.py ...")
    out = subprocess.run(
        [PY, os.path.join(HERE, "compare_terrain.py"), ra, rb],
        capture_output=True, text=True)
    for line in out.stdout.splitlines():
        print("    " + line)
    if out.stderr.strip():
        print("    [stderr] " + out.stderr.strip())


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dims = ["overworld", "nether", "end"]
    for a in sys.argv[1:]:
        if a.startswith("--dims"):
            dims = a.split("=", 1)[1].split(",") if "=" in a else dims
    if len(args) < 2:
        print("usage: python postprocess_parity.py <world_A_baseline> <world_B_candidate> "
              "[--dims=overworld,nether,end]")
        return 2
    base_world, cand_world = args[0], args[1]

    a = worldhash(base_world, dims)
    b = worldhash(cand_world, dims)

    print(f"{'DIM':10s} {'baseline':>10s} {'candidate':>10s}  result")
    ok = True
    for name in sorted(set(a) | set(b)):
        ca, sa = a.get(name, (0, "-"))
        cb, sb = b.get(name, (0, "-"))
        match = (sa == sb and sa != "-")
        ok &= match
        tag = "MATCH" if match else ("MISMATCH" if name != "total" else "== OVERALL ==")
        print(f"{name:10s} {ca:10d} {cb:10d}  {tag}  {sa[:12]}{'' if match else ' != ' + sb[:12]}")
        if not match and name in DIM_REGION:
            localize(name, base_world, cand_world)

    print()
    if ok:
        print("PARITY OK — post-process skip is bit-identical to vanilla over this sample.")
        return 0
    print("PARITY FAILED — a skipped/reduced block changed generation output. "
          "See the localization above; drop the offending block from the Tier-2 whitelist "
          "in PostProcessNeighbourShapes and re-run.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
