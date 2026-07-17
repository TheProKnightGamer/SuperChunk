#!/usr/bin/env python3
"""Compare a single chunk between two world region dirs, component-by-component,
to localize DFC on-vs-off differences (heightmaps vs section block layout)."""
import sys, os, io, zlib, gzip, struct
import nbtlib


def load_chunk(region_dir, cx, cz):
    rx, rz = cx >> 5, cz >> 5
    path = os.path.join(region_dir, 'r.%d.%d.mca' % (rx, rz))
    with open(path, 'rb') as f:
        header = f.read(4096)
        idx = (cx & 31) + (cz & 31) * 32
        off, = struct.unpack('>I', b'\x00' + header[idx*4:idx*4+3])
        if off == 0:
            return None
        f.seek(off * 4096)
        length, = struct.unpack('>I', f.read(4))
        comp = f.read(1)[0]
        data = f.read(length - 1)
        raw = zlib.decompress(data) if comp == 2 else (gzip.decompress(data) if comp == 1 else data)
        return nbtlib.File.parse(io.BytesIO(raw), byteorder='big')


def hm(root, key):
    h = root.get('Heightmaps')
    if h is None:
        return None
    a = h.get(key)
    return list(int(v) for v in a) if a is not None else None


def sections_summary(root):
    out = {}
    for sec in (root.get('sections') or []):
        y = int(sec.get('Y'))
        bs = sec.get('block_states')
        if bs is None:
            continue
        pal = [str(e.get('Name')) for e in (bs.get('palette') or [])]
        data = list(int(v) for v in (bs.get('data') or []))
        out[y] = (pal, data)
    return out


def main():
    d1, d2, cx, cz = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    a = load_chunk(d1, cx, cz)
    b = load_chunk(d2, cx, cz)
    print('chunk %d,%d  status off=%s on=%s' % (cx, cz, a.get('Status'), b.get('Status')))
    for k in ('WORLD_SURFACE', 'OCEAN_FLOOR', 'MOTION_BLOCKING'):
        ha, hb = hm(a, k), hm(b, k)
        print('  heightmap %-15s equal=%s' % (k, ha == hb))
    sa, sb = sections_summary(a), sections_summary(b)
    ys = sorted(set(sa) | set(sb))
    diff_secs = []
    for y in ys:
        pa = sa.get(y); pb = sb.get(y)
        if pa != pb:
            palsame = (pa[0] if pa else None) == (pb[0] if pb else None)
            datasame = (pa[1] if pa else None) == (pb[1] if pb else None)
            diff_secs.append((y, palsame, datasame))
    print('  differing sections (Y, palette_same, data_same):', diff_secs[:20])
    # show palette diff for first differing section
    if diff_secs:
        y = diff_secs[0][0]
        pa = sa.get(y); pb = sb.get(y)
        print('  section Y=%d palette off:' % y, pa[0] if pa else None)
        print('  section Y=%d palette on :' % y, pb[0] if pb else None)


if __name__ == '__main__':
    main()
