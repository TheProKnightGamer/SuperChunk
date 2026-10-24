#!/usr/bin/env python3
"""Compare the light two runs saved (same seed): every chunk that is minecraft:full in both.

Compares each section's SkyLight and BlockLight arrays and Starlight's per-section nibble states
(starlight.skylight_state / starlight.blocklight_state). Differences are bucketed by the chunk's
distance from the edge of the full-chunk area, since the outermost chunks are lit while their
outside neighbours are at different statuses run to run.
usage: lightdiff.py WORLD_A WORLD_B [--dim DIR] [--regions N] [--spawn-exclusion CHUNKS] [--edge N]
Exit status 0 when no chunk at least --edge (default 2) chunks inside the edge differs."""
import collections, sys
from worlddiff import load, parse

LAYERS = (('SkyLight', 'starlight.skylight_state'), ('BlockLight', 'starlight.blocklight_state'))

def light_of(chunk):
    out = {}
    for sec in chunk.get('sections', []):
        y = sec.get('Y')
        for data, state in LAYERS:
            out[(data, y)] = (sec.get(data), sec.get(state))
    return out

def nibble_diffs(x, y):
    if x is None or y is None:
        return 4096
    return sum(((p ^ q) & 0xF != 0) + ((p ^ q) >> 4 != 0) for p, q in zip(x, y))

def main():
    args = sys.argv[1:]
    def opt(name, default):
        if name in args:
            i = args.index(name); v = args[i + 1]; del args[i:i + 2]; return v
        return default
    dim = opt('--dim', 'region')
    limit = int(opt('--regions', 0)) or None
    spawn = int(opt('--spawn-exclusion', 40))
    edge = int(opt('--edge', 2))
    a, b = load(args[0], dim, limit), load(args[1], dim, limit)
    full = {}
    for pos in sorted(set(a) & set(b)):
        if max(abs(pos[0]), abs(pos[1])) < spawn:
            continue
        ca, cb = parse(a[pos]), parse(b[pos])
        if ca.get('Status') == 'minecraft:full' and cb.get('Status') == 'minecraft:full':
            full[pos] = (ca, cb)
    if not full:
        print('no full chunks in common')
        return 1
    xs = [p[0] for p in full]; zs = [p[1] for p in full]
    x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
    by_ring = collections.Counter(); chunks_by_ring = collections.Counter(); first = {}
    fields = collections.Counter(); nibbles = 0
    for pos, (ca, cb) in full.items():
        ring = min(pos[0] - x0, x1 - pos[0], pos[1] - z0, z1 - pos[1])
        ring = min(ring, edge)
        chunks_by_ring[ring] += 1
        la, lb = light_of(ca), light_of(cb)
        bad = False
        for key in set(la) | set(lb):
            va, vb = la.get(key, (None, None)), lb.get(key, (None, None))
            if va == vb:
                continue
            bad = True
            kind = key[0] + (' data' if va[0] != vb[0] else ' state')
            fields[kind] += 1
            if va[0] != vb[0] and ring >= edge:
                nibbles += nibble_diffs(va[0], vb[0])
            first.setdefault(ring, (pos, key))
        if bad:
            by_ring[ring] += 1
    print(f'compared {len(full)} full chunks, x {x0}..{x1}, z {z0}..{z1}')
    for ring in sorted(chunks_by_ring):
        label = f'ring {ring}' if ring < edge else f'interior (>= {edge} from edge)'
        extra = f', first {first[ring]}' if ring in first else ''
        print(f'  {label}: {by_ring[ring]} of {chunks_by_ring[ring]} chunks differ{extra}')
    for kind, n in fields.most_common():
        print(f'  sections with {kind} differing: {n}')
    if by_ring[edge]:
        print(f'  interior nibbles differing: {nibbles}')
        return 1
    print('IDENTICAL (interior)')
    return 0

if __name__ == '__main__':
    sys.exit(main())
