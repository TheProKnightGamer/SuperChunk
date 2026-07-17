#!/usr/bin/env python3
"""Order-independent terrain comparison between two world snapshots.

For each chunk computes:
  - heightmaps WORLD_SURFACE / OCEAN_FLOOR / MOTION_BLOCKING (exact, terrain shape)
  - per-section block MULTISET: decode the packed block_states into a per-block
    count keyed by block Name (palette-order-independent), so two identical
    terrains with differently-ordered palettes compare equal.

Compares two region dirs over chunks at minecraft:full in both, reporting:
  - HEIGHTMAP match/mismatch  (the strict terrain-shape signal)
  - BLOCKS match/mismatch     (order-independent block layout per section)

Usage: python compare_terrain.py <regionA> <regionB>
"""
import sys, os, io, zlib, gzip, struct, hashlib
import nbtlib


def iter_chunks(region_dir):
    for fn in os.listdir(region_dir):
        if not fn.endswith('.mca'):
            continue
        path = os.path.join(region_dir, fn)
        with open(path, 'rb') as f:
            header = f.read(4096)
            if len(header) < 4096:
                continue
            for i in range(1024):
                off, = struct.unpack('>I', b'\x00' + header[i*4:i*4+3])
                if off == 0 or header[i*4+3] == 0:
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
                    raw = zlib.decompress(data) if comp == 2 else (gzip.decompress(data) if comp == 1 else data)
                    nbt = nbtlib.File.parse(io.BytesIO(raw), byteorder='big')
                    yield int(nbt.get('xPos')), int(nbt.get('zPos')), nbt
                except Exception:
                    continue


def heightmap_sig(root):
    h = hashlib.sha256()
    hm = root.get('Heightmaps')
    if hm is not None:
        for key in ('WORLD_SURFACE', 'OCEAN_FLOOR', 'MOTION_BLOCKING'):
            a = hm.get(key)
            if a is not None:
                h.update(key.encode())
                for v in a:
                    h.update(struct.pack('>q', int(v)))
    return h.hexdigest()


def decode_section_blocks(sec):
    """Return a dict {block_name: count} for the 4096 cells of a section,
    decoding the packed long array (order-independent block multiset)."""
    bs = sec.get('block_states')
    if bs is None:
        return None
    pal = [str(e.get('Name')) for e in (bs.get('palette') or [])]
    if not pal:
        return None
    data = bs.get('data')
    counts = {}
    if data is None:
        # single-entry palette: all 4096 cells are pal[0]
        counts[pal[0]] = 4096
        return counts
    bits = max(4, (len(pal) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    longs = [int(v) & 0xFFFFFFFFFFFFFFFF for v in data]
    n = 0
    for lv in longs:
        for s in range(per_long):
            if n >= 4096:
                break
            idx = (lv >> (s * bits)) & mask
            if idx < len(pal):
                name = pal[idx]
                counts[name] = counts.get(name, 0) + 1
            n += 1
        if n >= 4096:
            break
    return counts


def blocks_sig(root):
    """Order-independent per-section block multiset signature."""
    h = hashlib.sha256()
    secs = {}
    for sec in (root.get('sections') or []):
        y = int(sec.get('Y'))
        c = decode_section_blocks(sec)
        if c is not None:
            secs[y] = c
    for y in sorted(secs):
        h.update(struct.pack('>i', y))
        for name in sorted(secs[y]):
            h.update(name.encode())
            h.update(struct.pack('>i', secs[y][name]))
    return h.hexdigest()


def load(region_dir):
    out = {}
    for cx, cz, nbt in iter_chunks(region_dir):
        if str(nbt.get('Status')) != 'minecraft:full':
            continue
        out[(cx, cz)] = (heightmap_sig(nbt), blocks_sig(nbt))
    return out


def main():
    A = load(sys.argv[1])
    B = load(sys.argv[2])
    common = set(A) & set(B)
    hm_match = hm_mis = bl_match = bl_mis = 0
    hm_ex = []; bl_ex = []
    for k in common:
        if A[k][0] == B[k][0]:
            hm_match += 1
        else:
            hm_mis += 1
            if len(hm_ex) < 10: hm_ex.append(k)
        if A[k][1] == B[k][1]:
            bl_match += 1
        else:
            bl_mis += 1
            if len(bl_ex) < 10: bl_ex.append(k)
    print('COMMON_FULL=%d' % len(common))
    print('HEIGHTMAP  MATCH=%d MISMATCH=%d' % (hm_match, hm_mis))
    if hm_ex: print('  hm mismatch examples:', hm_ex)
    print('BLOCKS     MATCH=%d MISMATCH=%d' % (bl_match, bl_mis))
    if bl_ex: print('  blocks mismatch examples:', bl_ex)


if __name__ == '__main__':
    main()
