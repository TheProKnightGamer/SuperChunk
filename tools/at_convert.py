#!/usr/bin/env python3
"""Convert mojmap AccessTransformer lines to SRG-named ones for the Forge 1.20.1 port.

Usage:  python3 tools/at_convert.py <<'EOF'
public net.minecraft.server.level.ChunkMap updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;[Lnet/minecraft/network/protocol/Packet;ZZ)V
public net.minecraft.server.level.DistanceManager tickets
EOF

Reads mojmap AT lines on stdin (same syntax as accesstransformer.cfg: fields by name,
methods by name+descriptor), resolves each member through build/createMcpToSrg/output.tsrg
(tsrg2, left=mojmap right=SRG), and prints the SRG-named AT line. Lines whose member
cannot be resolved are printed with a '# UNRESOLVED:' prefix so nothing silently passes.
Class-only lines (no member) pass through unchanged (class names are identical).
Run from the forge-1.20.1 directory (or pass the tsrg path as argv[1]).
"""
import sys
import re
from pathlib import Path

tsrg_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/createMcpToSrg/output.tsrg")
if not tsrg_path.exists():
    sys.exit(f"tsrg not found: {tsrg_path} (run ./gradlew createMcpToSrg or any build first)")

# parse tsrg2: class lines unindented "left right"; member lines indented
# fields:  "\tleftName rightName"            methods: "\tleftName desc rightName"
fields = {}   # (class, name) -> srg
methods = {}  # (class, name, desc) -> srg
cur = None
for line in tsrg_path.read_text().splitlines():
    if not line or line.startswith("tsrg2"):
        continue
    if not line.startswith("\t"):
        cur = line.split()[0].replace("/", ".")
        continue
    parts = line.strip().split()
    if cur is None or line.startswith("\t\t"):
        continue
    if len(parts) == 2:
        fields[(cur, parts[0])] = parts[1]
    elif len(parts) == 3:
        methods[(cur, parts[0], parts[1])] = parts[2]

member_re = re.compile(r"^(?P<mod>\S+(?:[-+]f)?)\s+(?P<cls>[\w.$]+)\s+(?P<member>\S+)(?P<rest>.*)$")

for raw in sys.stdin.read().splitlines():
    line = raw.strip()
    if not line or line.startswith("#"):
        print(raw)
        continue
    m = member_re.match(line)
    if not m:
        print(raw)
        continue
    mod, cls, member, rest = m.group("mod"), m.group("cls"), m.group("member"), m.group("rest")
    # class-only line: member field absent -> the regex requires one; class-only lines have
    # exactly two tokens, so member_re fails above and they pass through. Here member exists.
    if "(" in member:
        name, desc = member.split("(", 1)
        desc = "(" + desc
        srg = methods.get((cls, name, desc))
        if srg is None and name in ("<init>", "<clinit>"):
            srg = name
        if srg is None:
            print(f"# UNRESOLVED: {raw}")
        else:
            print(f"{mod} {cls} {srg}{desc}{rest} # {name}")
    else:
        srg = fields.get((cls, member))
        if srg is None:
            print(f"# UNRESOLVED: {raw}")
        else:
            print(f"{mod} {cls} {srg}{rest} # {member}")
