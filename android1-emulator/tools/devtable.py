import struct, sys

path = sys.argv[1]
d = open(path, "rb").read()

# ELF32 header
e_phoff = struct.unpack("<I", d[28:32])[0]
e_phentsize, e_phnum = struct.unpack("<HH", d[42:46])

segs = []
for i in range(e_phnum):
    off = e_phoff + i * e_phentsize
    p_type, p_offset, p_vaddr, _, p_filesz = struct.unpack("<IIIII", d[off:off + 20])
    if p_type == 1:
        segs.append((p_offset, p_vaddr, p_filesz))

def va_to_off(va):
    for o, v, sz in segs:
        if v <= va < v + sz:
            return o + (va - v)
    return None

targets = ["/dev/ashmem", "/dev/binder", "/dev/log/main", "/dev/log/radio",
           "/dev/alarm", "/dev/graphics/fb0", "/dev/input", "/dev/uinput"]

for t in targets:
    idx = d.find(t.encode() + b"\0")
    if idx < 0:
        print(f"{t}: string not found")
        continue
    va = None
    for o, v, sz in segs:
        if o <= idx < o + sz:
            va = v + (idx - o)
    if va is None:
        print(f"{t}: not in a mapped segment")
        continue
    hits = []
    needle = struct.pack("<I", va)
    pos = 0
    while True:
        pos = d.find(needle, pos)
        if pos < 0:
            break
        # mode is 16-bit in the table; sanity check the triple that follows
        maj, mino = struct.unpack("<ii", d[pos + 4:pos + 12])
        mode = struct.unpack("<I", d[pos + 12:pos + 16])[0]
        if 0 <= maj < 512 and 0 <= mino < 1 << 20:
            hits.append((maj, mino, oct(mode)))
        pos += 1
    print(f"{t}: va={va:#x} table entries={hits[:4]}")
