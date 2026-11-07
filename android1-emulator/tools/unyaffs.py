#!/usr/bin/env python3
"""Minimal yaffs2 image extractor for the Android 1.6 SDK images (2048+64 pages)."""
import os
import struct
import sys

PAGE = 2048
SPARE = 64
OBJ_FILE, OBJ_SYMLINK, OBJ_DIR, OBJ_HARDLINK, OBJ_SPECIAL = 1, 2, 3, 4, 5


def cstr(buf, off, maxlen=256):
    end = buf.find(b"\0", off, off + maxlen)
    if end < 0:
        return None
    raw = buf[off:end]
    if not raw:
        return None
    if any(c < 0x20 or c > 0x7E for c in raw):
        return None
    return raw.decode("ascii")


NAME_OFF = 10
MODE_OFF = NAME_OFF + 258
SIZE_OFF = MODE_OFF + 24
ALIAS_OFF = SIZE_OFF + 8
RDEV_OFF = ALIAS_OFF + 160


def main(img_path, out_dir):
    data = open(img_path, "rb").read()
    nchunks = len(data) // (PAGE + SPARE)
    objects = {}
    filedata = {}
    for i in range(nchunks):
        base = i * (PAGE + SPARE)
        page = data[base:base + PAGE]
        spare = data[base + PAGE:base + PAGE + SPARE]
        seq, obj, chunk, bytecount = struct.unpack("<IIII", spare[:16])
        if obj == 0xFFFFFFFF or chunk == 0xFFFFFFFF or obj == 0:
            continue
        if chunk == 0:
            kind = page[0]
            parent = struct.unpack("<I", page[4:8])[0]
            name = cstr(page, NAME_OFF)
            if name is None:
                if parent != obj:
                    continue
                name = ""
            name_end = MODE_OFF
            mode, uid, gid = struct.unpack("<III", page[name_end:name_end + 12])
            size, equiv = struct.unpack("<ii", page[SIZE_OFF:SIZE_OFF + 8])
            alias = cstr(page, ALIAS_OFF, 160) or ""
            rdev = struct.unpack("<I", page[RDEV_OFF:RDEV_OFF + 4])[0]
            objects[obj] = dict(type=kind, parent=parent, name=name, mode=mode,
                                uid=uid, gid=gid, size=size, equiv=equiv,
                                alias=alias, rdev=rdev)
        else:
            filedata.setdefault(obj, {})[chunk] = page

    def path_of(obj_id, depth=0):
        if depth > 64 or obj_id not in objects:
            return None
        o = objects[obj_id]
        if o["parent"] == obj_id or o["parent"] == 0:
            return ""
        parent = path_of(o["parent"], depth + 1)
        if parent is None:
            return None
        return os.path.join(parent, o["name"])

    written = 0
    entries = []
    for obj_id, o in objects.items():
        p = path_of(obj_id)
        if p is None or p == "":
            continue
        dest = os.path.join(out_dir, p)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        t = o["type"]
        try:
            if t == OBJ_DIR:
                os.makedirs(dest, exist_ok=True)
            elif t == OBJ_FILE:
                chunks = filedata.get(obj_id, {})
                with open(dest, "wb") as f:
                    for cid in sorted(chunks):
                        f.write(chunks[cid])
                if o["size"] > 0:
                    os.truncate(dest, o["size"])
                written += 1
            elif t == OBJ_SYMLINK and o["alias"]:
                if os.path.lexists(dest):
                    os.remove(dest)
                os.symlink(o["alias"], dest)
            elif t == OBJ_HARDLINK and o["equiv"] in objects:
                src = os.path.join(out_dir, path_of(o["equiv"]) or "")
                if os.path.isfile(src):
                    os.link(src, dest)
            elif t == OBJ_SPECIAL:
                pass
        except FileExistsError:
            pass
        except OSError as e:
            print(f"warn: {p}: {e}", file=sys.stderr)
            continue
        entries.append((dest, o["mode"], o["uid"], o["gid"], t))

    # Apply ownership and modes after everything exists. Directories go deepest
    # first so restrictive modes don't block creating their children.
    if os.geteuid() == 0:
        entries.sort(key=lambda e: e[0].count(os.sep) + (0 if e[4] == OBJ_DIR else 1),
                     reverse=True)
        for dest, mode, uid, gid, t in entries:
            try:
                if t == OBJ_SYMLINK:
                    os.lchown(dest, uid, gid)
                    continue
                os.chown(dest, uid, gid)
                if mode:
                    os.chmod(dest, mode & 0o7777)
            except OSError as e:
                print(f"warn: perms {dest}: {e}", file=sys.stderr)
    print(f"extracted {len(objects)} objects ({written} files) from {img_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
