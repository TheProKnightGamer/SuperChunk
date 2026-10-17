#!/usr/bin/env python3
"""Compare two -Dsuperchunk.debug.noiseFillHash files: each chunk's noise-fill hash(es) must match.
A chunk's noise fill depends only on its position and the seed, so any mismatch is a bug (the
first one this found: the aquifer cell cache sharing a status vanilla computes per chunk).
Chunks flagged B (structure terrain adaptation: the fill also ran the beardifier) are counted
separately, to see which code a mismatch went through.
usage: noisehashdiff.py A.txt B.txt   Exit status 0 when every chunk in both matches."""
import collections, sys
beard = set()
def load(p):
    m = collections.defaultdict(set)
    for line in open(p):
        parts = line.split()
        if len(parts) >= 3:
            c = (int(parts[0]), int(parts[1]))
            m[c].add(parts[2])
            if len(parts) > 3 and parts[3] == 'B':
                beard.add(c)
    return m
a, b = load(sys.argv[1]), load(sys.argv[2])
common = set(a) & set(b)
bad = [c for c in sorted(common) if a[c] != b[c]]
print(f'chunks: A={len(a)} B={len(b)} common={len(common)} (with structure adaptation: {len(beard & common)}) '
      f'mismatched={len(bad)} (with structure adaptation: {len(set(bad) & beard)})')
for c in bad[:10]:
    print('  MISMATCH', c, 'B' if c in beard else '-', sorted(a[c]), sorted(b[c]))
sys.exit(1 if bad or not common else 0)
