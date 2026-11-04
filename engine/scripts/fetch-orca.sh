#!/usr/bin/env bash
# Check out the pinned OrcaSlicer commit into <dest> and apply the Android patches.
# usage: fetch-orca.sh <dest>
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/orca.env"
DEST="$1"

if [ ! -d "$DEST/.git" ]; then
  git init -q "$DEST"
  git -C "$DEST" remote add origin "$ORCA_REPO"
fi
git -C "$DEST" fetch -q --depth 1 origin "$ORCA_COMMIT"
git -C "$DEST" checkout -q --force "$ORCA_COMMIT"
git -C "$DEST" clean -q -fdx

# 1. Android dependency superbuild and engine fixes (from github.com/LucasCoraca/orcaslicer-android, AGPL-3.0)
git -C "$DEST" apply --whitespace=nowarn "$HERE/patches/orcaslicer-android.patch"
# 2. Our own small fixes, applied in name order
for p in "$HERE"/patches/local/*.patch; do
  [ -e "$p" ] || continue
  echo "apply $(basename "$p")"
  git -C "$DEST" apply --whitespace=nowarn "$p"
done
echo "OrcaSlicer $(git -C "$DEST" log -1 --format='%h %cd') ready in $DEST"
