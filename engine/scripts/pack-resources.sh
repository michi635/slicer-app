#!/usr/bin/env bash
# Pack what the engine reads at run time into the app asset orca.zip:
#   resources/{info,flush,filament_mixing}    OrcaSlicer resource files libslic3r opens
#   data/system/<Vendor>.json + <Vendor>/      vendor profiles (JSON only), as OrcaSlicer keeps them in its data dir
# Extra profiles from engine/profiles/<Vendor>/ are merged into the vendor's index.
# usage: pack-resources.sh <orca-src> <out.zip>
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$1"; OUT="$(realpath -m "$2")"
VENDORS="Anycubic OrcaFilamentLibrary"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/resources" "$STAGE/data/system"
cp -r "$SRC/resources/info" "$SRC/resources/flush" "$SRC/resources/filament_mixing" "$STAGE/resources/"

for v in $VENDORS; do
  cp "$SRC/resources/profiles/$v.json" "$STAGE/data/system/"
  (cd "$SRC/resources/profiles" && find "$v" -name '*.json' -print0 | xargs -0 -I{} cp --parents {} "$STAGE/data/system/")
done

python3 -I "$HERE/scripts/merge-profiles.py" "$HERE/profiles" "$STAGE/data/system"

rm -f "$OUT"
(cd "$STAGE" && zip -q -r -9 "$OUT" .)
ls -la "$OUT"
