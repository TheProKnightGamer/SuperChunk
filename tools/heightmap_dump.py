#!/usr/bin/env python3
"""Dump per-chunk terrain signatures from a saved world's region files, for
vanilla-accuracy comparison (C2ME DFC on vs off).

Reads .mca region files directly (8KB location header, then zlib/gzip chunk NBT).
For each chunk emits a stable sha256 of terrain-defining data:
  - Heightmaps.WORLD_SURFACE / OCEAN_FLOOR / MOTION_BLOCKING (packed longs)
  - each section's block_states palette names + packed data (block layout)
The chunk x/z come from the NBT xPos/zPos. Status is recorded so the comparison
can restrict to fully-generated chunks.

Usage: python heightmap_dump.py <region_dir> <out_file>
"""
import sys, os, io, zlib, gzip, struct, hashlib
import nbtlib


def read_region(path):
    with open(path, 'rb') as f:
        header = f.read(4096)
        if len(header) < 4096:
            return
        for i in range(1024):
            off, = struct.unpack('>I', b'\x00' + header[i*4:i*4+3])
            sectors = header[i*4+3]
            if off == 0 or sectors == 0:
                continue
            f.seek(off * 4096)
            lb = f.read(4)
            if len(lb) < 4:
                continue
            length, = struct.unpack('>I', lb)
            cb = f.read(1)
            if len(cb) < 1:
                continue
            comp = cb[0]
            data = f.read(length - 1)
            try:
                if comp == 1:
                    raw = gzip.decompress(data)
                elif comp == 2:
                    raw = zlib.decompress(data)
                elif comp == 3:
                    raw = data
                else:
                    continue
                nbt = nbtlib.File.parse(io.BytesIO(raw), byteorder='big')
            except Exception:
                continue
            yield nbt


def chunk_signature(root):
    h = hashlib.sha256()
    hm = root.get('Heightmaps')
    if hm is not None:
        for key in ('WORLD_SURFACE', 'OCEAN_FLOOR', 'MOTION_BLOCKING'):
            arr = hm.get(key)
            if arr is not None:
                h.update(key.encode())
                for v in arr:
                    h.update(struct.pack('>q', int(v)))
    sections = root.get('sections')
    if sections is not None:
        for sec in sections:
            y = sec.get('Y')
            bs = sec.get('block_states')
            if bs is None:
                continue
            h.update(b'Y' + struct.pack('>i', int(y)))
            pal = bs.get('palette')
            if pal is not None:
                for entry in pal:
                    name = entry.get('Name')
                    if name is not None:
                        h.update(str(name).encode())
            d = bs.get('data')
            if d is not None:
                for v in d:
                    h.update(struct.pack('>q', int(v)))
    return h.hexdigest()


def main():
    region_dir, out_file = sys.argv[1], sys.argv[2]
    rows = []
    for fn in os.listdir(region_dir):
        if not fn.endswith('.mca'):
            continue
        for root in read_region(os.path.join(region_dir, fn)):
            try:
                cx = int(root.get('xPos'))
                cz = int(root.get('zPos'))
                status = str(root.get('Status'))
                rows.append((cx, cz, chunk_signature(root), status))
            except Exception as e:
                pass
    rows.sort()
    with open(out_file, 'w') as f:
        for cx, cz, sig, status in rows:
            f.write('%d %d %s %s\n' % (cx, cz, sig, status))
    full = sum(1 for r in rows if r[3] == 'minecraft:full')
    print('wrote %d chunk signatures (%d full) to %s' % (len(rows), full, out_file))


if __name__ == '__main__':
    main()
