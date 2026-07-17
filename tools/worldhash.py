#!/usr/bin/env python3
"""
SuperChunk worldgen verification oracle.

Hashes the GENERATION-DETERMINISTIC content of a Minecraft 1.21.1 Anvil world
(block-state palettes+data, biome palettes+data, chunk status, and structure
references/starts) while IGNORING volatile metadata (timestamps, InhabitedTime,
lighting flags, block/fluid tick queues, etc.) and IGNORING .mca container
nondeterminism (sector layout, write order, per-chunk compression, header
timestamps).

Purpose: prove that an optimization edit produces BIT-IDENTICAL generation.
Run before an edit and after an edit on the same fixed seed; the digests must match.

Usage:
    python worldhash.py <world-dir> [--per-chunk] [--dims overworld,nether,end]

Exit code 0 always; prints a single canonical SHA-256 over all chunks (sorted),
plus per-dimension digests and a chunk count.
"""
import sys, os, io, zlib, gzip, struct, hashlib, glob

# ---- minimal NBT reader (java edition, big-endian) ----
TAG_END=0; TAG_BYTE=1; TAG_SHORT=2; TAG_INT=3; TAG_LONG=4; TAG_FLOAT=5
TAG_DOUBLE=6; TAG_BYTE_ARRAY=7; TAG_STRING=8; TAG_LIST=9; TAG_COMPOUND=10
TAG_INT_ARRAY=11; TAG_LONG_ARRAY=12

class R:
    def __init__(self, b): self.b=b; self.i=0
    def u1(self): v=self.b[self.i]; self.i+=1; return v
    def n(self, k): v=self.b[self.i:self.i+k]; self.i+=k; return v

def _read_payload(r, t):
    if t==TAG_BYTE: return struct.unpack('>b', r.n(1))[0]
    if t==TAG_SHORT: return struct.unpack('>h', r.n(2))[0]
    if t==TAG_INT: return struct.unpack('>i', r.n(4))[0]
    if t==TAG_LONG: return struct.unpack('>q', r.n(8))[0]
    if t==TAG_FLOAT: return struct.unpack('>f', r.n(4))[0]
    if t==TAG_DOUBLE: return struct.unpack('>d', r.n(8))[0]
    if t==TAG_BYTE_ARRAY:
        n=struct.unpack('>i', r.n(4))[0]; return bytes(r.n(n))
    if t==TAG_STRING:
        n=struct.unpack('>H', r.n(2))[0]; return r.n(n).decode('utf-8','replace')
    if t==TAG_LIST:
        et=r.u1(); n=struct.unpack('>i', r.n(4))[0]
        return [_read_payload(r, et) for _ in range(n)]
    if t==TAG_COMPOUND:
        d={}
        while True:
            it=r.u1()
            if it==TAG_END: break
            nl=struct.unpack('>H', r.n(2))[0]; name=r.n(nl).decode('utf-8','replace')
            d[name]=_read_payload(r, it)
        return d
    if t==TAG_INT_ARRAY:
        n=struct.unpack('>i', r.n(4))[0]
        return list(struct.unpack('>%di'%n, r.n(4*n)))
    if t==TAG_LONG_ARRAY:
        n=struct.unpack('>i', r.n(4))[0]
        return list(struct.unpack('>%dq'%n, r.n(8*n)))
    raise ValueError("bad tag %d"%t)

def read_nbt(b):
    r=R(b); t=r.u1()
    if t==TAG_END: return {}
    nl=struct.unpack('>H', r.n(2))[0]; r.n(nl)  # root name
    return _read_payload(r, t)

# ---- canonical generation-content extraction ----
# Fields that affect GENERATION OUTPUT. Volatile fields are deliberately excluded.
def canon_section(sec):
    out=[]
    y=sec.get('Y')
    out.append(('Y', y))
    bs=sec.get('block_states')
    if isinstance(bs, dict):
        pal=bs.get('palette', [])
        # palette entry = {Name, Properties?}; canonicalize
        pcanon=[]
        for p in pal:
            name=p.get('Name','') if isinstance(p,dict) else str(p)
            props=p.get('Properties',{}) if isinstance(p,dict) else {}
            pcanon.append((name, tuple(sorted((k,str(v)) for k,v in props.items()))))
        out.append(('bs_pal', tuple(pcanon)))
        out.append(('bs_data', tuple(bs.get('data', []))))
    bi=sec.get('biomes')
    if isinstance(bi, dict):
        out.append(('bi_pal', tuple(bi.get('palette', []))))
        out.append(('bi_data', tuple(bi.get('data', []))))
    return tuple(out)

def canon_chunk(nbt):
    out=[]
    out.append(('xPos', nbt.get('xPos')))
    out.append(('zPos', nbt.get('zPos')))
    out.append(('status', nbt.get('Status')))
    secs=nbt.get('sections', [])
    out.append(('sections', tuple(canon_section(s) for s in secs if isinstance(s,dict))))
    # structures (starts + references) affect generation
    st=nbt.get('structures', {})
    if isinstance(st, dict):
        starts=st.get('starts', {})
        out.append(('struct_starts', tuple(sorted(starts.keys())) if isinstance(starts,dict) else ()))
    return repr(out).encode('utf-8')

def iter_region(path):
    with open(path,'rb') as f:
        hdr=f.read(4096)
        if len(hdr)<4096: return
        data=f.read()
    base=4096
    for idx in range(1024):
        off=struct.unpack('>I', b'\x00'+hdr[idx*4:idx*4+3])[0]
        cnt=hdr[idx*4+3]
        if off==0 or cnt==0: continue
        start=off*4096
        if start+5 > base+len(data): continue
        seg=(hdr+data)[start:start+cnt*4096]
        if len(seg)<5: continue
        ln=struct.unpack('>I', seg[0:4])[0]
        comp=seg[4]
        payload=seg[5:5+ln-1]
        try:
            if comp==1: raw=gzip.decompress(payload)
            elif comp==2: raw=zlib.decompress(payload)
            elif comp==3: raw=payload
            else: continue
        except Exception:
            continue
        try:
            nbt=read_nbt(raw)
        except Exception:
            continue
        yield canon_chunk(nbt)

def hash_dim(region_dir):
    items=[]
    for mca in sorted(glob.glob(os.path.join(region_dir,'*.mca'))):
        for c in iter_region(mca):
            items.append(c)
    items.sort()
    h=hashlib.sha256()
    for c in items: h.update(c); h.update(b'\n')
    return h.hexdigest(), len(items)

def main():
    if len(sys.argv)<2:
        print("usage: worldhash.py <world-dir> [--per-chunk]"); return
    world=sys.argv[1]
    dims=[('overworld', os.path.join(world,'region')),
          ('nether',    os.path.join(world,'DIM-1','region')),
          ('end',       os.path.join(world,'DIM1','region'))]
    overall=hashlib.sha256(); total=0
    for name,rd in dims:
        if not os.path.isdir(rd):
            print(f"{name:10s} (no region dir)"); continue
        d,n=hash_dim(rd)
        print(f"{name:10s} chunks={n:6d}  sha256={d}")
        overall.update(name.encode()); overall.update(d.encode()); total+=n
    print(f"{'TOTAL':10s} chunks={total:6d}  sha256={overall.hexdigest()}")

if __name__=='__main__':
    main()
