"""Checks the engine test results (run-on-device.sh): engine replies and the G-code itself.
usage: check_results.py <results-dir>
Exit code 1 if any check fails; a summary is printed either way.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
failures = []


def fail(what):
    failures.append(what)
    print("FAIL", what)


def ok(what):
    print("ok  ", what)


def reply(results, name):
    path = os.path.join(results, name + ".txt")
    if not os.path.isfile(path):
        fail(f"{name}: no reply")
        return None
    progress = 0
    result = None
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.rstrip("\r\n")
        if line.startswith("PROGRESS "):
            progress += 1
        elif line.startswith("RESULT "):
            result = json.loads(line[7:])
    if result is None:
        log = os.path.join(results, name + ".log")
        tail = open(log, encoding="utf-8", errors="replace").read()[-1500:] if os.path.isfile(log) else ""
        fail(f"{name}: no RESULT line (crash?)\n{tail}")
    return result


def check_describe(results):
    r = reply(results, "describe-all")
    if r and r.get("ok"):
        models = {p["model"] for p in r["printers"]}
        for m in ("Anycubic Kobra S1", "Anycubic Kobra 3 Max"):
            (ok if m in models else fail)(f"describe: printer model {m}")
    elif r:
        fail(f"describe-all: {r.get('error')}")
    # Kobra S1: Anycubic's 0.20 mm profile plus our seven derived ones (engine/profiles/Anycubic/process)
    # Without a process the printer's own default is selected (never OrcaSlicer's built-in "- Default Print -"),
    # and the values shown in the app are that profile's: Anycubic uses 15 % infill and tree supports.
    for name, printer, processes in (("describe-s1", "Anycubic Kobra S1 0.4 nozzle", 8),
                                     ("describe-3max", "Anycubic Kobra 3 Max 0.4 nozzle", 6)):
        default_process = "0.20mm Standard @" + printer
        r = reply(results, name)
        if not r:
            continue
        if not r.get("ok"):
            fail(f"{name}: {r.get('error')}")
            continue
        (ok if r["selected"]["printer"] == printer else fail)(f"{name}: selected printer {r['selected']['printer']}")
        (ok if r["selected"]["process"] == default_process else fail)(f"{name}: selected process {r['selected']['process']}")
        for key, value in (("sparse_infill_density", "15%"), ("support_type", "tree(auto)")):
            (ok if r["values"].get(key) == value else fail)(f"{name}: profile value {key} = {r['values'].get(key)} (expected {value})")
        (ok if len(r["processes"]) >= processes else fail)(f"{name}: {len(r['processes'])} processes (expected >= {processes})")
        vendors = {f.get("vendor") for f in r["filaments"]}
        names = [f["name"] for f in r["filaments"]]
        for brand in ("Anycubic", "Extrudr"):
            (ok if any(v and v.startswith(brand) for v in vendors) else fail)(f"{name}: {brand} filaments")
        (ok if any(n.startswith("Bambu") for n in names) else fail)(f"{name}: Bambu Lab filaments")
        for key in ("sparse_infill_density", "sparse_infill_pattern", "wall_loops", "brim_type", "support_type", "spiral_mode"):
            (ok if key in r["values"] else fail)(f"{name}: value {key} = {r['values'].get(key)}")
        print(f"     {len(r['filaments'])} filaments, processes: {[p['name'] for p in r['processes']]}")


MOVE = re.compile(r"^G[01]\s")
# OrcaSlicer drops the leading zero of values below 1 ("E.0234"), so the integer part is optional.
COORD = re.compile(r"([XYZEF])(-?(?:\d+\.?\d*|\.\d+))")


def check_gcode(name, path, expect):
    text = open(path, encoding="utf-8", errors="replace").read()
    lines = text.splitlines()
    (ok if len(lines) > 500 else fail)(f"{name}: {len(lines)} G-code lines")
    if "start" in expect:
        (ok if expect["start"] in text else fail)(f"{name}: start G-code contains {expect['start']}")
    for needle in expect.get("contains", []):
        (ok if needle in text else fail)(f"{name}: G-code contains {needle!r}")
    if expect.get("thumbnail"):
        (ok if "thumbnail begin" in text or "thumbnail_PNG begin" in text else fail)(f"{name}: thumbnail for the printer screen")
    tool_changes = sum(1 for l in lines if re.match(r"^T\d+\s*(;.*)?$", l.strip()))
    if "tool_changes" in expect:
        (ok if tool_changes == expect["tool_changes"] else fail)(f"{name}: {tool_changes} tool changes (expected {expect['tool_changes']})")
    if "tool_changes_min" in expect:
        (ok if tool_changes >= expect["tool_changes_min"] else fail)(f"{name}: {tool_changes} tool changes (expected >= {expect['tool_changes_min']})")
    if "nozzle_temp" in expect:
        t = expect["nozzle_temp"]
        found = re.search(rf"(M10[49]\s.*S{t}\b|extruderTemp={t}\b)", text)
        (ok if found else fail)(f"{name}: nozzle temperature {t} °C set")
    # Extrusion moves stay on the bed
    x = y = 0.0
    lo_x = lo_y = 1e9
    hi_x = hi_y = -1e9
    relative = False
    extrusions = 0
    for l in lines:
        s = l.split(";")[0].strip()
        if s.startswith("G91"):
            relative = True
        elif s.startswith("G90"):
            relative = False
        if not MOVE.match(s) or relative:
            continue
        c = dict(COORD.findall(s))
        if "X" in c:
            x = float(c["X"])
        if "Y" in c:
            y = float(c["Y"])
        if "E" in c and float(c["E"]) > 0 and ("X" in c or "Y" in c):
            lo_x, lo_y, hi_x, hi_y = min(lo_x, x), min(lo_y, y), max(hi_x, x), max(hi_y, y)
            extrusions += 1
    if extrusions < 100:
        fail(f"{name}: only {extrusions} extrusion moves found")
        return
    bx0, by0, bx1, by1 = expect.get("bed", [0, 0, 1e9, 1e9])
    inside = lo_x >= bx0 - 0.5 and lo_y >= by0 - 0.5 and hi_x <= bx1 + 0.5 and hi_y <= by1 + 0.5
    (ok if inside else fail)(f"{name}: {extrusions} extrusion moves within X {lo_x:.1f}..{hi_x:.1f}, Y {lo_y:.1f}..{hi_y:.1f}")


def check_jobs(results):
    for fn in sorted(os.listdir(os.path.join(HERE, "jobs"))):
        name = fn[:-5]
        expect = json.load(open(os.path.join(HERE, "jobs", fn), encoding="utf-8")).get("expect", {})
        r = reply(results, name)
        if r is None:
            continue
        if not expect.get("ok", True):
            (ok if not r.get("ok") else fail)(f"{name}: rejected as expected ({r.get('error')})")
            continue
        if not r.get("ok"):
            fail(f"{name}: {r.get('error')}")
            continue
        s = r.get("summary", {})
        print(f"     {name}: {s.get('time', 0) / 60:.0f} min, {s.get('weight', 0):.1f} g, {s.get('layer_count')} layers, "
              f"{s.get('filament_changes')} filament changes, warnings {r.get('warnings')}")
        path = os.path.join(results, name + ".gcode")
        if not os.path.isfile(path):
            fail(f"{name}: no G-code file")
            continue
        check_gcode(name, path, expect)


def main(results):
    v = reply(results, "version")
    if v:
        print("engine", v.get("version"))
    check_describe(results)
    check_jobs(results)
    print()
    print(f"{len(failures)} failures")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main(sys.argv[1])
