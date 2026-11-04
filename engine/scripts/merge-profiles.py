"""Merge our own profiles (engine/profiles/<Vendor>/<type>/...json) into OrcaSlicer's vendor folders.

Each extra file is copied next to the vendor's own ones and listed in the vendor index (<Vendor>.json), after the
presets it inherits from, so OrcaSlicer loads it like a system profile.
usage: merge-profiles.py <extra-profiles-dir> <system-dir>
"""
import json
import os
import shutil
import sys

LISTS = {"filament": "filament_list", "process": "process_list", "machine": "machine_list"}


def main(extra, system):
    if not os.path.isdir(extra):
        return
    for vendor in sorted(os.listdir(extra)):
        vdir = os.path.join(extra, vendor)
        index_path = os.path.join(system, vendor + ".json")
        if not os.path.isdir(vdir) or not os.path.isfile(index_path):
            continue
        with open(index_path, encoding="utf-8") as f:
            index = json.load(f)
        added = 0
        for kind, list_key in LISTS.items():
            kdir = os.path.join(vdir, kind)
            if not os.path.isdir(kdir):
                continue
            entries = index.setdefault(list_key, [])
            known = {e["name"] for e in entries}
            for root, _, files in os.walk(kdir):
                for name in sorted(files):
                    if not name.endswith(".json"):
                        continue
                    src = os.path.join(root, name)
                    with open(src, encoding="utf-8") as f:
                        preset = json.load(f)
                    sub_path = os.path.relpath(src, vdir).replace(os.sep, "/")
                    dst = os.path.join(system, vendor, sub_path)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copyfile(src, dst)
                    if preset["name"] not in known:
                        entries.append({"name": preset["name"], "sub_path": sub_path})
                        known.add(preset["name"])
                        added += 1
        with open(index_path, "w", encoding="utf-8") as f:
            json.dump(index, f, indent=4, ensure_ascii=False)
        print(f"{vendor}: {added} extra profiles")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
