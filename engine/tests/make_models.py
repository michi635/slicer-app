"""Writes the test models (binary STL) used by the engine test.
usage: make_models.py <out-dir>
"""
import math
import os
import struct
import sys


def write_stl(path, triangles):
    with open(path, "wb") as f:
        f.write(b"slicer-app test model".ljust(80, b" "))
        f.write(struct.pack("<I", len(triangles)))
        for a, b, c in triangles:
            f.write(struct.pack("<3f", 0.0, 0.0, 0.0))
            for v in (a, b, c):
                f.write(struct.pack("<3f", *v))
            f.write(b"\0\0")


def box(x0, y0, z0, x1, y1, z1):
    p = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    quads = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    tris = []
    for a, b, c, d in quads:
        tris += [(p[a], p[b], p[c]), (p[a], p[c], p[d])]
    return tris


def frustum(r0, r1, h, n=96):
    """A closed lamp-shade-like body, wider at the bottom."""
    tris = []
    for i in range(n):
        a0 = 2 * math.pi * i / n
        a1 = 2 * math.pi * (i + 1) / n
        b0 = (r0 * math.cos(a0), r0 * math.sin(a0), 0.0)
        b1 = (r0 * math.cos(a1), r0 * math.sin(a1), 0.0)
        t0 = (r1 * math.cos(a0), r1 * math.sin(a0), h)
        t1 = (r1 * math.cos(a1), r1 * math.sin(a1), h)
        tris += [(b0, b1, t1), (b0, t1, t0)]
        tris += [((0.0, 0.0, 0.0), b1, b0)]
        tris += [((0.0, 0.0, h), t0, t1)]
    return tris


def main(out):
    os.makedirs(out, exist_ok=True)
    write_stl(os.path.join(out, "cube20.stl"), box(0, 0, 0, 20, 20, 20))
    # A post with an overhanging plate on top: needs support
    write_stl(os.path.join(out, "overhang.stl"), box(15, 0, 0, 25, 10, 30) + box(0, 0, 30, 40, 10, 34))
    write_stl(os.path.join(out, "lampshade.stl"), frustum(45, 30, 90))
    print("models written to", out)


if __name__ == "__main__":
    main(sys.argv[1])
