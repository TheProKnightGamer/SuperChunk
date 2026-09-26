#!/usr/bin/env python3
"""Compare the chunks two runs saved (same seed): every chunk that is minecraft:full in both.

Compares decoded block states and biomes per section (palette-order independent), heightmaps,
fluid post-processing lists (order-sensitive: they drive fluid ticks), block/fluid ticks, block
entities and structure starts/references. Ignores timestamps (LastUpdate, InhabitedTime) and
light. usage: worlddiff.py WORLD_A WORLD_B [--dim DIR] [--regions N] [--spawn-exclusion CHUNKS]   (a world dir holding region/)
Exit status 0 when no chunk differs."""
import os, struct, sys, zlib, gzip, collections

def chunks_of(region_path):
    with open(region_path, 'rb') as f:
        data = f.read()
    rx, rz = map(int, os.path.basename(region_path).split('.')[1:3])
    for i in range(1024):
        loc = struct.unpack('>I', data[i * 4:i * 4 + 4])[0]
        sector = loc >> 8
        if not sector:
            continue
        length = struct.unpack('>I', data[sector * 4096:sector * 4096 + 4])[0]
        comp = data[sector * 4096 + 4]
        raw = data[sector * 4096 + 5:sector * 4096 + 4 + length]
        if comp == 2:
            raw = zlib.decompress(raw)
        elif comp == 1:
            raw = gzip.decompress(raw)
        elif comp == 3:
            pass
        else:
            continue
        yield (rx * 32 + (i % 32), rz * 32 + (i // 32)), raw

class R:
    def __init__(s, d): s.d = d; s.p = 0
    def rd(s, n): b = s.d[s.p:s.p + n]; s.p += n; return b
    def u8(s): return s.rd(1)[0]
    def st(s): n = struct.unpack('>H', s.rd(2))[0]; return s.rd(n).decode('utf-8', 'replace')
    def payload(s, t):
        if t == 1: return struct.unpack('>b', s.rd(1))[0]
        if t == 2: return struct.unpack('>h', s.rd(2))[0]
        if t == 3: return struct.unpack('>i', s.rd(4))[0]
        if t == 4: return struct.unpack('>q', s.rd(8))[0]
        if t == 5: return struct.unpack('>f', s.rd(4))[0]
        if t == 6: return struct.unpack('>d', s.rd(8))[0]
        if t == 7: n = struct.unpack('>i', s.rd(4))[0]; return bytes(s.rd(n))
        if t == 8: return s.st()
        if t == 9:
            et = s.u8(); n = struct.unpack('>i', s.rd(4))[0]; return [s.payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = s.u8()
                if tt == 0: return out
                name = s.st(); out[name] = s.payload(tt)
        if t == 11: n = struct.unpack('>i', s.rd(4))[0]; return list(struct.unpack('>%di' % n, s.rd(4 * n)))
        if t == 12: n = struct.unpack('>i', s.rd(4))[0]; return list(struct.unpack('>%dq' % n, s.rd(8 * n)))
        raise ValueError('tag %d' % t)

def parse(raw):
    r = R(raw); t = r.u8(); r.st(); return r.payload(t)

def canon(v):
    if isinstance(v, dict): return tuple(sorted((k, canon(x)) for k, x in v.items()))
    if isinstance(v, list): return tuple(canon(x) for x in v)
    return v

def decode(container, size):
    pal = [canon(p) for p in container.get('palette', [])]
    data = container.get('data')
    if data is None or len(pal) == 1:
        return tuple([pal[0]] * size) if pal else ()
    bits = max((len(pal) - 1).bit_length(), 4 if size == 4096 else 1)
    per = 64 // bits; mask = (1 << bits) - 1
    out = []
    for i in range(size):
        w = data[i // per] & 0xFFFFFFFFFFFFFFFF
        out.append(pal[(w >> ((i % per) * bits)) & mask])
    return tuple(out)

def summary(chunk):
    s = {}
    for sec in chunk.get('sections', []):
        y = sec.get('Y')
        # raw palette+data; decoded only when the raw forms differ (see differs())
        if 'block_states' in sec:
            s[('blocks', y)] = ('raw', 4096, sec['block_states'])
        if 'biomes' in sec:
            s[('biomes', y)] = ('raw', 64, sec['biomes'])
    s['heightmaps'] = canon(chunk.get('Heightmaps', {}))
    s['postprocessing'] = canon(chunk.get('PostProcessing', []))
    s['block_ticks'] = canon(sorted(chunk.get('block_ticks', []), key=lambda t: (t.get('x'), t.get('y'), t.get('z'), t.get('i'))))
    s['fluid_ticks'] = canon(sorted(chunk.get('fluid_ticks', []), key=lambda t: (t.get('x'), t.get('y'), t.get('z'), t.get('i'))))
    s['block_entities'] = canon(sorted(chunk.get('block_entities', []), key=lambda e: (e.get('x'), e.get('y'), e.get('z'))))
    s['structures'] = canon(chunk.get('structures', {}))
    return s

def differs(x, y):
    if isinstance(x, tuple) and x and x[0] == 'raw' and isinstance(y, tuple) and y and y[0] == 'raw':
        if canon(x[2]) == canon(y[2]):
            return False
        return decode(x[2], x[1]) != decode(y[2], y[1])
    return x != y

def load(world, dim, limit=None):
    d = os.path.join(world, dim)
    out = {}
    names = [n for n in sorted(os.listdir(d)) if n.endswith('.mca')]
    for name in names[:limit] if limit else names:
        if name.endswith('.mca'):
            for pos, raw in chunks_of(os.path.join(d, name)):
                out[pos] = raw
    return out

def main():
    args = sys.argv[1:]
    dim = 'region'
    if '--dim' in args:
        i = args.index('--dim'); dim = args[i + 1]; del args[i:i + 2]
    limit = None
    if '--regions' in args:
        i = args.index('--regions'); limit = int(args[i + 1]); del args[i:i + 2]
    # Spawn chunks stay loaded and ticking while the server runs (fluids flow, blocks tick), so
    # they are not pure worldgen output: skip chunks within this many chunks of (0, 0).
    spawn = 40
    if '--spawn-exclusion' in args:
        i = args.index('--spawn-exclusion'); spawn = int(args[i + 1]); del args[i:i + 2]
    a, b = load(args[0], dim, limit), load(args[1], dim, limit)
    for m in (a, b):
        for pos in [p for p in m if max(abs(p[0]), abs(p[1])) < spawn]:
            del m[pos]
    compared = 0; diffs = collections.Counter(); first = {}
    for pos in sorted(set(a) & set(b)):
        ca, cb = parse(a[pos]), parse(b[pos])
        if ca.get('Status') != 'minecraft:full' or cb.get('Status') != 'minecraft:full':
            continue
        compared += 1
        sa, sb = summary(ca), summary(cb)
        for key in set(sa) | set(sb):
            if differs(sa.get(key), sb.get(key)):
                field = key[0] if isinstance(key, tuple) else key
                diffs[field] += 1
                first.setdefault(field, (pos, key))
    print(f'compared {compared} full chunks (A has {len(a)}, B has {len(b)} saved)')
    if not diffs:
        print('IDENTICAL')
        return 0
    for field, n in diffs.most_common():
        print(f'  DIFF {field}: {n} (first at chunk {first[field][0]}, {first[field][1]})')
    return 1

if __name__ == '__main__':
    sys.exit(main())
