#!/usr/bin/env bash
# Runs the engine on a connected Android device or emulator (adb) with the test jobs.
# usage: run-on-device.sh <test-dir>
#   <test-dir>/engine/   libslicercore.so + libc++_shared.so for the device's ABI
#   <test-dir>/res/orca.zip
#   <test-dir>/models/   test STL files (make_models.py)
# Results (engine replies, logs, G-code) go to <test-dir>/results.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
T="$(realpath "$1")"
D=/data/local/tmp/slicer
R="$T/results"
mkdir -p "$R" "$T/orca"
unzip -q -o "$T/res/orca.zip" -d "$T/orca"

adb shell "rm -rf $D; mkdir -p $D/orca $D/models $D/out"
adb push -q "$T/engine/libslicercore.so" "$T/engine/libc++_shared.so" "$D/"
adb push -q "$T/orca/." "$D/orca/"
adb push -q "$T/models/." "$D/models/"
adb shell "chmod 755 $D/libslicercore.so"
adb shell getprop ro.product.cpu.abi

# run <name> <engine arguments...>: one engine call on the device, reply in $R/<name>.txt, stderr in $R/<name>.log
run() {
  local name="$1"; shift
  local args=""
  for a in "$@"; do args="$args '$a'"; done
  local start=$(date +%s)
  adb shell "cd $D && LD_LIBRARY_PATH=$D ./libslicercore.so $args 2> $D/out/$name.log" > "$R/$name.txt"
  echo "$name: exit $? after $(( $(date +%s) - start )) s"
  adb pull -q "$D/out/$name.log" "$R/$name.log" 2>/dev/null || true
}

run version version
run describe-all describe "$D/orca/resources" "$D/orca/data"
run describe-s1 describe "$D/orca/resources" "$D/orca/data" "Anycubic Kobra S1 0.4 nozzle"
run describe-3max describe "$D/orca/resources" "$D/orca/data" "Anycubic Kobra 3 Max 0.4 nozzle"

for job in "$HERE"/jobs/*.json; do
  name="$(basename "$job" .json)"
  sed "s#@MODELS@#$D/models#g" "$job" > "$R/$name.job.json"
  adb push -q "$R/$name.job.json" "$D/$name.json"
  run "$name" slice "$D/orca/resources" "$D/orca/data" "$D/$name.json" "$D/out/$name.gcode"
  adb pull -q "$D/out/$name.gcode" "$R/$name.gcode" 2>/dev/null || true
done
ls -la "$R"
exit 0
